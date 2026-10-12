package com.cockblocker.app.trust;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/** One explicit Android prerequisite and its user-facing remediation action. */
public final class Check {
    public final String id;
    public final String label;
    public final boolean satisfied;
    public final String reason;

    public Check(String id, String label, boolean satisfied, String reason) {
        this.id = id == null ? "" : id;
        this.label = label == null ? "" : label;
        this.satisfied = satisfied;
        this.reason = reason == null ? "" : reason;
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public boolean isSatisfied() { return satisfied; }
    public boolean isOk() { return satisfied; }
    public String getReason() { return reason; }

    public Intent remediation(Context context) {
        return TrustAnchorGate.remediationFor(context, this);
    }

    static Intent defaultRemediation(Context context, String checkId) {
        if (context == null) {
            return null;
        }
        Intent intent;
        switch (checkId) {
            case TrustAnchorGate.ID_HOME:
                intent = new Intent(Settings.ACTION_HOME_SETTINGS);
                break;
            case TrustAnchorGate.ID_ACCESSIBILITY:
                intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                break;
            case TrustAnchorGate.ID_NOTIFICATION_ACCESS:
                intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                break;
            case TrustAnchorGate.ID_USAGE_ACCESS:
                intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                break;
            case TrustAnchorGate.ID_OVERLAY:
                intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.getPackageName()));
                break;
            case TrustAnchorGate.ID_SECURE_LOCK:
                intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
                break;
            case TrustAnchorGate.ID_EXACT_ALARM:
                intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.getPackageName()));
                break;
            case TrustAnchorGate.ID_BATTERY:
                intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                break;
            case TrustAnchorGate.ID_NOTIFICATIONS:
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
                break;
            default:
                intent = new Intent(Settings.ACTION_SETTINGS);
                break;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
