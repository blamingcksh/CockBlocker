package com.cockblocker.app.data;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.UserManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.cockblocker.app.BuildConfig;
import com.cockblocker.app.integrity.IntegrityGuard;
import com.cockblocker.app.trust.Check;
import com.cockblocker.app.trust.GateResult;
import com.cockblocker.app.trust.TrustAnchorGate;

import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Process-wide serialized authority for the authenticated session journal. */
public final class StateRepository {
    public static final long CHECKPOINT_INTERVAL_MS = 60_000L;
    public static final long WALL_DISCONTINUITY_TOLERANCE_MS = 2_000L;
    public static final long MONOTONIC_REGRESSION_TOLERANCE_MS = 500L;
    private static final String TAG = "CockblockerJournal";

    private static volatile StateRepository instance;

    private final Context context;
    private final ReentrantLock lock = new ReentrantLock();
    private final String runtimeContinuityToken = UUID.randomUUID().toString();
    private final SessionJournalStorage storage;
    private JournalKey journalKey;
    private SessionRecord trustedSnapshot;
    private boolean initialized;
    private boolean journalValid;
    private int currentSlot = -1;
    // Highest journal sequence this process has ever observed. A recovered record at or
    // below it is a rollback of the whole journal (restored snapshot) and is refused;
    // it cannot be forged because the chain is HMAC-authenticated, so a lower sequence
    // can only come from stale on-disk state, never from a legitimate commit.
    private long highestObservedSequence = -1L;
    private boolean boundaryReconciled;
    private String lastFailureReason = "";
    private String lastFailureKind = "";
    private GateResult lastGateFailure;

    private StateRepository(Context context) {
        this.context = context.getApplicationContext() == null ? context : context.getApplicationContext();
        this.storage = new SessionJournalStorage(this.context);
    }

    public static StateRepository get(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        StateRepository result = instance;
        if (result == null) {
            synchronized (StateRepository.class) {
                result = instance;
                if (result == null) {
                    result = new StateRepository(context);
                    instance = result;
                }
            }
        }
        return result;
    }

