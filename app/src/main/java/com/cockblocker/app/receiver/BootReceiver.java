package com.cockblocker.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.cockblocker.app.data.SessionRecord;
import com.cockblocker.app.data.StateRepository;
import com.cockblocker.app.service.SessionWatchdogService;

/** Reconciles signed state after boot or package replacement. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        StateRepository repository = StateRepository.get(appContext);
        try {
            if (!repository.initialize()) {
                reconcileWatchdog(appContext, repository.lastSnapshot());
                return;
            }
            reconcileWatchdog(appContext, repository.read());
        } catch (RuntimeException ignored) {
            // Preserve an authenticated in-memory session when boundary storage is unavailable.
            reconcileWatchdog(appContext, repository.lastSnapshot());
        }
    }

    private void reconcileWatchdog(Context context, SessionRecord record) {
        if (record != null && record.isActivePlan()) {
            try {
                SessionWatchdogService.start(context);
            } catch (RuntimeException ignored) {
                // The authenticated journal remains authoritative for the next recovery boundary.
            }
        } else {
            try {
                SessionWatchdogService.stop(context);
            } catch (RuntimeException ignored) {
                // No idle state transition is performed when service reconciliation is unavailable.
            }
        }
    }
}
