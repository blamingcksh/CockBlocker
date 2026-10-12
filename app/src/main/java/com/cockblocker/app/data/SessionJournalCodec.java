package com.cockblocker.app.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Fixed-order, bounded binary format for records and journal pointers. */
public final class SessionJournalCodec {
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    public static final int MAX_STRING_BYTES = 4096;
    public static final int MAX_SET_ENTRIES = 512;
    private static final byte[] RECORD_MAGIC = new byte[] { 'C', 'B', 'R', '1' };
    private static final byte[] HEAD_MAGIC = new byte[] { 'C', 'B', 'H', '1' };
    private static final String RECORD_DOMAIN = "cockblocker/journal/record/v1";
    private static final String HEAD_DOMAIN = "cockblocker/journal/head/v1";
    private static final String BOOTSTRAP_DOMAIN = "cockblocker/journal/bootstrap/v1";

    private SessionJournalCodec() { }

    public static byte[] encodeRecord(SessionRecord record, JournalKey key) {
        return encodeRecordInternal(record, key, RECORD_DOMAIN, RECORD_MAGIC);
    }

    public static byte[] encodeBootstrap(SessionRecord record, JournalKey key) {
        return encodeRecordInternal(record, key, BOOTSTRAP_DOMAIN, RECORD_MAGIC);
    }

    public static SessionRecord decodeRecord(byte[] bytes, JournalKey key) {
        return decodeRecordInternal(bytes, key, RECORD_DOMAIN);
    }

    public static SessionRecord decodeBootstrap(byte[] bytes, JournalKey key) {
        return decodeRecordInternal(bytes, key, BOOTSTRAP_DOMAIN);
    }

    /** A head is written with an explicit slot by storage; this overload uses slot -1. */
    public static byte[] encodeHead(SessionRecord record, JournalKey key) {
        return encodeHead(record, key, -1);
    }