    public boolean initialize() {
        lock.lock();
        try {
            return initializeLocked();
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord read() {
        lock.lock();
        try {
            if (!initialized && !initializeLocked()) {
                return trustedSnapshot;
            }
            return currentLocked();
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord start(Set<String> allowed, Set<String> blocked, Set<String> critical,
                               Set<String> routes, long durationMs) {
        return start(allowed, blocked, critical, routes, PomodoroPlan.legacy(durationMs));
    }

    public SessionRecord start(Set<String> allowed, Set<String> blocked, Set<String> critical,
                               Set<String> routes, PomodoroPlan plan) {
        lock.lock();
        try {
            SessionRecord current = currentLocked();
            if (current == null || !journalValid || plan == null
                    || (current.getState() != SessionState.IDLE
                    && current.getState() != SessionState.RELEASED)) {
                recordFailure("start", startRefusal(current, plan));
                return current;
            }
            GateResult gate = TrustAnchorGate.evaluateForStart(context);
            if (!gate.ok) {
                recordFailure("gate", describeGate(gate));
                lastGateFailure = gate;
                return current;
            }
            int createdVersion = versionCode();
            if (createdVersion <= 0) {
                recordFailure("version", "app version code unavailable");
                return current;
            }
            long nowWall = System.currentTimeMillis();
            long nowElapsed = SystemClock.elapsedRealtime();
            long durationMs = plan.getFocusDurationMs();
            SessionRecord next = new SessionRecord(
                    SessionRecord.CURRENT_SCHEMA_VERSION,
                    UUID.randomUUID().toString(),
                    current.getSequence() + 1,
                    current.getRecordHash(),
                    SessionState.ARMING,
                    nowWall,
                    nowElapsed,
                    nowWall + durationMs,
                    durationMs,
                    durationMs,
                    nowWall,
                    nowElapsed,
                    bootHint(),
                    allowed,
                    blocked,
                    critical,
                    routes,
                    IntegrityGuard.certificateDigest(context),
                    createdVersion,
                    "POMODORO_START",
                    "SETUP_REQUIRED",
                    plan,
                    null);
            return commitLocked(next.withRecordHash(next.contentHash()));
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord activate(String sessionId, long sequence) {
        lock.lock();
        try {
            SessionRecord current = currentLocked();
            if (!journalValid || !matches(current, sessionId, sequence) || current.getState() != SessionState.ARMING) {
                return current;
            }
            if (!IntegrityGuard.verify(context, current) || !TrustAnchorGate.evaluateForActivation(context).ok) {
                return abortArmingLocked(current, "ACTIVATION_ABORTED");
            }
            SessionRecord active = current.withState(SessionState.ACTIVE)
                    .withIntegrityReason("ACTIVE")
                    .withRecordHash(null);
            return commitLocked(active.withRecordHash(active.contentHash()));
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord abortArming(String sessionId, long sequence, String reason) {
        lock.lock();
        try {
            SessionRecord current = currentLocked();
            if (!journalValid || !matches(current, sessionId, sequence) || current.getState() != SessionState.ARMING) {
                return current;
            }
            return abortArmingLocked(current, reason == null ? "ACTIVATION_ABORTED" : reason);
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord abortArming(String sessionId, long sequence) {
        return abortArming(sessionId, sequence, "ACTIVATION_ABORTED");
    }

    public SessionRecord abortArming(String reason) {
        lock.lock();
        try {
            SessionRecord current = currentLocked();
            return journalValid && current != null && current.getState() == SessionState.ARMING
                    ? abortArmingLocked(current, reason) : current;
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord markDegraded(String sessionId, long sequence, String reason) {
        lock.lock();
        try {
            SessionRecord current = currentLocked();
            if (!journalValid || !matches(current, sessionId, sequence) || current == null) {
                return current;
            }
            if (current.getState() == SessionState.ACTIVE) {
                SessionRecord next = current.withState(SessionState.DEGRADED)
                        .withIntegrityReason(reason).withRecordHash(null);
                return commitLocked(next.withRecordHash(next.contentHash()));
            }
            if (current.getState() == SessionState.DEGRADED || current.getState() == SessionState.TAMPERED) {
                SessionRecord next = current.withIntegrityReason(appendReason(current.getLastIntegrityReason(), reason))
                        .withRecordHash(null);
                return commitLocked(next.withRecordHash(next.contentHash()));
            }
            return current;
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord appendCriticalPackages(String sessionId, long sequence, Set<String> additions) {
        lock.lock();
        try {
            SessionRecord current = currentLocked();
            if (!matches(current, sessionId, sequence) || !journalValid
                    || (!current.isEnforcing() && !current.isBreak())
                    || additions == null || additions.isEmpty()) {
                return current;
            }
            Set<String> merged = new HashSet<>(current.getCriticalPackages());
            for (String packageName : additions) {
                if (packageName == null || packageName.isEmpty()) {
                    return current;
                }
                merged.add(packageName);
            }
            if (merged.equals(current.getCriticalPackages())) {
                return current;
            }
            SessionRecord next = current.withCriticalPackages(merged).withRecordHash(null);
            return commitLocked(next.withRecordHash(next.contentHash()));
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord markDegraded(String reason) {
        SessionRecord current = read();
        return current == null ? null : markDegraded(current.getSessionId(), current.getSequence(), reason);
    }

    public SessionRecord markTampered(String sessionId, long sequence, String reason) {
        lock.lock();
        try {
            SessionRecord current = currentLocked();
            if (!journalValid || !matches(current, sessionId, sequence) || current == null) {
                return current;
            }
            if (current.getState() == SessionState.IDLE || current.getState() == SessionState.RELEASED) {
                return current;
            }
            SessionRecord next = current.withState(SessionState.TAMPERED)
                    .withIntegrityReason(appendReason(current.getLastIntegrityReason(), reason))
                    .withRecordHash(null);
            return commitLocked(next.withRecordHash(next.contentHash()));
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord markTampered(String reason) {
        SessionRecord current = read();
        return current == null ? null : markTampered(current.getSessionId(), current.getSequence(), reason);
    }

    public SessionRecord restoreIntegrity(String sessionId, long sequence) {
        lock.lock();
        try {
            SessionRecord current = currentLocked();
            if (!matches(current, sessionId, sequence) || current == null
                    || current.getState() != SessionState.TAMPERED || !journalValid
                    || !IntegrityGuard.verify(context, current)
                    || !TrustAnchorGate.evaluateForActivation(context).ok) {
                return current;
            }
            SessionRecord next = current.withState(SessionState.DEGRADED)
                    .withIntegrityReason("INTEGRITY_RESTORED").withRecordHash(null);
            return commitLocked(next.withRecordHash(next.contentHash()));
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord restoreIntegrity() {
        SessionRecord current = read();
        return current == null ? null : restoreIntegrity(current.getSessionId(), current.getSequence());
    }

    public SessionRecord restoreHealth(String sessionId, long sequence) {
        lock.lock();
        try {
            SessionRecord current = currentLocked();
            if (!matches(current, sessionId, sequence) || !journalValid
                    || current.getState() != SessionState.DEGRADED
                    || current.getSchemaVersion() != SessionRecord.CURRENT_SCHEMA_VERSION
                    || versionCode() < current.getCreatedVersionCode()
                    || !IntegrityGuard.verify(context, current)
                    || !TrustAnchorGate.evaluateForActivation(context).ok) {
                return current;
            }
            SessionRecord next = current.withState(SessionState.ACTIVE)
                    .withIntegrityReason("ACTIVE").withRecordHash(null);
            return commitLocked(next.withRecordHash(next.contentHash()));
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord restoreHealth() {
        SessionRecord current = read();
        return current == null ? null
                : restoreHealth(current.getSessionId(), current.getSequence());
    }

    public SessionRecord checkpoint() {
        lock.lock();
        try {
            SessionRecord current = currentLocked();
            if (current == null || !journalValid
                    || (!current.isEnforcing() && !current.isBreak())) {
                return current;
            }
            long nowWall = System.currentTimeMillis();
            long nowElapsed = SystemClock.elapsedRealtime();
            long elapsedDelta = nowElapsed - current.getCheckpointElapsedMs();
            long wallDelta = nowWall - current.getCheckpointWallMs();
            long remaining = current.getRemainingMs();
            if (elapsedDelta > 0) {
                remaining = remaining <= 0L ? remaining
                        : elapsedDelta >= remaining ? 0L : remaining - elapsedDelta;
            }
            SessionRecord checkpointed = current.withTiming(nowWall, current.getDurationMs(), remaining,
                    nowWall, nowElapsed).withRecordHash(null);
            if (elapsedDelta < -MONOTONIC_REGRESSION_TOLERANCE_MS) {
                return markTamperedLocked(checkpointed, "MONOTONIC_CLOCK_REGRESSION");
            }
            if (Math.abs(wallDelta - elapsedDelta) > WALL_DISCONTINUITY_TOLERANCE_MS) {
                return markTamperedLocked(checkpointed, "WALL_CLOCK_ANOMALY");
            }
            return commitLocked(checkpointed.withRecordHash(checkpointed.contentHash()));
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord evaluateLiveExpiry() {
        lock.lock();
        try {
            SessionRecord current = currentLocked();
            if (current == null || !journalValid || !current.isEnforcing()) {
                return current;
            }
            if (current.isRecoveryLock()) {
                return current;
            }
            long nowElapsed = SystemClock.elapsedRealtime();
            long elapsedDelta = nowElapsed - current.getCheckpointElapsedMs();
            if (elapsedDelta < 0) {
                if (elapsedDelta < -MONOTONIC_REGRESSION_TOLERANCE_MS) {
                    return markTamperedLocked(current, "MONOTONIC_CLOCK_REGRESSION");
                }
                long nowWall = System.currentTimeMillis();
                SessionRecord reanchored = current.withTiming(nowWall, current.getDurationMs(),
                        current.getRemainingMs(), nowWall, nowElapsed).withRecordHash(null);
                return commitLocked(reanchored.withRecordHash(reanchored.contentHash()));
            }
            if (!IntegrityGuard.verify(context, current) || !TrustAnchorGate.evaluateForActivation(context).ok) {
                return markTamperedLocked(current, "RELEASE_INTEGRITY_FAILED");
            }
            long remaining = Math.max(0L, current.getRemainingMs() - elapsedDelta);
            if (remaining != 0L) {
                return current;
            }
            // This read is about to end the session, so it is the one place a forged
            // monotonic clock buys a release. The checkpoint path above already refuses a
            // monotonic delta the wall clock does not corroborate; the release path did
            // not, so hooking SystemClock.elapsedRealtime() forward in-process produced a
            // clean signed RELEASED record without any capability or signature ever being
            // touched. Cross-check identically, and fail toward TAMPERED - still enforcing,
            // per SessionRecord.isEnforcing - never toward release. This raises the cost
            // from one hooked clock to every clock the app reads; it is not a certainty.
            long nowWall = System.currentTimeMillis();
            long wallDelta = nowWall - current.getCheckpointWallMs();
            if (Math.abs(wallDelta - elapsedDelta) > WALL_DISCONTINUITY_TOLERANCE_MS) {
                return markTamperedLocked(current, "RELEASE_CLOCK_ANOMALY");
            }
            PomodoroPlan plan = current.getPomodoroPlan();
            if (plan != null && !plan.isFinalFocus()) {
                long breakDuration = plan.getBreakDurationMs();
                SessionRecord paused = current.withState(SessionState.BREAK)
                        .withTiming(nowWall + breakDuration, breakDuration, breakDuration,
                                nowWall, nowElapsed)
                        .withIntegrityReason("POMODORO_BREAK")
                        .withRecordHash(null);
                return commitLocked(paused.withRecordHash(paused.contentHash()));
            }
            SessionRecord released = current.withState(SessionState.RELEASED)
                    .withTiming(nowWall, current.getDurationMs(), 0L, nowWall, nowElapsed)
                    .withIntegrityReason("LIVE_EXPIRY").withRecordHash(null);
            return commitLocked(released.withRecordHash(released.contentHash()));
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord advanceBreakIfExpired() {
        lock.lock();
        try {
            SessionRecord current = currentLocked();
            PomodoroPlan plan = current == null ? null : current.getPomodoroPlan();
            if (current == null || !journalValid || current.getState() != SessionState.BREAK
                    || plan == null || plan.isFinalFocus()) {
                return current;
            }
            long nowElapsed = SystemClock.elapsedRealtime();
            long elapsedDelta = nowElapsed - current.getCheckpointElapsedMs();
            if (elapsedDelta < -MONOTONIC_REGRESSION_TOLERANCE_MS) {
                return markTamperedLocked(current, "MONOTONIC_CLOCK_REGRESSION");
            }
            long remaining = Math.max(0L, current.getRemainingMs() - Math.max(0L, elapsedDelta));
            if (remaining != 0L) {
                return current;
            }
            if (!IntegrityGuard.verify(context, current)) {
                return markTamperedLocked(current, "POMODORO_INTEGRITY_FAILED");
            }
            if (!TrustAnchorGate.evaluateForActivation(context).ok) {
                // A break is not an enforcing state, so returning here leaves the redirect
                // layer inert, and evaluateLiveExpiry never runs on a BREAK record to bring
                // it back. The session would sit in a permanently non-enforcing state with
                // every blocked app reachable and no way out. Fail toward enforcement, exactly
                // as the integrity check above does. It settles: once TAMPERED, the gate is
                // re-checked in evaluateLiveExpiry before any plan decision, so the record
                // holds here until the capability is restored and the break can end properly.
                return markTamperedLocked(current, "POMODORO_TRUST_ANCHOR_MISSING");
            }
            PomodoroPlan nextPlan = plan.nextFocus();
            long nowWall = System.currentTimeMillis();
            long focusDuration = nextPlan.getFocusDurationMs();
            SessionRecord active = current.withPomodoroPlan(nextPlan)
                    .withState(SessionState.ACTIVE)
                    .withTiming(nowWall + focusDuration, focusDuration, focusDuration,
                            nowWall, nowElapsed)
                    .withCreatedReason("POMODORO_CONTINUE")
                    .withIntegrityReason("POMODORO_FOCUS_START")
                    .withRecordHash(null);
            return commitLocked(active.withRecordHash(active.contentHash()));
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord reconcileAfterBoundary() {
        lock.lock();
        try {
            if (boundaryReconciled) {
                return trustedSnapshot;
            }
            boundaryReconciled = true;
            SessionRecord current = currentLocked();
            if (current == null || !journalValid || current.getState() == SessionState.RELEASED
                    || current.getState() == SessionState.IDLE) {
                return current;
            }
            if (current.getState() == SessionState.ARMING) {
                return abortArmingLocked(current, "ACTIVATION_ABORTED");
            }
            long nowWall = System.currentTimeMillis();
            long nowElapsed = SystemClock.elapsedRealtime();
            if (current.getState() == SessionState.BREAK) {
                SessionRecord reanchored = current.withTiming(nowWall, current.getDurationMs(),
                        current.getRemainingMs(), nowWall, nowElapsed).withRecordHash(null);
                return commitLocked(reanchored.withRecordHash(reanchored.contentHash()));
            }
            SessionRecord next = current.withState(current.getState() == SessionState.ACTIVE
                            ? SessionState.DEGRADED : current.getState())
                    .withTiming(nowWall, current.getDurationMs(), current.getRemainingMs(), nowWall, nowElapsed)
                    .withIntegrityReason(current.getLastIntegrityReason()).withRecordHash(null);
            return commitLocked(next.withRecordHash(next.contentHash()));
        } finally {
            lock.unlock();
        }
    }

    public boolean hasTrustedSnapshot() {
        lock.lock();
        try {
            return trustedSnapshot != null;
        } finally {
            lock.unlock();
        }
    }

    public SessionRecord lastSnapshot() {
        lock.lock();
        try {
            return trustedSnapshot;
        } finally {
            lock.unlock();
        }
    }

    public Context getContext() { return context; }

    /**
     * Truthful cause of the most recent refused operation, or {@code null} when the
     * last operation succeeded. Failures that the caller cannot remediate name the
     * journal condition; gate failures are exposed via {@link #getLastGateFailure()}
     * so the UI can reuse its setup-check formatting.
     */
    public String getLastFailureReason() {
        lock.lock();
        try {
            return lastFailureReason.isEmpty() ? null : lastFailureReason;
        } finally {
            lock.unlock();
        }
    }

    public GateResult getLastGateFailure() {
        lock.lock();
        try {
            return lastGateFailure;
        } finally {
            lock.unlock();
        }
    }

    /** Clears the recorded failure after a successful start so a later toast is never stale. */
    public void clearLastFailure() {
        lock.lock();
        try {
            lastFailureReason = "";
            lastFailureKind = "";
            lastGateFailure = null;
        } finally {
            lock.unlock();
        }
    }

    private void recordFailure(String kind, String reason) {
        lastFailureKind = kind == null ? "" : kind;
        lastFailureReason = reason == null ? "" : reason;
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "start refused [" + lastFailureKind + "]: " + lastFailureReason);
        }
    }

    private String journalFailureReason(SessionRecord current) {
        if (!journalValid) {
            if (!isJournalKeyUsable()) {
                return "session key unavailable. Reinstalling the app resets local session state.";
            }
            return "signed journal is unavailable. Reinstalling the app resets local session state.";
        }
        if (current == null) {
            return "signed journal is unavailable. Reinstalling the app resets local session state.";
        }
        return null;
    }

    private boolean isJournalKeyUsable() {
        return journalKey != null && journalKey.isUsable();
    }

    /**
     * Which precondition refused the start, in the same order {@link #start} tests
     * them, so the reported cause matches the check that actually fired.
     */
    private String startRefusal(SessionRecord current, PomodoroPlan plan) {
        String journal = journalFailureReason(current);
        if (journal != null) {
            return journal;
        }
        if (plan == null) {
            return "no focus plan is selected";
        }
        return "a session is already in progress";
    }

    /** First unsatisfied check plus its reason; mirrors the UI's own failure wording. */
    private static String describeGate(GateResult gate) {
        if (gate == null) {
            return "setup checks are unavailable on this device";
        }
        for (Check check : gate.getChecks()) {
            if (!check.isSatisfied()) {
                return check.getLabel() + " — " + check.getReason();
            }
        }
        return gate.getSummary();
    }

    private boolean initializeLocked() {
        if (initialized) {
            return journalValid || trustedSnapshot != null;
        }
        journalKey = JournalKey.acquire(context);
        if (journalKey == null || !journalKey.isUsable()) {
            trustedSnapshot = createDenyAllSnapshot();
            journalValid = false;
            boundaryReconciled = true;
            return false;
        }
        JournalRecovery.RecoveryResult recovered = JournalRecovery.recover(storage, journalKey);
        logRecovery("initialize", recovered);
        if (recovered.status == JournalRecovery.Status.TAMPERED) {
            trustedSnapshot = createDenyAllSnapshot();
            journalValid = false;
            initialized = true;
            boundaryReconciled = true;
            return false;
        }
        if (recovered.status == JournalRecovery.Status.EMPTY) {
            if (!isUserUnlocked()) {
                trustedSnapshot = createDenyAllSnapshot();
                journalValid = false;
                boundaryReconciled = true;
                return false;
            }
            boundaryReconciled = true;
            SessionRecord genesis = createGenesis();
            try {
                storage.writeBootstrap(genesis, journalKey);
                storage.writeSlot(0, genesis, journalKey);
                storage.writeHead(genesis, journalKey, 0);
                trustedSnapshot = genesis;
                currentSlot = 0;
                journalValid = true;
                initialized = true;
                return true;
            } catch (RuntimeException failure) {
                journalValid = false;
                initialized = true;
                return false;
            }
        }
        trustedSnapshot = recovered.record;
        currentSlot = recovered.getSlot() >= 0 ? recovered.getSlot() : readHeadSlot();
        journalValid = true;
        initialized = true;
        boundaryReconciled = true;
        if (trustedSnapshot != null && trustedSnapshot.getSchemaVersion() == 1) {
            try {
                PomodoroPlan plan = trustedSnapshot.getState() == SessionState.ARMING
                        || trustedSnapshot.isEnforcing()
                        ? PomodoroPlan.legacy(trustedSnapshot.getDurationMs()) : null;
                SessionRecord migrated = trustedSnapshot.migrateToCurrent(plan);
                trustedSnapshot = commitLocked(migrated.withRecordHash(migrated.contentHash()));
            } catch (RuntimeException failure) {
                trustedSnapshot = createDenyAllSnapshot();
                journalValid = false;
                initialized = false;
                return false;
            }
        }
        if (trustedSnapshot != null && trustedSnapshot.getState() == SessionState.ARMING) {
            trustedSnapshot = abortArmingLocked(trustedSnapshot, "ACTIVATION_ABORTED");
        } else if (trustedSnapshot != null && trustedSnapshot.getState() == SessionState.BREAK) {
            long nowWall = System.currentTimeMillis();
            long nowElapsed = SystemClock.elapsedRealtime();
            SessionRecord reanchored = trustedSnapshot.withTiming(nowWall,
                    trustedSnapshot.getDurationMs(), trustedSnapshot.getRemainingMs(),
                    nowWall, nowElapsed).withRecordHash(null);
            trustedSnapshot = commitLocked(reanchored.withRecordHash(reanchored.contentHash()));
        } else if (trustedSnapshot != null && trustedSnapshot.isEnforcing()) {
            long nowWall = System.currentTimeMillis();
            long nowElapsed = SystemClock.elapsedRealtime();
            SessionRecord reanchored = trustedSnapshot.withState(
                            trustedSnapshot.getState() == SessionState.ACTIVE
                                    ? SessionState.DEGRADED : trustedSnapshot.getState())
                    .withTiming(nowWall, trustedSnapshot.getDurationMs(), trustedSnapshot.getRemainingMs(),
                            nowWall, nowElapsed).withRecordHash(null);
            trustedSnapshot = commitLocked(reanchored.withRecordHash(reanchored.contentHash()));
        }
        return true;
    }

    private SessionRecord currentLocked() {
        if (!initialized && !initializeLocked()) {
            return trustedSnapshot;
        }
        JournalRecovery.RecoveryResult recovered = JournalRecovery.recover(storage, journalKey);
        if (recovered.status == JournalRecovery.Status.TAMPERED) {
            logRecovery("read", recovered);
            if (trustedSnapshot == null) {
                trustedSnapshot = createDenyAllSnapshot();
            }
            journalValid = false;
            return trustedSnapshot;
        }
        if (recovered.status == JournalRecovery.Status.EMPTY && trustedSnapshot == null) {
            logRecovery("read", recovered);
            trustedSnapshot = createDenyAllSnapshot();
            journalValid = false;
            return trustedSnapshot;
        }
        logRecovery("read", recovered);
        journalValid = recovered.record != null;
        if (recovered.record != null) {
            SessionRecord adopted = recovered.record;
            if (adopted.getSequence() < highestObservedSequence) {
                // The on-disk chain moved strictly backwards within this process: the
                // journal was replaced wholesale from an earlier snapshot. Re-reading the
                // current tip returns the SAME sequence, which is not a rollback, so the
                // comparison is strict. Nothing legitimate produces a lower sequence than
                // one already adopted, so refuse it and fail closed rather than adopting a
                // record that would un-earn session time or drop an ACTIVE session to
                // IDLE/RELEASED.
                logRecovery("rollback-detected", recovered);
                trustedSnapshot = createDenyAllSnapshot();
                journalValid = false;
                return trustedSnapshot;
            }
            if (adopted.getSequence() > highestObservedSequence) {
                highestObservedSequence = adopted.getSequence();
            }
            if (adopted.isEnforcing() && wasRestoredBackwards(adopted)) {
                // The record was sealed in an earlier boot than the live one, or the wall
                // clock has moved backwards since it was checkpointed. Either way its
                // timing fields cannot be trusted to have aged honestly. Fail closed.
                logRecovery("time-rollback-detected", recovered);
                trustedSnapshot = createDenyAllSnapshot();
                journalValid = false;
                return trustedSnapshot;
            }
            trustedSnapshot = adopted;
            // Prefer the slot the authenticated chain actually occupies: decoding the
            // head can fail (-1) while both slots are authentic, and the next commit
            // alternates from currentSlot, so -1 would clobber the surviving chain.
            currentSlot = recovered.getSlot() >= 0 ? recovered.getSlot() : readHeadSlot();
        }
        return trustedSnapshot;
    }

    /** Journal health as observed by the last read, for on-device diagnosis. */
    private void logRecovery(String operation, JournalRecovery.RecoveryResult recovered) {
        if (recovered == null) {
            return;
        }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, operation + ": status=" + recovered.status
                    + " reason=" + recovered.reason
                    + " slot=" + recovered.getSlot()
                    + " headSlot=" + readHeadSlot()
                    + " sequence=" + (recovered.record == null ? -1 : recovered.record.getSequence())
                    + " state=" + (recovered.record == null ? "none" : recovered.record.getState()));
        }
    }

    private SessionRecord commitLocked(SessionRecord unsigned) {
        if (!journalValid || unsigned == null || journalKey == null || !journalKey.isUsable()) {
            return trustedSnapshot;
        }
        SessionRecord record = unsigned.getRecordHash() == null
                ? unsigned.withRecordHash(unsigned.contentHash()) : unsigned;
        int slot = currentSlot < 0 ? 0 : (currentSlot == 0 ? 1 : 0);
        storage.writeSlot(slot, SessionJournalCodec.encodeRecord(record, journalKey));
        storage.writeHead(record, journalKey, slot);
        storage.quarantine(SessionJournalStorage.BOOTSTRAP);
        currentSlot = slot;
        if (record.getSequence() > highestObservedSequence) {
            highestObservedSequence = record.getSequence();
        }
        trustedSnapshot = record;
        journalValid = true;
        lastFailureReason = "";
        lastFailureKind = "";
        lastGateFailure = null;
        return record;
    }

    private boolean isUserUnlocked() {
        try {
            UserManager manager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            return manager == null || manager.isUserUnlocked();
        } catch (RuntimeException ignored) {
            return false;
        }
    }


    private SessionRecord abortArmingLocked(SessionRecord current, String reason) {
        if (current == null || current.getState() != SessionState.ARMING) {
            return current;
        }
        SessionRecord next = current.withState(SessionState.IDLE)
                .withCreatedReason("ACTIVATION_ABORTED")
                .withIntegrityReason(reason == null ? "ACTIVATION_ABORTED" : reason)
                .withRecordHash(null);
        return commitLocked(next.withRecordHash(next.contentHash()));
    }

    private SessionRecord markTamperedLocked(SessionRecord current, String reason) {
        if (current == null || current.getState() == SessionState.IDLE || current.getState() == SessionState.RELEASED) {
            return current;
        }
        SessionRecord next = current.withState(SessionState.TAMPERED)
                .withIntegrityReason(appendReason(current.getLastIntegrityReason(), reason))
                .withRecordHash(null);
        return commitLocked(next.withRecordHash(next.contentHash()));
    }

    private SessionRecord createDenyAllSnapshot() {
        Set<String> blocked = new HashSet<>();
        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = context.getPackageManager().queryIntentActivities(
                    launcher, PackageManager.MATCH_ALL);
            if (resolved != null) {
                for (ResolveInfo info : resolved) {
                    if (info.activityInfo != null && info.activityInfo.packageName != null) {
                        blocked.add(info.activityInfo.packageName);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // The signed in-memory record still fails closed if package visibility is unavailable.
        }
        Set<String> critical = new HashSet<>();
        critical.add(context.getPackageName());
        critical.add("android");
        long nowWall = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        long duration = Long.MAX_VALUE / 4L;
        return new SessionRecord(SessionRecord.CURRENT_SCHEMA_VERSION,
                "deny-all-" + UUID.randomUUID(), 1L, null, SessionState.TAMPERED,
                nowWall, nowElapsed, nowWall + duration, duration, duration,
                nowWall, nowElapsed, "", Collections.<String>emptySet(), blocked, critical, Collections.<String>emptySet(),
                IntegrityGuard.certificateDigest(context), versionCode(), "JOURNAL_RECOVERY",
                "JOURNAL_UNAVAILABLE", PomodoroPlan.initial(90, 1, 1), null);
    }

    private SessionRecord createGenesis() {
        long nowWall = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        SessionRecord genesis = new SessionRecord(SessionRecord.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(), 1, null, SessionState.IDLE, nowWall, nowElapsed, 0L, 0L, 0L,
                nowWall, nowElapsed, bootHint(), Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(), IntegrityGuard.certificateDigest(context),
                versionCode(), "GENESIS", "GENESIS", null);
        return genesis.withRecordHash(genesis.contentHash());
    }

    private int versionCode() {
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            }
            return info.versionCode;
        } catch (Exception failure) {
            return -1;
        }
    }

    /**
     * True when a recovered enforcing record's own timing fields are internally
     * inconsistent with "now", which means the journal was rolled back (restored from a
     * prior snapshot) or wall time moved backwards. The stored remaining time can no
     * longer be trusted, so the caller must fail closed. A small tolerance absorbs
     * ordinary scheduling jitter between the checkpoint and this read.
     */
    private boolean wasRestoredBackwards(SessionRecord record) {
        long nowWall = System.currentTimeMillis();
        long wallSinceCheckpoint = nowWall - record.getCheckpointWallMs();
        // The record was sealed in an EARLIER boot than the live one. A restore from a
        // pre-reboot snapshot carries the old (lower) BOOT_COUNT, so the rollback shows
        // up as storedBoot < liveBoot, not storedBoot > liveBoot. This catches a snapshot
        // taken before a reboot and restored after it, even across a same-value wall clock.
        String storedBoot = record.getBootHint();
        String liveBoot = bootHint();
        if (storedBoot != null && !storedBoot.isEmpty() && !liveBoot.isEmpty()
                && !storedBoot.equals(liveBoot)) {
            try {
                if (Long.parseLong(storedBoot) < Long.parseLong(liveBoot)) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // A non-numeric hint carries no ordering; fall through to the wall check.
            }
        }
        // Wall time has gone backwards past tolerance: the clock was wound back.
        if (wallSinceCheckpoint < -WALL_DISCONTINUITY_TOLERANCE_MS) {
            return true;
        }
        // Deliberately NOT checked here: "more wall time has elapsed since the checkpoint
        // than the record's remaining budget can absorb". Checkpoints are 60s apart and the
        // watchdog reads every 15s, so a session with under ~75s left that is read between
        // two checkpoints would trip it while expiring perfectly normally - and the response
        // is a permanent deny-all the owner cannot clear without wiping app data. The
        // residual case it would have covered (a same-boot restore that regains time) is
        // not defensible in-app anyway: any purely on-disk monotonic anchor is restored
        // alongside the files it is meant to guard, so there is nothing to compare against.
        return false;
    }

    private String bootHint() {
        try {
            String value = Settings.Global.getString(context.getContentResolver(), Settings.Global.BOOT_COUNT);
            return value == null ? "" : value;
        } catch (RuntimeException failure) {
            return "";
        }
    }

    private int readHeadSlot() {
        try {
            byte[] bytes = storage.readHead();
            return bytes == null ? -1 : SessionJournalCodec.decodeHead(bytes, journalKey).getSlot();
        } catch (RuntimeException failure) {
            return -1;
        }
    }

    private static boolean matches(SessionRecord record, String sessionId, long sequence) {
        return record != null && record.getSessionId().equals(sessionId) && record.getSequence() == sequence;
    }

    private static String appendReason(String existing, String addition) {
        if (addition == null || addition.isEmpty()) {
            return existing == null ? "" : existing;
        }
        if (existing == null || existing.isEmpty()) {
            return addition;
        }
        return existing.contains(addition) ? existing : existing + ";" + addition;
    }
}
