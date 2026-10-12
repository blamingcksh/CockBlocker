package com.cockblocker.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import com.cockblocker.app.BuildConfig;
import com.cockblocker.app.R;
import com.cockblocker.app.data.SessionRecord;
import com.cockblocker.app.data.StateRepository;
import com.cockblocker.app.data.SessionState;
import com.cockblocker.app.integrity.IntegrityGuard;
import com.cockblocker.app.platform.AlarmScheduler;
import com.cockblocker.app.platform.OverlayController;
import com.cockblocker.app.platform.SafetySurfaceResolver;
import com.cockblocker.app.platform.UsageReader;
import com.cockblocker.app.trust.Check;
import com.cockblocker.app.trust.GateResult;
import com.cockblocker.app.trust.TrustAnchorGate;
import com.cockblocker.app.ui.MainActivity;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Visible foreground watchdog. It owns no authoritative session state. */
public final class SessionWatchdogService extends Service {
    public static final String ACTION_START = "com.cockblocker.app.action.WATCHDOG_START";
    public static final String ACTION_ACTIVATE = "com.cockblocker.app.action.WATCHDOG_ACTIVATE";
    public static final String ACTION_STOP = "com.cockblocker.app.action.WATCHDOG_STOP";
    public static final String ACTION_SHOW_BLOCKED_REDIRECT =
            "com.cockblocker.app.action.SHOW_BLOCKED_REDIRECT";
    public static final String ACTION_SHOW_RECOVERY =
            "com.cockblocker.app.action.SHOW_RECOVERY";
    public static final String EXTRA_PACKAGE_NAME = "com.cockblocker.app.extra.PACKAGE_NAME";
    public static final String EXTRA_REASON = "com.cockblocker.app.extra.REASON";
    public static final String EXTRA_EXPECTED_SESSION_ID =
            "com.cockblocker.app.extra.EXPECTED_SESSION_ID";
    public static final String EXTRA_EXPECTED_SEQUENCE =
            "com.cockblocker.app.extra.EXPECTED_SEQUENCE";
    public static final String CHANNEL_ID = "cockblocker_watchdog";

    private static final int NOTIFICATION_ID = 4107;
    private static final long HEALTH_INTERVAL_MS = 15_000L;
    private static final long CHECKPOINT_INTERVAL_MS = StateRepository.CHECKPOINT_INTERVAL_MS;
    private static final long TAMPERED_CHECKPOINT_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable healthLoop = new Runnable() {
        @Override
        public void run() {
            runHealthCycle();
            if (!isReleasedCleanupPending()) {
                handler.postDelayed(this, HEALTH_INTERVAL_MS);
            }
        }
    };

    private StateRepository repository;
    private AlarmScheduler alarms;
    private OverlayController overlay;
    private UsageReader usageReader;
    private NotificationManager notificationManager;
    private long lastCheckpointElapsedMs;
    private boolean loopScheduled;
    private boolean releasedCleanupPending;

    public static void start(Context context) {
        send(context, ACTION_START, true);
    }

    public static void startForActivation(Context context, String sessionId, long sequence) {
        if (sessionId == null || sessionId.isEmpty() || sequence < 1L) {
            throw new IllegalArgumentException("a valid expected session is required");
        }
        Intent intent = baseIntent(context, ACTION_ACTIVATE, true)
                .putExtra(EXTRA_EXPECTED_SESSION_ID, sessionId)
                .putExtra(EXTRA_EXPECTED_SEQUENCE, sequence);
        launch(context, intent, true);
    }

    /** Does not release or stop an enforcing session; the journal remains authoritative. */
    public static void stop(Context context) {
        send(context, ACTION_STOP, false);
    }

    public static void showBlockedRedirect(Context context, String packageName) {
        Intent intent = baseIntent(context, ACTION_SHOW_BLOCKED_REDIRECT, true);
        if (packageName != null) {
            intent.putExtra(EXTRA_PACKAGE_NAME, packageName);
        }
        launch(context, intent, true);
    }

