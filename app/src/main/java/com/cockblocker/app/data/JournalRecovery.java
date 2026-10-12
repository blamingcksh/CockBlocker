package com.cockblocker.app.data;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Authenticates journal artifacts and repairs only the documented commit windows. */
public final class JournalRecovery {
    private JournalRecovery() { }

    public enum Status { EMPTY, HEALTHY, HEAD_REPAIRED, BOOTSTRAP_RECOVERED, TAMPERED }

    /** Result of one recovery pass. {@code slot} is the durable location of
     *  {@code record}: the next commit MUST alternate away from it so it is not
     *  overwritten before it becomes the chain predecessor. */
    public static class RecoveryResult {
        public final Status status;
        public final SessionRecord record;
        public final boolean headRepair;
        public final String reason;
        public final int slot;

        public RecoveryResult(Status status, SessionRecord record, boolean headRepair, String reason) {
            this(status, record, headRepair, reason, -1);
        }

        public RecoveryResult(Status status, SessionRecord record, boolean headRepair, String reason, int slot) {
            this.status = status;
            this.record = record;
            this.headRepair = headRepair;
            this.reason = reason == null ? "" : reason;
            this.slot = slot < -1 || slot > 1 ? -1 : slot;
        }

        public Status getStatus() { return status; }
        public SessionRecord getRecord() { return record; }
        public boolean isHeadRepair() { return headRepair; }
        public boolean wasHeadRepaired() { return headRepair; }
        public String getReason() { return reason; }
        public int getSlot() { return slot; }
    }


    public static RecoveryResult recover(Context context, JournalKey key) {
        return recover(new SessionJournalStorage(context), key);
    }

