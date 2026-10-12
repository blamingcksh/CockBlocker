package com.cockblocker.app.data;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Recovery invariants for the two-slot journal. These run on the JVM against a
 * temp directory and a locally generated HmacSHA256 key ({@link JournalKey#forTest});
 * production always resolves the key from Android Keystore.
 */
public final class JournalRecoveryTest {
    private File filesDir;
    private JournalKey key;

    @Before
    public void setUp() throws Exception {
        filesDir = Files.createTempDirectory("cockblocker-journal").toFile();
        key = JournalKey.forTest(HmacTestKey.generate());
    }

    @After
    public void tearDown() {
        deleteTree(filesDir);
    }

    @Test
    public void missingHeadRebuiltFromChainedTip() {
        SessionRecord genesis = record(1, null, SessionState.IDLE);
        SessionRecord released = record(2, hashOf(genesis), SessionState.RELEASED);
        writeRound(genesis, released, null, -1);

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(storage(), key);

        assertEquals(JournalRecovery.Status.HEAD_REPAIRED, result.getStatus());
        assertEquals("rebuilt missing commit head", result.getReason());
        assertNotNull(result.getRecord());
        assertEquals(2L, result.getRecord().getSequence());
        // Lossless slot derivation: the tip occupies slot 1, so the next commit
        // alternates away from it instead of overwriting the only valid chain.
        assertEquals(1, result.getSlot());
    }

    @Test
    public void missingHeadStaysTamperedWithoutChainLink() {
        SessionRecord genesis = record(1, null, SessionState.IDLE);
        SessionRecord unrelated = record(2, foreignHash(7), SessionState.IDLE);
        writeRound(genesis, unrelated, null, -1);

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(storage(), key);

        assertEquals(JournalRecovery.Status.TAMPERED, result.getStatus());
        assertNull(result.getRecord());
    }

    @Test
    public void missingHeadStaysTamperedForSameSequenceCollision() {
        // Two same-sequence, distinct-hash records: no head to disambiguate, and neither
        // chains onto the other.
        SessionRecord first = record(7, foreignHash(1), SessionState.IDLE);
        SessionRecord second = record(7, foreignHash(2), SessionState.RELEASED);
        writeRound(first, second, null, -1);

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(storage(), key);

        assertEquals(JournalRecovery.Status.TAMPERED, result.getStatus());
        assertNull(result.getRecord());
    }

    @Test
    public void staleHeadPromotesChainedSuccessor() {
        SessionRecord genesis = record(1, null, SessionState.IDLE);
        SessionRecord successor = record(2, hashOf(genesis), SessionState.RELEASED);
        writeRound(genesis, successor, genesis, 0);

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(storage(), key);

        assertEquals(JournalRecovery.Status.HEAD_REPAIRED, result.getStatus());
        assertEquals("promoted newer chained record", result.getReason());
        assertNotNull(result.getRecord());
        assertEquals(2L, result.getRecord().getSequence());
        assertEquals(1, result.getSlot());
    }

    @Test
    public void healthyChainReportsItsSlot() {
        SessionRecord genesis = record(1, null, SessionState.IDLE);
        SessionRecord successor = record(2, hashOf(genesis), SessionState.RELEASED);
        writeRound(genesis, successor, successor, 1);

        JournalRecovery.RecoveryResult result = JournalRecovery.recover(storage(), key);

        assertEquals(JournalRecovery.Status.HEALTHY, result.getStatus());
        assertEquals("authenticated chain", result.getReason());
        assertNotNull(result.getRecord());
        assertEquals(2L, result.getRecord().getSequence());
        assertEquals(1, result.getSlot());
    }

    @Test
    public void repairSurvivesACommitAfterwards() {
        SessionRecord genesis = record(1, null, SessionState.IDLE);
        SessionRecord released = record(2, hashOf(genesis), SessionState.RELEASED);
        writeRound(genesis, released, null, -1);

        SessionJournalStorage storage = storage();
        JournalRecovery.RecoveryResult repaired = JournalRecovery.recover(storage, key);
        assertEquals(1, repaired.getSlot());

        // commitLocked derives its slot from alternating away from the recovered tip.
        int nextSlot = repaired.getSlot() == 0 ? 1 : 0;
        SessionRecord next = record(3, hashOf(released), SessionState.RELEASED);
        storage.writeSlot(nextSlot, next, key);
        storage.writeHead(next, key, nextSlot);

        JournalRecovery.RecoveryResult after = JournalRecovery.recover(storage, key);
        assertEquals(JournalRecovery.Status.HEALTHY, after.getStatus());
        assertNotNull(after.getRecord());
        assertEquals(3L, after.getRecord().getSequence());
    }

    private SessionJournalStorage storage() {
        return new SessionJournalStorage(filesDir);
    }

    /** Writes both slots plus an optional head, mirroring a commit sequence's artifacts. */
    private void writeRound(SessionRecord first, SessionRecord second, SessionRecord headRecord, int headSlot) {
        SessionJournalStorage storage = storage();
        storage.writeSlot(0, first, key);
        storage.writeSlot(1, second, key);
        if (headRecord != null) {
            storage.writeHead(headRecord, key, headSlot);
        }
    }

    private static SessionRecord record(long sequence, byte[] previousRecordHash, SessionState state) {
        // recordHash is deliberately left null: encodeRecord computes it from the
        // canonical payload, exactly as the journal does for every real commit.
        return new SessionRecord(SessionRecord.CURRENT_SCHEMA_VERSION,
                "session-" + sequence, sequence, previousRecordHash, state,
                1000L, 1000L, 2000L, 1000L, 1000L, 1000L, 1000L, "",
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                "cert", 1, "TEST", "TEST", null, null);
    }

    /** A valid-length predecessor hash that links to no record either slot holds. */
    private static byte[] foreignHash(int fill) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) fill);
        return hash;
    }

    /**
     * The record as storage would hand it back: with the hash the codec computed.
     * Callers that need the hash of a record they are ABOUT to commit use the raw
     * helper, since encoding assigns the hash.
     */
    private static byte[] hashOf(SessionRecord record) { return SessionJournalCodec.contentHash(record); }

    private static SessionRecord persisted(SessionRecord record) {
        return record.getRecordHash() == null ? record.withRecordHash(
                SessionJournalCodec.contentHash(record)) : record;
    }

    private static void deleteTree(File root) {
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        root.delete();
    }

    /** Local HmacSHA256 key: the JVM has no Android Keystore. */
    private static final class HmacTestKey {
        static SecretKey generate() throws Exception {
            KeyGenerator generator = KeyGenerator.getInstance("HmacSHA256");
            generator.init(256);
            return generator.generateKey();
        }
    }
}