    public static byte[] encodeHead(SessionRecord record, JournalKey key, int slot) {
        requireKey(key);
        if (record == null || record.getSequence() < 1) {
            throw new IllegalArgumentException("committed record is required");
        }
        byte[] recordHash = record.getRecordHash() == null ? record.contentHash() : record.getRecordHash();
        if (slot < -1 || slot > 1) {
            throw new IllegalArgumentException("invalid slot");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);
            data.write(HEAD_MAGIC);
            writeInt(data, 1);
            writeString(data, record.getSessionId());
            data.writeLong(record.getSequence());
            data.write(recordHash);
            data.writeInt(slot);
            data.flush();
            byte[] payload = out.toByteArray();
            byte[] mac = key.hmac(HEAD_DOMAIN, payload);
            return concat(payload, mac);
        } catch (IOException impossible) {
            throw new IllegalStateException("head encoding failed", impossible);
        }
    }

    public static Head decodeHead(byte[] bytes, JournalKey key) {
        requireKey(key);
        if (bytes == null || bytes.length < HEAD_MAGIC.length + 4 + 32 + 4 + 32) {
            throw new IllegalArgumentException("short head");
        }
        int payloadLength = bytes.length - 32;
        if (payloadLength > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("head too large");
        }
        byte[] payload = Arrays.copyOfRange(bytes, 0, payloadLength);
        byte[] mac = Arrays.copyOfRange(bytes, payloadLength, bytes.length);
        if (!JournalKey.equal(key.hmac(HEAD_DOMAIN, payload), mac)) {
            throw new IllegalArgumentException("head MAC mismatch");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            byte[] magic = new byte[HEAD_MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, HEAD_MAGIC)) {
                throw new IllegalArgumentException("bad head magic");
            }
            int version = in.readInt();
            if (version != 1) {
                throw new IllegalArgumentException("unsupported head version");
            }
            String sessionId = readString(in);
            long sequence = in.readLong();
            byte[] hash = new byte[SessionRecord.HASH_SIZE];
            in.readFully(hash);
            int slot = in.readInt();
            if (in.available() != 0 || sequence < 1 || slot < -1 || slot > 1) {
                throw new IllegalArgumentException("non-canonical head");
            }
            return new Head(sessionId, sequence, hash, slot);
        } catch (EOFException failure) {
            throw new IllegalArgumentException("truncated head", failure);
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid head", failure);
        }
    }

    public static byte[] contentHash(SessionRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record is required");
        }
        return sha256(canonicalContent(record));
    }

    private static byte[] encodeRecordInternal(SessionRecord record, JournalKey key, String domain, byte[] magic) {
        requireKey(key);
        if (record == null) {
            throw new IllegalArgumentException("record is required");
        }
        byte[] payload = canonicalContent(record);
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("record too large");
        }
        byte[] expectedHash = sha256(payload);
        byte[] suppliedHash = record.getRecordHash();
        if (suppliedHash != null && !JournalKey.equal(expectedHash, suppliedHash)) {
            throw new IllegalArgumentException("record hash does not match content");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 64);
            DataOutputStream data = new DataOutputStream(out);
            data.write(magic);
            data.writeInt(payload.length);
            data.write(payload);
            data.write(expectedHash);
            data.flush();
            byte[] framed = out.toByteArray();
            return concat(framed, key.hmac(domain, framed));
        } catch (IOException impossible) {
            throw new IllegalStateException("record encoding failed", impossible);
        }
    }

    private static SessionRecord decodeRecordInternal(byte[] bytes, JournalKey key, String domain) {
        requireKey(key);
        if (bytes == null || bytes.length < RECORD_MAGIC.length + 4 + SessionRecord.HASH_SIZE + 32) {
            throw new IllegalArgumentException("short record");
        }
        int macLength = 32;
        int framedLength = bytes.length - macLength;
        byte[] framed = Arrays.copyOfRange(bytes, 0, framedLength);
        byte[] mac = Arrays.copyOfRange(bytes, framedLength, bytes.length);
        if (!JournalKey.equal(key.hmac(domain, framed), mac)) {
            throw new IllegalArgumentException("record MAC mismatch");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(framed));
            byte[] magic = new byte[RECORD_MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, RECORD_MAGIC)) {
                throw new IllegalArgumentException("bad record magic");
            }
            int payloadLength = in.readInt();
            if (payloadLength < 1 || payloadLength > MAX_PAYLOAD_BYTES
                    || payloadLength != framed.length - RECORD_MAGIC.length - 4 - SessionRecord.HASH_SIZE) {
                throw new IllegalArgumentException("invalid record length");
            }
            byte[] payload = new byte[payloadLength];
            in.readFully(payload);
            byte[] recordHash = new byte[SessionRecord.HASH_SIZE];
            in.readFully(recordHash);
            if (in.available() != 0) {
                throw new IllegalArgumentException("record trailing bytes");
            }
            if (!JournalKey.equal(sha256(payload), recordHash)) {
                throw new IllegalArgumentException("record content hash mismatch");
            }
            SessionRecord record = parseContent(payload);
            if (!Arrays.equals(canonicalContent(record), payload)) {
                throw new IllegalArgumentException("non-canonical record");
            }
            return record.withRecordHash(recordHash);
        } catch (EOFException failure) {
            throw new IllegalArgumentException("truncated record", failure);
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid record", failure);
        }
    }

    private static byte[] canonicalContent(SessionRecord r) {
        validateRecordShape(r);
        return r.getSchemaVersion() == 1 ? canonicalV1Content(r) : canonicalV2Content(r);
    }

    private static byte[] canonicalV1Content(SessionRecord r) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);
            writeCommonContent(data, r);
            data.flush();
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("canonical encoding failed", impossible);
        }
    }

    private static byte[] canonicalV2Content(SessionRecord r) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);
            writeCommonContent(data, r);
            PomodoroPlan plan = r.getPomodoroPlan();
            data.writeBoolean(plan != null);
            if (plan != null) {
                data.writeLong(plan.getFocusDurationMs());
                data.writeLong(plan.getBreakDurationMs());
                data.writeInt(plan.getTotalSessions());
                data.writeInt(plan.getCurrentSession());
            }
            data.flush();
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("canonical encoding failed", impossible);
        }
    }

    private static void writeCommonContent(DataOutputStream data, SessionRecord r) throws IOException {
        data.writeInt(r.getSchemaVersion());
        writeString(data, r.getSessionId());
        data.writeLong(r.getSequence());
        byte[] previous = r.getPreviousRecordHash();
        data.writeBoolean(previous != null);
        if (previous != null) {
            data.write(previous);
        }
        data.writeInt(r.getState().ordinal());
        data.writeLong(r.getCreatedWallMs());
        data.writeLong(r.getCreatedElapsedMs());
        data.writeLong(r.getEndWallMs());
        data.writeLong(r.getDurationMs());
        data.writeLong(r.getRemainingMs());
        data.writeLong(r.getCheckpointWallMs());
        data.writeLong(r.getCheckpointElapsedMs());
        writeString(data, r.getBootHint());
        writeSet(data, r.getAllowed());
        writeSet(data, r.getBlocked());
        writeSet(data, r.getCriticalPackages());
        writeSet(data, r.getProtectedRoutes());
        writeString(data, r.getSigningCertSha256());
        data.writeInt(r.getCreatedVersionCode());
        writeString(data, r.getCreatedReason());
        writeString(data, r.getLastIntegrityReason());
    }

    private static SessionRecord parseContent(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int schema = in.readInt();
        String sessionId = readString(in);
        long sequence = in.readLong();
        byte[] previous = null;
        if (in.readBoolean()) {
            previous = new byte[SessionRecord.HASH_SIZE];
            in.readFully(previous);
        }
        int stateOrdinal = in.readInt();
        if (stateOrdinal < 0 || stateOrdinal >= SessionState.values().length) {
            throw new IllegalArgumentException("invalid state");
        }
        SessionState state = SessionState.values()[stateOrdinal];
        long createdWall = in.readLong();
        long createdElapsed = in.readLong();
        long endWall = in.readLong();
        long duration = in.readLong();
        long remaining = in.readLong();
        long checkpointWall = in.readLong();
        long checkpointElapsed = in.readLong();
        String bootHint = readString(in);
        Set<String> allowed = readSet(in);
        Set<String> blocked = readSet(in);
        Set<String> critical = readSet(in);
        Set<String> routes = readSet(in);
        String cert = readString(in);
        int version = in.readInt();
        String createdReason = readString(in);
        String integrityReason = readString(in);
        PomodoroPlan plan = null;
        if (schema >= 2 && in.readBoolean()) {
            plan = new PomodoroPlan(in.readLong(), in.readLong(), in.readInt(), in.readInt());
        }
        if (in.available() != 0) {
            throw new IllegalArgumentException("record content trailing bytes");
        }
        return new SessionRecord(schema, sessionId, sequence, previous, state, createdWall, createdElapsed,
                endWall, duration, remaining, checkpointWall, checkpointElapsed, bootHint, allowed, blocked,
                critical, routes, cert, version, createdReason, integrityReason, plan, null);
    }

    private static void validateRecordShape(SessionRecord r) {
        if (r.getSchemaVersion() != 1 && r.getSchemaVersion() != SessionRecord.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schema");
        }
        if (r.getSchemaVersion() == 1 && r.getPomodoroPlan() != null) {
            throw new IllegalArgumentException("schema v1 cannot carry a plan");
        }
        if (r.getSchemaVersion() == 1 && r.getState() == SessionState.BREAK) {
            throw new IllegalArgumentException("schema v1 cannot contain a break");
        }
        if (r.getSchemaVersion() == SessionRecord.CURRENT_SCHEMA_VERSION
                && r.getPomodoroPlan() == null
                && (r.getState() == SessionState.ARMING
                || r.getState() == SessionState.ACTIVE
                || r.getState() == SessionState.DEGRADED
                || r.getState() == SessionState.TAMPERED
                || r.getState() == SessionState.BREAK)) {
            throw new IllegalArgumentException("active plan record is missing its Pomodoro plan");
        }
        if (r.getSequence() < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (r.getSequence() == 1 && r.getPreviousRecordHash() != null) {
            throw new IllegalArgumentException("genesis cannot have previous hash");
        }
        if (r.getSequence() > 1 && r.getPreviousRecordHash() == null) {
            throw new IllegalArgumentException("non-genesis requires previous hash");
        }
    }

    private static void writeSet(DataOutputStream out, Set<String> values) throws IOException {
        if (values.size() > MAX_SET_ENTRIES) {
            throw new IllegalArgumentException("set too large");
        }
        out.writeInt(values.size());
        for (String value : values) {
            writeString(out, value);
        }
    }

    private static Set<String> readSet(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_SET_ENTRIES) {
            throw new IllegalArgumentException("invalid set size");
        }
        TreeSet<String> result = new TreeSet<>();
        String previous = null;
        for (int i = 0; i < count; i++) {
            String value = readString(in);
            if (previous != null && previous.compareTo(value) >= 0) {
                throw new IllegalArgumentException("set is not sorted and unique");
            }
            result.add(value);
            previous = value;
        }
        return result;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("string is required");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("string too large");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > in.available()) {
            throw new IllegalArgumentException("invalid string length");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            String value = chars.toString();
            if (value.isEmpty() && length != 0) {
                throw new IllegalArgumentException("invalid UTF-8");
            }
            return value;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("invalid UTF-8", failure);
        }
    }

    private static void writeInt(DataOutputStream out, int value) throws IOException {
        out.writeInt(value);
    }

    private static void requireKey(JournalKey key) {
        if (key == null || !key.isUsable()) {
            throw new IllegalStateException("journal HMAC key is unavailable");
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    public static final class Head {
        private final String sessionId;
        private final long sequence;
        private final byte[] recordHash;
        private final int slot;

        public Head(String sessionId, long sequence, byte[] recordHash, int slot) {
            if (sessionId == null || sessionId.isEmpty() || recordHash == null || recordHash.length != 32
                    || sequence < 1 || slot < -1 || slot > 1) {
                throw new IllegalArgumentException("invalid head");
            }
            this.sessionId = sessionId;
            this.sequence = sequence;
            this.recordHash = recordHash.clone();
            this.slot = slot;
        }

        public String getSessionId() { return sessionId; }
        public long getSequence() { return sequence; }
        public byte[] getRecordHash() { return recordHash.clone(); }
        public int getSlot() { return slot; }
    }
}
