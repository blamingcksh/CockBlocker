package com.cockblocker.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.cockblocker.app.data.StateRepository;
import com.cockblocker.app.service.SessionWatchdogService;

/** Periodic signed checkpoint wake-up. */
public final class CheckpointReceiver extends BroadcastReceiver {
    public static final String ACTION_CHECKPOINT = "com.cockblocker.app.action.CHECKPOINT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_CHECKPOINT.equals(intent.getAction())) {
            return;
        }
        try {
            StateRepository.get(context.getApplicationContext()).initialize();
        } catch (RuntimeException ignored) {
            // The service still performs authenticated recovery.
        }
        SessionWatchdogService.start(context.getApplicationContext());
    }
}