    public static RecoveryResult recover(SessionJournalStorage storage, JournalKey key) {
        if (storage == null || key == null || !key.isUsable()) {
            return new RecoveryResult(Status.TAMPERED, null, false, "journal key unavailable");
        }
        byte[] slotABytes = storage.readSlotA();
        byte[] slotBBytes = storage.readSlotB();
        byte[] headBytes = storage.readHead();
        byte[] bootstrapBytes = storage.readBootstrap();
        if (!storage.hasAnyArtifact()) {
            return new RecoveryResult(Status.EMPTY, null, false, "no journal artifacts");
        }

        Candidate a = decodeCandidate(slotABytes, key, false);
        Candidate b = decodeCandidate(slotBBytes, key, true);
        SessionJournalCodec.Head head = null;
        boolean headPresent = headBytes != null;
        if (headPresent) {
            try {
                head = SessionJournalCodec.decodeHead(headBytes, key);
            } catch (RuntimeException failure) {
                if (bootstrapBytes != null && a == null && b == null) {
                    RecoveryResult bootstrap = recoverBootstrap(storage, key, bootstrapBytes);
                    if (bootstrap != null) {
                        return bootstrap;
                    }
                }
                if (a == null && b == null) {
                    return new RecoveryResult(Status.TAMPERED, null, false,
                            "head authentication failed");
                }
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        if (a != null) {
            candidates.add(a);
        }
        if (b != null) {
            candidates.add(b);
        }
        if (headPresent && head != null) {
            Candidate referenced = findHead(candidates, head);
            if (referenced == null) {
            return new RecoveryResult(Status.TAMPERED, null, false, "head-referenced slot is invalid");
            }
        }

        if (candidates.isEmpty()) {
            RecoveryResult bootstrap = recoverBootstrap(storage, key, bootstrapBytes);
            if (bootstrap != null) {
                return bootstrap;
            }
            return new RecoveryResult(Status.TAMPERED, null, false, "no authenticated record");
        }

        Selection selection = selectChain(candidates, head);
        if (selection == null || selection.selected == null) {
            return new RecoveryResult(Status.TAMPERED, null, false, "record chain is ambiguous or broken");
        }
        Candidate selected = selection.selected;
        for (Candidate other : candidates) {
            if (other != selected && other.record.getSequence() == selected.record.getSequence()
                    && !sameHash(other.record, selected.record)) {
                // A valid head disambiguates a same-sequence collision. Without one, selection is null.
                if (head == null || !headMatches(head, selected.record)) {
                    return new RecoveryResult(Status.TAMPERED, null, false, "same-sequence collision");
                }
                storage.quarantine(other.slot ? SessionJournalStorage.SLOT_B : SessionJournalStorage.SLOT_A);
            }
        }
        if (head != null && !headMatches(head, selected.record)) {
            if (selected.record.getSequence() < head.getSequence()) {
                return new RecoveryResult(Status.TAMPERED, null, false, "head is ahead of valid chain");
            }
            storage.writeHead(selected.record, key, selected.slot ? 1 : 0);
            storage.quarantine(SessionJournalStorage.BOOTSTRAP);
            return new RecoveryResult(Status.HEAD_REPAIRED, selected.record, true, "promoted newer chained record", slotOf(selected));
        }
        if (head == null) {
            storage.writeHead(selected.record, key, selected.slot ? 1 : 0);
            storage.quarantine(SessionJournalStorage.BOOTSTRAP);
            return new RecoveryResult(Status.HEAD_REPAIRED, selected.record, true, "rebuilt missing commit head", slotOf(selected));
        }
        storage.quarantine(SessionJournalStorage.BOOTSTRAP);
        return new RecoveryResult(Status.HEALTHY, selected.record, false, "authenticated chain", slotOf(selected));
    }

    private static RecoveryResult recoverBootstrap(SessionJournalStorage storage, JournalKey key, byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            SessionRecord genesis = SessionJournalCodec.decodeBootstrap(bytes, key);
            if (genesis.getSequence() != 1 || genesis.getPreviousRecordHash() != null
                    || genesis.getState() != SessionState.IDLE
                    || genesis.getPomodoroPlan() != null
                    || !genesis.getAllowed().isEmpty() || !genesis.getBlocked().isEmpty()
                    || !genesis.getCriticalPackages().isEmpty() || !genesis.getProtectedRoutes().isEmpty()) {
                return null;
            }
            storage.writeSlot(0, genesis, key);
            storage.writeHead(genesis, key, 0);
            storage.quarantine(SessionJournalStorage.BOOTSTRAP);
            return new RecoveryResult(Status.BOOTSTRAP_RECOVERED, genesis, true, "reconstructed genesis from bootstrap", 0);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static Candidate decodeCandidate(byte[] bytes, JournalKey key, boolean slot) {
        if (bytes == null) {
            return null;
        }
        try {
            return new Candidate(slot, SessionJournalCodec.decodeRecord(bytes, key));
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static Candidate findHead(List<Candidate> candidates, SessionJournalCodec.Head head) {
        for (Candidate candidate : candidates) {
            if (headMatches(head, candidate.record) && head.getSlot() == (candidate.slot ? 1 : 0)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean headMatches(SessionJournalCodec.Head head, SessionRecord record) {
        return head.getSequence() == record.getSequence()
                && head.getSessionId().equals(record.getSessionId())
                && JournalKey.equal(head.getRecordHash(), record.getRecordHash());
    }

    private static boolean sameHash(SessionRecord left, SessionRecord right) {
        return JournalKey.equal(left.getRecordHash(), right.getRecordHash());
    }

    /**
     * Deterministic chain selection, ordered greatest-sequence-first.
     *
     * Without a head, the anchor is either the unambiguous genesis (sequence 1,
     * no predecessor) or, once a chain exists, the unambiguous tip: the
     * candidate whose {@code previous_record_hash} points at the other candidate's
     * record hash. That is the crash-after-slot-commit, crash-before-head-commit
     * window in the plan: both slots authenticate, one chains onto the other, and
     * only a head write is missing. Every stale head then points at the one valid
     * authentic slot, so repair rewrites the head at the tip's slot.
     *
     * Stays {@code null} (TAMPERED) for: same-sequence distinct hashes with no
     * head; a lone candidate with no chain partner; a candidate whose predecessor
     * is absent; a set that links from neither genesis nor a linked tip.
     */
    private static Selection selectChain(List<Candidate> candidates, SessionJournalCodec.Head head) {
        Candidate anchor = null;
        if (head != null) {
            anchor = findHead(candidates, head);
            if (anchor == null) {
                return null;
            }
        } else {
            for (Candidate candidate : candidates) {
                if (candidate.record.getSequence() == 1
                        && candidate.record.getPreviousRecordHash() == null) {
                    if (anchor != null && !sameHash(anchor.record, candidate.record)) {
                        return null;
                    }
                    anchor = candidate;
                }
            }
            if (anchor == null) {
                anchor = unambiguousChainTip(candidates);
                if (anchor == null) {
                    return null;
                }
            }
        }

        Candidate selected = anchor;
        for (Candidate other : candidates) {
            if (other == selected) {
                continue;
            }
            if (other.record.getSequence() == selected.record.getSequence()) {
                // Normal state commits retain the session sequence. A valid head disambiguates
                // the older same-sequence record; without one, distinct hashes remain ambiguous.
                if (head == null && !sameHash(other.record, selected.record)) {
                    return null;
                }
                continue;
            }
            if (other.record.getSequence() == selected.record.getSequence() + 1
                    && JournalKey.equal(other.record.getPreviousRecordHash(), selected.record.getRecordHash())) {
                selected = other;
                continue;
            }
            if (other.record.getSequence() + 1 == selected.record.getSequence()
                    && JournalKey.equal(selected.record.getPreviousRecordHash(), other.record.getRecordHash())) {
                continue;
            }
            return null;
        }
        return new Selection(selected);
    }

    /**
     * Head-less fallback anchor. Accepts only a tip that chains onto a sibling
     * candidate, so the successor's predecessor hash is the surviving record and
     * the surviving record's own link is still authentic; that pair is a complete
     * chain, not a guess. Rejects lone candidates, unlinked pairs, links whose
     * target is not present, and identical-hash candidates.
     */
    private static Candidate unambiguousChainTip(List<Candidate> candidates) {
        if (candidates.size() != 2) {
            return null;
        }
        Candidate first = candidates.get(0);
        Candidate second = candidates.get(1);
        if (first.record.getSequence() == second.record.getSequence()) {
            return null;
        }
        Candidate later = first.record.getSequence() > second.record.getSequence() ? first : second;
        Candidate earlier = later == first ? second : first;
        if (later.record.getSequence() != earlier.record.getSequence() + 1) {
            return null;
        }
        if (JournalKey.equal(later.record.getPreviousRecordHash(), earlier.record.getRecordHash())) {
            return later;
        }
        return null;
    }


    private static int slotOf(Candidate candidate) {
        return candidate == null ? -1 : (candidate.slot ? 1 : 0);
    }

    private static final class Candidate {
        final boolean slot;
        final SessionRecord record;
        Candidate(boolean slot, SessionRecord record) { this.slot = slot; this.record = record; }
    }

    private static final class Selection {
        final Candidate selected;
        Selection(Candidate selected) { this.selected = selected; }
    }
}
