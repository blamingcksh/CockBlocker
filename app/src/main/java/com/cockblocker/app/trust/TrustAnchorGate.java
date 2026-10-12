package com.cockblocker.app.trust;

import android.app.NotificationChannel;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import android.os.PowerManager;
import android.provider.Settings;
import com.cockblocker.app.service.LockAccessibilityService;
import com.cockblocker.app.service.NotificationBlocker;
import com.cockblocker.app.service.SessionWatchdogService;

import java.util.Locale;

import java.util.ArrayList;
import java.util.List;

/** Explicit setup checks for the security-sensitive Android capabilities. */
public final class TrustAnchorGate {
    public static final String ID_HOME = "home";
    public static final String ID_ACCESSIBILITY = "accessibility";
    public static final String ID_NOTIFICATION_ACCESS = "notification_access";
    public static final String ID_USAGE_ACCESS = "usage_access";
    public static final String ID_OVERLAY = "overlay";
    public static final String ID_SECURE_LOCK = "secure_lock";
    public static final String ID_EXACT_ALARM = "exact_alarm";
    public static final String ID_BATTERY = "battery";
    public static final String ID_NOTIFICATIONS = "notifications";

    private TrustAnchorGate() { }

    public static GateResult evaluateForStart(Context context) {
        return evaluate(context, true);
    }

    public static GateResult evaluateForActivation(Context context) {
        return evaluate(context, true);
    }

    public static GateResult evaluateHealth(Context context) {
        return evaluate(context, false);
    }

    public static Intent remediation(Context context, Check check) {
        return remediationFor(context, check);
    }

    static Intent remediationFor(Context context, Check check) {
        return check == null ? null : Check.defaultRemediation(context, check.id);
    }

    private static GateResult evaluate(Context context, boolean includeExactAlarm) {
        List<Check> checks = new ArrayList<>();
        if (context == null) {
            checks.add(new Check(ID_HOME, "Home role", false, "Android context unavailable"));
            return new GateResult(false, checks, "Android context unavailable");
        }
        checks.add(new Check(ID_HOME, "Home role", hasHome(context), "Set Cockblocker as the Home app"));
        checks.add(new Check(ID_ACCESSIBILITY, "Accessibility service", hasAccessibility(context),
                "Enable Cockblocker Accessibility in Android Settings"));
        checks.add(new Check(ID_NOTIFICATION_ACCESS, "Notification access", hasNotificationAccess(context),
                "Enable Cockblocker notification access in Android Settings"));
        checks.add(new Check(ID_USAGE_ACCESS, "Usage access", hasUsageAccess(context),
                "Enable Usage access for Cockblocker"));
        checks.add(new Check(ID_OVERLAY, "Display over other apps", hasOverlay(context),
                "Allow Cockblocker to display over other apps"));
        checks.add(new Check(ID_SECURE_LOCK, "Secure lock screen", hasSecureLock(context),
                "Set a secure screen lock in Android Settings"));
        checks.add(new Check(ID_EXACT_ALARM, "Exact alarms", hasExactAlarms(context),
                "Allow Cockblocker to schedule exact alarms"));
        checks.add(new Check(ID_BATTERY, "Battery optimization exemption", hasBatteryExemption(context),
                "Exempt Cockblocker from battery optimization"));
        checks.add(new Check(ID_NOTIFICATIONS, "Notifications", hasNotifications(context),
                "Enable Cockblocker notifications"));
        boolean ok = true;
        for (Check check : checks) {
            ok &= check.satisfied;
        }
        return new GateResult(ok, checks);
    }

    /**
     * Resolving ACTION_HOME and comparing the package is the authoritative check on every
     * API level. The RoleManager branch this replaces was both weaker and simpler to get
     * wrong: isRoleHeld is satisfied by ANY app holding the role and never names the holder
     * (RoleManager.getRoleHolders is a hidden @SystemApi, not public SDK), so it reported
     * the Home role healthy after the role had been handed to another app - while every
     * GLOBAL_ACTION_HOME redirect was landing the user there instead.
     */
    private static boolean hasHome(Context context) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = context.getPackageManager()
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                && context.getPackageName().equals(resolved.activityInfo.packageName);
    }

    private static boolean hasAccessibility(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return containsEnabledComponent(enabled,
                new ComponentName(context, LockAccessibilityService.class));
    }

    private static boolean hasNotificationAccess(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        return containsEnabledComponent(enabled,
                new ComponentName(context, NotificationBlocker.class));
    }

    private static boolean containsEnabledComponent(String enabled, ComponentName expected) {
        if (enabled == null || enabled.isEmpty()) {
            return false;
        }
        String expectedComponent = expected.flattenToString().toLowerCase(Locale.ROOT);
        for (String value : enabled.split(":")) {
            ComponentName actual = ComponentName.unflattenFromString(value.trim());
            if (actual != null && expectedComponent.equals(actual.flattenToString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUsageAccess(Context context) {
        AppOpsManager manager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (manager == null) {
            return false;
        }
        try {
            return manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean hasOverlay(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context);
    }

    private static boolean hasSecureLock(Context context) {
        KeyguardManager manager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return manager != null && manager.isDeviceSecure();
    }

    private static boolean hasExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    private static boolean hasBatteryExemption(Context context) {
        PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return manager != null && manager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private static boolean hasNotifications(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        NotificationChannel watchdogChannel = manager.getNotificationChannel(SessionWatchdogService.CHANNEL_ID);
        return watchdogChannel != null
                && watchdogChannel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
}
