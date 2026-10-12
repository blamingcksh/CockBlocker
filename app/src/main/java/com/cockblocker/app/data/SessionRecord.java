package com.cockblocker.app.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/** An authenticated journal record. All collection and hash accessors are defensive copies. */
public final class SessionRecord {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final int HASH_SIZE = 32;

    private final int schemaVersion;
    private final String sessionId;
    private final long sequence;
    private final byte[] previousRecordHash;
    private final SessionState state;
    private final long createdWallMs;
    private final long createdElapsedMs;
    private final long endWallMs;
    private final long durationMs;
    private final long remainingMs;
    private final long checkpointWallMs;
    private final long checkpointElapsedMs;
    private final String bootHint;
    private final Set<String> allowed;
    private final Set<String> blocked;
    private final Set<String> criticalPackages;
    private final Set<String> protectedRoutes;
    private final String signingCertSha256;
    private final int createdVersionCode;
    private final String createdReason;
    private final String lastIntegrityReason;
    private final PomodoroPlan pomodoroPlan;
    private final byte[] recordHash;

    public SessionRecord(
            int schemaVersion,
            String sessionId,
            long sequence,
            byte[] previousRecordHash,
            SessionState state,
            long createdWallMs,
            long createdElapsedMs,
            long endWallMs,
            long durationMs,
            long remainingMs,
            long checkpointWallMs,
            long checkpointElapsedMs,
            String bootHint,
            Set<String> allowed,
            Set<String> blocked,
            Set<String> criticalPackages,
            Set<String> protectedRoutes,
            String signingCertSha256,
            int createdVersionCode,
            String createdReason,
            String lastIntegrityReason,
            byte[] recordHash) {
        this(schemaVersion, sessionId, sequence, previousRecordHash, state, createdWallMs,
                createdElapsedMs, endWallMs, durationMs, remainingMs, checkpointWallMs,
                checkpointElapsedMs, bootHint, allowed, blocked, criticalPackages, protectedRoutes,
                signingCertSha256, createdVersionCode, createdReason, lastIntegrityReason,
                null, recordHash);
    }

    public SessionRecord(
            int schemaVersion,
            String sessionId,
            long sequence,
            byte[] previousRecordHash,
            SessionState state,
            long createdWallMs,
            long createdElapsedMs,
            long endWallMs,
            long durationMs,
            long remainingMs,
            long checkpointWallMs,
            long checkpointElapsedMs,
            String bootHint,
            Set<String> allowed,
            Set<String> blocked,
            Set<String> criticalPackages,
            Set<String> protectedRoutes,
            String signingCertSha256,
            int createdVersionCode,
            String createdReason,
            String lastIntegrityReason,
            PomodoroPlan pomodoroPlan,
            byte[] recordHash) {
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        if (schemaVersion != 1 && schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schema");
        }
        if (schemaVersion == 1 && pomodoroPlan != null) {
            throw new IllegalArgumentException("schema v1 cannot carry a Pomodoro plan");
        }
        this.schemaVersion = schemaVersion;
        this.sessionId = requireText(sessionId, "sessionId");
        this.sequence = sequence;
        this.previousRecordHash = copyHash(previousRecordHash, "previousRecordHash");
        this.state = state;
        this.createdWallMs = createdWallMs;
        this.createdElapsedMs = createdElapsedMs;
        this.endWallMs = endWallMs;
        this.durationMs = durationMs;
        this.remainingMs = remainingMs;
        this.checkpointWallMs = checkpointWallMs;
        this.checkpointElapsedMs = checkpointElapsedMs;
        this.bootHint = bootHint == null ? "" : bootHint;
        this.allowed = immutableSorted(allowed);
        this.blocked = immutableSorted(blocked);
        this.criticalPackages = immutableSorted(criticalPackages);
        this.protectedRoutes = immutableSorted(protectedRoutes);
        this.signingCertSha256 = signingCertSha256 == null ? "" : signingCertSha256;
        this.createdVersionCode = createdVersionCode;
        this.createdReason = createdReason == null ? "" : createdReason;
        this.lastIntegrityReason = lastIntegrityReason == null ? "" : lastIntegrityReason;
        this.pomodoroPlan = pomodoroPlan;
        this.recordHash = copyHash(recordHash, "recordHash");
    }

