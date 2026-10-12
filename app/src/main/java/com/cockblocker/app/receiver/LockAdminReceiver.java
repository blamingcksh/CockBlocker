package com.cockblocker.app.receiver;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

import com.cockblocker.app.data.SessionRecord;
import com.cockblocker.app.data.StateRepository;
import com.cockblocker.app.service.SessionWatchdogService;

/** Optional optimization receiver. It never locks, wipes, or changes passwords. */
public final class LockAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        // Admin is optional and is not a release authority.
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Cockblocker cannot prevent Android from removing its optional administrator. "
                + "The focus session will not be released.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        try {
            StateRepository repository = StateRepository.get(context.getApplicationContext());
            repository.initialize();
            SessionRecord record = repository.read();
            if (record != null && record.isEnforcing()) {
                repository.markDegraded(record.getSessionId(), record.getSequence(),
                        "The optional Device Administrator capability was removed.");
                SessionWatchdogService.showRecovery(context,
                        "The optional administrator was removed. The session remains protected.");
            }
        } catch (RuntimeException ignored) {
            // A missing optional capability must not cause a release.
        }
    }
}
