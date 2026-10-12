package com.cockblocker.app.service;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.cockblocker.app.data.SessionRecord;
import com.cockblocker.app.data.StateRepository;
import com.cockblocker.app.platform.SafetySurfaceResolver;


/** Best-effort blocked-notification cancellation using metadata only. */
public final class NotificationBlocker extends NotificationListenerService {
    private StateRepository repository;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        repository = StateRepository.get(getApplicationContext());
        repository.initialize();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification notification : active) {
                    cancelIfBlocked(notification);
                }
            }
        } catch (RuntimeException ignored) {
            // Notification access can be revoked between binding and enumeration.
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        // Losing listener binding is a capability change, never a release event.
        if (repository == null) {
            repository = StateRepository.get(getApplicationContext());
        }
        try {
            SessionRecord record = repository.read();
            if (record != null && record.isEnforcing()) {
                repository.markTampered(record.getSessionId(), record.getSequence(),
                        "Notification listener access was disconnected.");
            }
        } catch (RuntimeException ignored) {
            // A disconnected system service cannot safely mutate further state here.
        }
        repository = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        cancelIfBlocked(notification);
    }

    private void cancelIfBlocked(StatusBarNotification notification) {
        if (notification == null || repository == null) {
            return;
        }
        // Only package/key/ongoing metadata is consulted. Notification content is
        // intentionally never read or retained.
        String packageName = notification.getPackageName();
        if (packageName == null || notification.isOngoing()) {
            return;
        }
        try {
            SessionRecord record = repository.read();
            if (record == null || !record.isEnforcing()
                    || !record.effectiveBlocked().contains(packageName)) {
                return;
            }
            if (SafetySurfaceResolver.redirectableCriticalPackages(this, record.getCriticalPackages())
                    .contains(packageName)) {
                return;
            }
            String key = notification.getKey();
            if (key != null) {
                cancelNotification(key);
            }
        } catch (RuntimeException ignored) {
            // Notification races and system exemptions are expected; no state release.
        }
    }
}