    public int getSchemaVersion() { return schemaVersion; }
    public String getSessionId() { return sessionId; }
    public long getSequence() { return sequence; }
    public byte[] getPreviousRecordHash() { return copyHash(previousRecordHash, "previousRecordHash"); }
    public SessionState getState() { return state; }
    public long getCreatedWallMs() { return createdWallMs; }
    public long getCreatedElapsedMs() { return createdElapsedMs; }
    public long getEndWallMs() { return endWallMs; }
    public long getDurationMs() { return durationMs; }
    public long getRemainingMs() { return remainingMs; }
    public long getCheckpointWallMs() { return checkpointWallMs; }
    public long getCheckpointElapsedMs() { return checkpointElapsedMs; }
    public String getBootHint() { return bootHint; }
    public Set<String> getAllowed() { return allowed; }
    public Set<String> getBlocked() { return blocked; }
    public Set<String> getCriticalPackages() { return criticalPackages; }
    public Set<String> getProtectedRoutes() { return protectedRoutes; }
    public String getSigningCertSha256() { return signingCertSha256; }
    public int getCreatedVersionCode() { return createdVersionCode; }
    public String getCreatedReason() { return createdReason; }
    public String getLastIntegrityReason() { return lastIntegrityReason; }
    public PomodoroPlan getPomodoroPlan() { return pomodoroPlan; }
    public byte[] getRecordHash() { return copyHash(recordHash, "recordHash"); }

    public byte[] previousRecordHash() { return getPreviousRecordHash(); }
    public byte[] recordHash() { return getRecordHash(); }
    public Set<String> allowed() { return allowed; }
    public Set<String> blocked() { return blocked; }
    public Set<String> criticalPackages() { return criticalPackages; }
    public Set<String> protectedRoutes() { return protectedRoutes; }

    public SessionRecord withState(SessionState next) {
        return copy(schemaVersion, next, getCreatedWallMs(), getCreatedElapsedMs(), getEndWallMs(),
                getDurationMs(), getRemainingMs(), getCheckpointWallMs(), getCheckpointElapsedMs(),
                getBootHint(), allowed, blocked, criticalPackages, protectedRoutes, signingCertSha256,
                createdVersionCode, createdReason, lastIntegrityReason, pomodoroPlan, null);
    }

    public SessionRecord withState(SessionState next, String reason) {
        return withState(next).withIntegrityReason(reason);
    }

    public SessionRecord withTiming(long endWall, long duration, long remaining,
                                    long checkpointWall, long checkpointElapsed) {
        return copy(schemaVersion, state, getCreatedWallMs(), getCreatedElapsedMs(), endWall, duration,
                remaining, checkpointWall, checkpointElapsed, getBootHint(), allowed, blocked,
                criticalPackages, protectedRoutes, signingCertSha256, createdVersionCode, createdReason,
                lastIntegrityReason, pomodoroPlan, null);
    }

    public SessionRecord withTiming(long endWall, long remaining, long checkpointWall, long checkpointElapsed) {
        return withTiming(endWall, getDurationMs(), remaining, checkpointWall, checkpointElapsed);
    }

    public SessionRecord withCriticalPackages(Set<String> next) {
        return copy(schemaVersion, state, getCreatedWallMs(), getCreatedElapsedMs(), getEndWallMs(),
                getDurationMs(), getRemainingMs(), getCheckpointWallMs(), getCheckpointElapsedMs(),
                getBootHint(), allowed, blocked, next, protectedRoutes, signingCertSha256,
                createdVersionCode, createdReason, lastIntegrityReason, pomodoroPlan, null);
    }

    public SessionRecord withCreatedReason(String reason) {
        return copy(schemaVersion, state, getCreatedWallMs(), getCreatedElapsedMs(), getEndWallMs(),
                getDurationMs(), getRemainingMs(), getCheckpointWallMs(), getCheckpointElapsedMs(),
                getBootHint(), allowed, blocked, criticalPackages, protectedRoutes, signingCertSha256,
                createdVersionCode, reason, lastIntegrityReason, pomodoroPlan, null);
    }

    public SessionRecord withIntegrityReason(String reason) {
        return copy(schemaVersion, state, getCreatedWallMs(), getCreatedElapsedMs(), getEndWallMs(),
                getDurationMs(), getRemainingMs(), getCheckpointWallMs(), getCheckpointElapsedMs(),
                getBootHint(), allowed, blocked, criticalPackages, protectedRoutes, signingCertSha256,
                createdVersionCode, createdReason, reason, pomodoroPlan, null);
    }

