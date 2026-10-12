package com.cockblocker.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.cockblocker.app.data.SessionRecord;
import com.cockblocker.app.data.StateRepository;
import com.cockblocker.app.service.SessionWatchdogService;

/** Fixed immutable alarm wake-up. Delivery is never release authority. */
public final class SessionEndReceiver extends BroadcastReceiver {
    public static final String ACTION_END = "com.cockblocker.app.action.SESSION_END";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_END.equals(intent.getAction())) {
            return;
        }
        try {
            StateRepository repository = StateRepository.get(context.getApplicationContext());
            repository.initialize();
            SessionRecord record = repository.read();
            if (record != null && record.getState() == com.cockblocker.app.data.SessionState.RELEASED) {
                SessionWatchdogService.stop(context.getApplicationContext());
            } else {
                // The service revalidates monotonic expiry under StateRepository.
                SessionWatchdogService.start(context.getApplicationContext());
            }
        } catch (RuntimeException ignored) {
            SessionWatchdogService.start(context.getApplicationContext());
        }
    }
}
