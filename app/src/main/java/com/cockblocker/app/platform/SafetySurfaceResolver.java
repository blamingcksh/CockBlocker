package com.cockblocker.app.platform;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.inputmethod.InputMethodManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** Conservative package/component inventory for surfaces that must never be redirected. */
public final class SafetySurfaceResolver {
    public SafetySurfaceResolver() { }

    public static Set<String> resolveCriticalPackages(Context context) {
        TreeSet<String> result = new TreeSet<>();
        if (context == null) {
            result.add("android");
            result.add("com.android.systemui");
            return immutable(result);
        }
        result.add(context.getPackageName());
        result.add("android");
        result.add("com.android.systemui");
        result.add("com.android.phone");
        result.add("com.android.server.telecom");
        result.add("com.android.emergency");
        result.add("com.android.calllog");
        result.add("com.google.android.dialer");
        try {
            TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (telecom != null && telecom.getDefaultDialerPackage() != null) {
                result.add(telecom.getDefaultDialerPackage());
            }
        } catch (RuntimeException ignored) {
        }
        try {
            String sms = android.provider.Telephony.Sms.getDefaultSmsPackage(context);
            if (sms != null) {
                result.add(sms);
            }
        } catch (RuntimeException ignored) {
        }
        try {
            InputMethodManager input = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (input != null && input.getCurrentInputMethodInfo() != null
                    && input.getCurrentInputMethodInfo().getPackageName() != null) {
                result.add(input.getCurrentInputMethodInfo().getPackageName());
            }
        } catch (RuntimeException ignored) {
        }
        return immutable(result);
    }

    /**
     * Settings is a blockable surface, not a safety surface: the user can lock it for the
     * length of a session. It is resolved on its own so the accessibility guard, the
     * overlay, and the selection UI can never disagree about it.
     */
    public static Set<String> resolveSettingsPackages(Context context) {
        TreeSet<String> result = new TreeSet<>();
        result.add("com.android.settings");
        result.add("com.android.providers.settings");
        if (context == null) {
            return immutable(result);
        }
        PackageManager manager = context.getPackageManager();
        addResolvedPackages(result, manager, new Intent(Settings.ACTION_SETTINGS));
        addResolvedPackages(result, manager, new Intent(Settings.ACTION_HOME_SETTINGS));
        addResolvedPackages(result, manager, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        addResolvedPackages(result, manager, new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        addResolvedPackages(result, manager, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName())));
        addResolvedPackages(result, manager,
                new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        // Resolution can name a platform package; those stay safety-critical, never blockable.
        result.remove(context.getPackageName());
        result.remove("android");
        result.remove("com.android.systemui");
        return immutable(result);
    }

    /**
     * Critical surfaces a redirect may still apply to. A session signed before Settings
     * became blockable can still list it as critical, so it is dropped here and every
     * enforcement layer answers the same way.
     */
    public static Set<String> redirectableCriticalPackages(Context context, Set<String> recordCritical) {
        Set<String> result = new HashSet<>();
        if (recordCritical != null) {
            result.addAll(recordCritical);
        }
        result.addAll(resolveCriticalPackages(context));
        result.removeAll(resolveSettingsPackages(context));
        return Collections.unmodifiableSet(result);
    }

    public static Set<String> resolveProtectedRoutes(Context context) {
        TreeSet<String> result = new TreeSet<>();
        if (context == null) {
            result.add("com.android.settings/");
            return immutable(result);
        }
        PackageManager manager = context.getPackageManager();
        addQueryHandlers(result, manager, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        addQueryHandlers(result, manager, new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        addQueryHandlers(result, manager, new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        addQueryHandlers(result, manager, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        addQueryHandlers(result, manager, new Intent(Settings.ACTION_HOME_SETTINGS));
        addQueryHandlers(result, manager, new Intent(Settings.ACTION_SECURITY_SETTINGS));
        addQueryHandlers(result, manager, new Intent(Settings.ACTION_DATE_SETTINGS));
        addQueryHandlers(result, manager, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + context.getPackageName())));
        addQueryHandlers(result, manager, new Intent("android.settings.DEVICE_ADMIN_SETTINGS"));
        addQueryHandlers(result, manager, new Intent("android.settings.REQUEST_INSTALL_PACKAGES"));
        result.add("com.android.settings/");
        result.add("com.android.providers.settings/");
        result.add("com.android.systemui/");
        return immutable(result);
    }

    private static void addQueryHandlers(Set<String> result, PackageManager manager, Intent intent) {
        try {
            for (ResolveInfo info : manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
                if (info.activityInfo != null) {
                    result.add(info.activityInfo.packageName + "/" + info.activityInfo.name);
                    result.add(info.activityInfo.packageName);
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void addResolvedPackages(Set<String> result, PackageManager manager, Intent intent) {
        try {
            for (ResolveInfo info : manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
                if (info.activityInfo != null && info.activityInfo.packageName != null) {
                    result.add(info.activityInfo.packageName);
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static Set<String> immutable(Set<String> values) {
        return Collections.unmodifiableSet(values);
    }
}