    public SessionRecord withPomodoroPlan(PomodoroPlan next) {
        return copy(CURRENT_SCHEMA_VERSION, state, getCreatedWallMs(), getCreatedElapsedMs(), getEndWallMs(),
                getDurationMs(), getRemainingMs(), getCheckpointWallMs(), getCheckpointElapsedMs(),
                getBootHint(), allowed, blocked, criticalPackages, protectedRoutes, signingCertSha256,
                createdVersionCode, createdReason, lastIntegrityReason, next, null);
    }

    public SessionRecord migrateToCurrent(PomodoroPlan plan) {
        if (schemaVersion == CURRENT_SCHEMA_VERSION) {
            return this;
        }
        if (recordHash == null) {
            throw new IllegalStateException("an authenticated record is required for migration");
        }
        return new SessionRecord(CURRENT_SCHEMA_VERSION, sessionId, sequence + 1L, getRecordHash(),
                state, getCreatedWallMs(), getCreatedElapsedMs(), getEndWallMs(), getDurationMs(),
                getRemainingMs(), getCheckpointWallMs(), getCheckpointElapsedMs(), getBootHint(),
                allowed, blocked, criticalPackages, protectedRoutes, signingCertSha256,
                createdVersionCode, createdReason, lastIntegrityReason, plan, null);
    }

    public SessionRecord withRecordHash(byte[] hash) {
        return copy(schemaVersion, state, getCreatedWallMs(), getCreatedElapsedMs(), getEndWallMs(),
                getDurationMs(), getRemainingMs(), getCheckpointWallMs(), getCheckpointElapsedMs(),
                getBootHint(), allowed, blocked, criticalPackages, protectedRoutes, signingCertSha256,
                createdVersionCode, createdReason, lastIntegrityReason, pomodoroPlan, hash);
    }

    public Set<String> effectiveBlocked() {
        Set<String> result = new TreeSet<>();
        result.addAll(blocked);
        result.removeAll(criticalPackages);
        return Collections.unmodifiableSet(result);
    }

    public boolean isEnforcing() {
        return state == SessionState.ACTIVE || state == SessionState.DEGRADED || state == SessionState.TAMPERED;
    }

    public boolean isBreak() {
        return state == SessionState.BREAK;
    }
    public boolean isActivePlan() {
        return isEnforcing() || isBreak();
    }
    public boolean isRecoveryLock() {
        return (state == SessionState.TAMPERED || isEnforcing())
                && (remainingMs > PomodoroPlan.minutesToMillis(PomodoroPlan.MAX_STRAIGHT_FOCUS_MINUTES)
                || "JOURNAL_UNAVAILABLE".equals(lastIntegrityReason));
    }

    public boolean hasPomodoroPlan() {
        return pomodoroPlan != null;
    }

    /** SHA-256 over the fixed-order canonical content, excluding the self hash. */
    public byte[] contentHash() {
        return SessionJournalCodec.contentHash(this);
    }

    private SessionRecord copy(int nextSchemaVersion, SessionState nextState, long cw, long ce, long ew,
                                long duration, long remaining, long cpw, long cpe, String boot,
                                Set<String> a, Set<String> b, Set<String> c, Set<String> routes,
                                String cert, int version, String created, String integrity,
                                PomodoroPlan plan, byte[] hash) {
        return new SessionRecord(nextSchemaVersion, sessionId, sequence, previousRecordHash, nextState,
                cw, ce, ew, duration, remaining, cpw, cpe, boot, a, b, c, routes, cert, version,
                created, integrity, plan, hash);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static byte[] copyHash(byte[] value, String name) {
        if (value == null) {
            return null;
        }
        if (value.length != HASH_SIZE) {
            throw new IllegalArgumentException(name + " must be 32 bytes");
        }
        return value.clone();
    }

    private static Set<String> immutableSorted(Set<String> source) {
        TreeSet<String> sorted = new TreeSet<>(Comparator.naturalOrder());
        if (source != null) {
            for (String value : source) {
                if (value == null || value.isEmpty()) {
                    throw new IllegalArgumentException("sets cannot contain null or empty values");
                }
                if (!sorted.add(value)) {
                    continue;
                }
            }
        }
        return Collections.unmodifiableSet(sorted);
    }
}
