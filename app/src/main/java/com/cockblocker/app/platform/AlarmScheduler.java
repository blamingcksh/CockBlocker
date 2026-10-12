package com.cockblocker.app.platform;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import com.cockblocker.app.data.SessionRecord;
import com.cockblocker.app.data.StateRepository;
import com.cockblocker.app.data.SessionState;
import com.cockblocker.app.receiver.CheckpointReceiver;
import com.cockblocker.app.receiver.SessionEndReceiver;

/** Alarm facade. Alarm delivery is only a wake-up signal; signed state decides. */
public final class AlarmScheduler {
    public static final String ACTION_END = SessionEndReceiver.ACTION_END;
    public static final String ACTION_CHECKPOINT = CheckpointReceiver.ACTION_CHECKPOINT;
    private static final int END_REQUEST_CODE = 0x434F454E; // "COEN"
    private static final int CHECKPOINT_REQUEST_CODE = 0x434B5054; // "CKPT"

    private final Context context;
    private final AlarmManager alarmManager;

    public AlarmScheduler(Context context) {
        this.context = context.getApplicationContext();
        this.alarmManager = this.context.getSystemService(AlarmManager.class);
    }

    public boolean canScheduleExact() {
        if (alarmManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        try {
            return alarmManager.canScheduleExactAlarms();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Returns whether the fixed immutable end token currently exists. */
    public boolean hasFixedEndToken() {
        return fixedEndPendingIntent(false) != null;
    }

    public boolean scheduleEndWake(long triggerElapsedMs) {
        if (alarmManager == null || triggerElapsedMs <= 0L) {
            return false;
        }
        PendingIntent operation = fixedEndPendingIntent(true);
        if (operation == null) {
            return false;
        }
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsedMs, operation);
            } else {
                // A missing exact-alarm grant is reported by the health gate. The
                // inexact wake-up still gives the watchdog a chance to reconcile.
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsedMs, operation);
            }
            return true;
        } catch (SecurityException | IllegalArgumentException ignored) {
            return false;
        }
    }

    public boolean scheduleEndWake(SessionRecord record) {
        if (record == null || record.getRemainingMs() <= 0L) {
            return false;
        }
        return scheduleEndWake(record.getCheckpointElapsedMs() + record.getRemainingMs());
    }

    public boolean scheduleCheckpoint(long triggerElapsedMs) {
        if (alarmManager == null || triggerElapsedMs <= 0L) {
            return false;
        }
        PendingIntent operation = checkpointPendingIntent(true);
        if (operation == null) {
            return false;
        }
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsedMs, operation);
            } else {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsedMs, operation);
            }
            return true;
        } catch (SecurityException | IllegalArgumentException ignored) {
            return false;
        }
    }

    public boolean scheduleCheckpoint(SessionRecord record) {
        if (record == null || record.getRemainingMs() <= 0L) {
            return false;
        }
        return scheduleCheckpoint(SystemClock.elapsedRealtime() + StateRepository.CHECKPOINT_INTERVAL_MS);
    }

    /** Reconcile only from the authenticated record. RELEASED is the sole cancel signal. */
    public void reconcileFromSignedState(SessionRecord record) {
        if (record == null || record.getState() == SessionState.RELEASED
                || !record.isActivePlan()) {
            cancelAllSessionAlarms();
            return;
        }
        scheduleEndWake(record);
        if (record.getState() == SessionState.TAMPERED) {
            scheduleCheckpoint(SystemClock.elapsedRealtime() + 60_000L);
        } else {
            scheduleCheckpoint(record);
        }
    }

    public void cancelAllSessionAlarms() {
        if (alarmManager == null) {
            return;
        }
        PendingIntent end = fixedEndPendingIntent(false);
        if (end != null) {
            alarmManager.cancel(end);
        }
        PendingIntent checkpoint = checkpointPendingIntent(false);
        if (checkpoint != null) {
            alarmManager.cancel(checkpoint);
        }
    }

    /**
     * Fixed identity, explicit receiver, immutable token. No deadline or release
     * data is put in extras; callers must read and authenticate the journal.
     */
    public static PendingIntent fixedEndPendingIntent(Context context) {
        if (context == null) {
            return null;
        }
        Intent intent = new Intent(context, SessionEndReceiver.class)
                .setAction(SessionEndReceiver.ACTION_END)
                .setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(context, END_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent fixedEndPendingIntent(boolean create) {
        if (context == null) {
            return null;
        }
        Intent intent = new Intent(context, SessionEndReceiver.class)
                .setAction(SessionEndReceiver.ACTION_END)
                .setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_IMMUTABLE;
        flags |= create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE;
        try {
            return PendingIntent.getBroadcast(context, END_REQUEST_CODE, intent, flags);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private PendingIntent checkpointPendingIntent(boolean create) {
        Intent intent = new Intent(context, CheckpointReceiver.class)
                .setAction(CheckpointReceiver.ACTION_CHECKPOINT)
                .setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_IMMUTABLE;
        flags |= create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE;
        try {
            return PendingIntent.getBroadcast(context, CHECKPOINT_REQUEST_CODE, intent, flags);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