    public static void showRecovery(Context context, String reason) {
        Intent intent = baseIntent(context, ACTION_SHOW_RECOVERY, true);
        if (reason != null) {
            intent.putExtra(EXTRA_REASON, reason);
        }
        launch(context, intent, true);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        repository = StateRepository.get(getApplicationContext());
        repository.initialize();
        alarms = new AlarmScheduler(this);
        overlay = new OverlayController(this);
        usageReader = new UsageReader(this);
        notificationManager = getSystemService(NotificationManager.class);
        ensureNotificationChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (!isInternalAction(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (intent == null) {
            // A sticky restart has no request payload; reconcile the journal instead of rejecting it.
            intent = baseIntent(this, ACTION_START, true);
        }
        if (ACTION_STOP.equals(action)) {
            SessionRecord record = safeReadWithRecovery();
            if (record == null) {
                ensureForeground();
                showUnavailableFallback();
                ensureHealthLoop();
                return START_STICKY;
            }
            if (record.getState() == SessionState.RELEASED
                    || record.getState() == SessionState.IDLE) {
                cleanupReleased();
                return START_NOT_STICKY;
            }
            if (record.getState() == SessionState.ARMING) {
                repository.abortArming(record.getSessionId(), record.getSequence(),
                        "Watchdog stopped before activation.");
                cleanupReleased();
                return START_NOT_STICKY;
            }
            ensureForeground();
            // A stop request never releases an enforcing session.
            runHealthCycle();
            return START_STICKY;
        }

        if (!ensureForeground()) {
            failClosedForForeground("Unable to establish the visible watchdog notification.");
            if (ACTION_ACTIVATE.equals(action)) {
                abortActivationIntent(intent);
            }
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_ACTIVATE.equals(action)) {
            activateExpectedSession(intent);
        }

        if (ACTION_SHOW_BLOCKED_REDIRECT.equals(action)) {
            String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
            SessionRecord record = safeRead();
            if (record != null && record.isEnforcing()) {
                overlay.setCriticalPackages(effectiveCriticalPackages(record));
                overlay.showBlockedRedirect(packageName);
            }
        } else if (ACTION_SHOW_RECOVERY.equals(action)) {
            String reason = intent.getStringExtra(EXTRA_REASON);
            overlay.showRecovery(reason == null ? "Cockblocker needs attention." : reason);
        }

        ensureHealthLoop();
        runHealthCycle();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(healthLoop);
        loopScheduled = false;
        // The overlay and notification may be required if Android restarts this service.
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void activateExpectedSession(Intent intent) {
        String expectedSessionId = intent.getStringExtra(EXTRA_EXPECTED_SESSION_ID);
        long expectedSequence = intent.getLongExtra(EXTRA_EXPECTED_SEQUENCE, -1L);
        SessionRecord current = safeRead();
        if (expectedSessionId == null || expectedSequence < 1L || current == null
                || current.getState() != SessionState.ARMING
                || !expectedSessionId.equals(current.getSessionId())
                || current.getSequence() != expectedSequence) {
            return;
        }

        SessionRecord activated;
        try {
            activated = repository.activate(expectedSessionId, expectedSequence);
        } catch (RuntimeException ignored) {
            abortFailedActivation(expectedSessionId, expectedSequence);
            return;
        }
        if (activated == null || activated.getState() != SessionState.ACTIVE
                || !expectedSessionId.equals(activated.getSessionId())
                || activated.getSequence() != expectedSequence) {
            abortFailedActivation(expectedSessionId, expectedSequence);
        }
    }

    private void abortActivationIntent(Intent intent) {
        String expectedSessionId = intent.getStringExtra(EXTRA_EXPECTED_SESSION_ID);
        long expectedSequence = intent.getLongExtra(EXTRA_EXPECTED_SEQUENCE, -1L);
        SessionRecord current = safeRead();
        if (expectedSessionId != null && expectedSequence >= 1L && current != null
                && current.getState() == SessionState.ARMING
                && expectedSessionId.equals(current.getSessionId())
                && current.getSequence() == expectedSequence) {
            abortFailedActivation(expectedSessionId, expectedSequence);
        }
    }

    private void abortFailedActivation(String sessionId, long sequence) {
        try {
            repository.abortArming(sessionId, sequence, "ACTIVATION_ABORTED");
            updateNotification(safeRead());
        } catch (RuntimeException ignored) {
            // Activation remains non-enforcing when journal reconciliation cannot complete.
        }
    }

    private void ensureHealthLoop() {
        if (!loopScheduled) {
            loopScheduled = true;
            handler.post(healthLoop);
        }
    }

    private void runHealthCycle() {
        SessionRecord record = safeReadWithRecovery();
        if (record == null) {
            return;
        }
        if (record.getState() == SessionState.RELEASED) {
            cleanupReleased();
            return;
        }

        if (record.getState() == SessionState.BREAK) {
            overlay.clearForBreak();
            try {
                Set<String> resolvedCritical = SafetySurfaceResolver.resolveCriticalPackages(this);
                if (resolvedCritical != null && !resolvedCritical.isEmpty()) {
                    repository.appendCriticalPackages(record.getSessionId(), record.getSequence(),
                            resolvedCritical);
                }
                repository.advanceBreakIfExpired();
            } catch (RuntimeException ignored) {
                showUnavailableFallback();
                return;
            }
            record = safeReadWithRecovery();
            if (record == null) {
                return;
            }
            if (record.getState() == SessionState.RELEASED) {
                cleanupReleased();
                return;
            }
            if (record.getState() == SessionState.BREAK) {
                updateNotification(record);
                reconcileAlarms(record);
                return;
            }
        }

        if (record.getState() == SessionState.ARMING && !healthGateOk(null)) {
            repository.abortArming(record.getSessionId(), record.getSequence(),
                    "Required capability is unavailable.");
            updateNotification(safeReadWithRecovery());
            return;
        }

        if (record.isEnforcing()) {
            SessionRecord expiryAttempt = evaluateExpiry();
            if (expiryAttempt != null && expiryAttempt.getState() == SessionState.RELEASED) {
                cleanupReleased();
                return;
            }
            if (expiryAttempt != null && expiryAttempt.getState() == SessionState.BREAK) {
                overlay.clearForBreak();
                record = safeReadWithRecovery();
                if (record == null) {
                    return;
                }
                if (record.getState() == SessionState.RELEASED) {
                    cleanupReleased();
                    return;
                }
                updateNotification(record);
                reconcileAlarms(record);
                return;
            }
            record = safeReadWithRecovery();
            if (record == null) {
                return;
            }
            if (record.getState() == SessionState.RELEASED) {
                cleanupReleased();
                return;
            }
            if (!healthGateOk(record)) {
                markTampered(record, healthFailureReason(record));
                return;
            }
        }

        record = safeReadWithRecovery();
        if (record == null) {
            return;
        }
        if (record.getState() == SessionState.RELEASED) {
            cleanupReleased();
            return;
        }

        if (record.isEnforcing()) {
            refreshSafetySurfaces(record);
            record = safeReadWithRecovery();
            if (record == null) {
                return;
            }
            if (record.getState() == SessionState.RELEASED) {
                cleanupReleased();
                return;
            }
            UsageReader.UsageObservation observation;
            try {
                observation = usageReader.latestForeground();
            } catch (SecurityException ignored) {
                return;
            } catch (RuntimeException ignored) {
                return;
            }
            if (observation != null && record.effectiveBlocked().contains(observation.getPackageName())
                    && !effectiveCriticalPackages(record).contains(observation.getPackageName())) {
                overlay.setCriticalPackages(effectiveCriticalPackages(record));
                overlay.showBlockedRedirect(observation.getPackageName());
            }
        }
        reconcileAlarms(record);
        updateNotification(record);
    }

    private boolean healthGateOk(SessionRecord record) {
        if (BuildConfig.DEBUG) {
            return false;
        }
        GateResult result = TrustAnchorGate.evaluateHealth(this);
        if (!result.ok) {
            return false;
        }
        return record == null || IntegrityGuard.verify(this, record);
    }

    private String healthFailureReason(SessionRecord record) {
        if (BuildConfig.DEBUG) {
            return "Debug builds are visible but never trusted for enforcement.";
        }
        GateResult result = TrustAnchorGate.evaluateHealth(this);
        if (!result.ok) {
            return result.summary;
        }
        if (record != null && !IntegrityGuard.verify(this, record)) {
            return "Application signing or build integrity validation failed.";
        }
        return "A required enforcement capability is unavailable.";
    }

    private void markTampered(SessionRecord record, String reason) {
        if (record == null || !record.isEnforcing()) {
            return;
        }
        SessionRecord changed = repository.markTampered(record.getSessionId(), record.getSequence(), reason);
        if (changed == null || changed.getState() == SessionState.RELEASED) {
            return;
        }
        GateResult result = TrustAnchorGate.evaluateHealth(this);
        Check failed = firstFailedCheck(result);
        Intent remediation = failed == null ? null : result.remediation(this, failed);
        overlay.setCriticalPackages(effectiveCriticalPackages(changed));
        overlay.showRecoveryBanner(reason, remediation);
    }

    private void failClosedForForeground(String reason) {
        SessionRecord record = safeReadWithRecovery();
        if (record != null && record.isEnforcing()) {
            repository.markTampered(record.getSessionId(), record.getSequence(), reason);
            overlay.showRecovery(reason);
        } else if (record == null) {
            showUnavailableFallback();
        }
        updateNotification(record);
    }

    private void refreshSafetySurfaces(SessionRecord record) {
        Set<String> resolvedCritical = SafetySurfaceResolver.resolveCriticalPackages(this);
        Set<String> resolvedRoutes = SafetySurfaceResolver.resolveProtectedRoutes(this);
        if (resolvedCritical == null || resolvedCritical.isEmpty()) {
            repository.markTampered(record.getSessionId(), record.getSequence(),
                    "A critical system safety package could not be resolved.");
            return;
        }
        if (resolvedRoutes == null) {
            repository.markTampered(record.getSessionId(), record.getSequence(),
                    "Protected Settings routes could not be resolved.");
        }
        // Resolver results are used immediately for exclusions. StateRepository remains
        // the sole authority for the signed critical snapshot and release.
        overlay.setCriticalPackages(effectiveCriticalPackages(record));
    }

    private void reconcileAlarms(SessionRecord record) {
        long now = SystemClock.elapsedRealtime();
        long interval = record.getState() == SessionState.TAMPERED
                ? TAMPERED_CHECKPOINT_MS : CHECKPOINT_INTERVAL_MS;
        boolean checkpointDue = lastCheckpointElapsedMs == 0L
                || now - lastCheckpointElapsedMs >= interval;
        if (checkpointDue) {
            SessionRecord checkpointed = repository.checkpoint();
            lastCheckpointElapsedMs = now;
            if (checkpointed != null) {
                record = checkpointed;
            }
            alarms.reconcileFromSignedState(record);
        } else {
            // Reconcile the fixed end wake-up without continually postponing checkpoint.
            alarms.scheduleEndWake(record);
        }
        updateNotification(record);
    }

    private SessionRecord evaluateExpiry() {
        try {
            return repository.evaluateLiveExpiry();
        } catch (RuntimeException ignored) {
            SessionRecord record = safeRead();
            if (record != null && record.isEnforcing()) {
                repository.markTampered(record.getSessionId(), record.getSequence(),
                        "Monotonic expiry validation failed.");
            }
            return safeRead();
        }
    }

    private SessionRecord safeRead() {
        try {
            return repository.read();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
    private SessionRecord safeReadWithRecovery() {
        SessionRecord record = safeRead();
        if (record != null) {
            return record;
        }
        try {
            repository.initialize();
        } catch (RuntimeException ignored) {
            // The fallback below remains fail-closed.
        }
        record = safeRead();
        if (record == null) {
            showUnavailableFallback();
        }
        return record;
    }

    private void showUnavailableFallback() {
        try {
            overlay.showDenyAllWithoutDeadline(
                    "Cockblocker could not authenticate the session. No early release is available.");
        } catch (RuntimeException ignored) {
            // The watchdog remains alive for the next recovery attempt.
        }
        updateNotification(null);
    }

    private Set<String> effectiveCriticalPackages(SessionRecord record) {
        return SafetySurfaceResolver.redirectableCriticalPackages(this,
                record == null ? null : record.getCriticalPackages());
    }

    private Check firstFailedCheck(GateResult result) {
        if (result == null) {
            return null;
        }
        for (Check check : result.checks) {
            if (!check.satisfied) {
                return check;
            }
        }
        return null;
    }

    private boolean ensureForeground() {
        Notification notification = buildNotification(safeRead());
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (RuntimeException failure) {
            if (Build.VERSION.SDK_INT < 29) {
                return false;
            }
            try {
                startForeground(NOTIFICATION_ID, notification);
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }

    private void updateNotification(SessionRecord record) {
        if (notificationManager == null) {
            return;
        }
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(record));
        } catch (RuntimeException ignored) {
            // Home remains the guaranteed health surface when notifications are suppressed.
        }
    }

    private Notification buildNotification(SessionRecord record) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        String remaining = "--:--";
        int resource = R.string.notification_idle;
        int accent = 0xFF7BA2F5;
        if (record != null) {
            remaining = formatRemaining(record.getRemainingMs());
            if (record.getState() == SessionState.BREAK) {
                resource = R.string.notification_break;
                accent = 0xFF65DB91;
            } else if (record.getState() == SessionState.ACTIVE) {
                resource = R.string.notification_active;
                accent = 0xFF7BA2F5;
            } else if (record.getState() == SessionState.DEGRADED) {
                resource = R.string.notification_degraded;
                accent = 0xFFF5B96B;
            } else if (record.getState() == SessionState.TAMPERED) {
                resource = R.string.notification_tampered;
                accent = 0xFFFF6B6B;
            } else if (record.getState() == SessionState.ARMING) {
                resource = R.string.notification_arming;
                accent = 0xFFF5B96B;
            }
        }
        String text = getString(resource);
        if (resource != R.string.notification_arming && resource != R.string.notification_idle) {
            text = String.format(text, remaining);
        }
        Intent open = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setColor(accent)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(android.app.PendingIntent.getActivity(this, 0, open,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                | android.app.PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(true);
        return builder.build();
    }

    public static void ensureNotificationChannel(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        try {
            manager.createNotificationChannel(channel);
        } catch (RuntimeException ignored) {
            // The health gate reports a disabled/missing notification capability.
        }
    }

    private void cleanupReleased() {
        if (releasedCleanupPending) {
            return;
        }
        releasedCleanupPending = true;
        handler.removeCallbacks(healthLoop);
        loopScheduled = false;
        alarms.cancelAllSessionAlarms();
        overlay.clearAfterReleased();
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (RuntimeException ignored) {
            // Idempotent cleanup continues even if the platform already removed it.
        }
        stopSelf();
    }

    private boolean isReleasedCleanupPending() {
        return releasedCleanupPending;
    }

    private static String formatRemaining(long remainingMs) {
        if (remainingMs <= 0L) {
            return "00:00";
        }
        long totalSeconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs);
        return String.format(java.util.Locale.US, "%02d:%02d",
                totalSeconds / 60L, totalSeconds % 60L);
    }

    private boolean isInternalAction(String action) {
        return ACTION_START.equals(action) || ACTION_ACTIVATE.equals(action)
                || ACTION_STOP.equals(action) || ACTION_SHOW_BLOCKED_REDIRECT.equals(action)
                || ACTION_SHOW_RECOVERY.equals(action);
    }

    private static Intent baseIntent(Context context, String action, boolean foregroundService) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        Intent intent = new Intent(context, SessionWatchdogService.class)
                .setAction(action)
                .setPackage(context.getPackageName());
        return intent;
    }

    private static void send(Context context, String action, boolean foregroundService) {
        launch(context, baseIntent(context, action, foregroundService), foregroundService);
    }

    private static void launch(Context context, Intent intent, boolean foregroundService) {
        if (foregroundService && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
