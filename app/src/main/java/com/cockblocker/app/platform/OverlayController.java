package com.cockblocker.app.platform;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Owns the single, non-deceptive application overlay used by the watchdog.
 * It deliberately has no release action and is only ever shown for a surface the
 * signed record classifies as redirectable.
 */
public final class OverlayController {
    private static final int TYPE_OVERLAY = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

    private final Context context;
    private final WindowManager windowManager;
    private final Set<String> criticalPackages = new HashSet<>();
    private View overlay;
    private boolean lastWasRecovery;
    private String lastMessage;

    public OverlayController(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = this.context.getSystemService(WindowManager.class);
    }

    /** Replace the live safety exclusion set. The signed record remains authoritative. */
    public void setCriticalPackages(Set<String> packages) {
        synchronized (criticalPackages) {
            criticalPackages.clear();
            if (packages != null) {
                for (String value : packages) {
                    if (value != null && !value.isEmpty()) {
                        criticalPackages.add(value);
                    }
                }
            }
        }
    }

    public void showBlockedRedirect(String packageName) {
        if (isProtectedSurface(packageName)) {
            return;
        }
        show(SafetySurfaceResolver.resolveSettingsPackages(context).contains(packageName)
                ? "Settings is locked while focus is running."
                : "Cockblocker is active. This app is paused for this session.", false, null);
    }

    public void showRecovery(String reason) {
        showRecoveryBanner(reason, null);
    }

    public void showRecoveryBanner(String reason, Intent remediation) {
        String message = reason == null || reason.trim().isEmpty()
                ? "Cockblocker needs attention. The session remains active." : reason;
        show(message, true, remediation);
    }

    public void showTrustedSnapshotStatus(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            reason = "Cockblocker is using its last authenticated session snapshot.";
        }
        show(reason, true, null);
    }

    public void showDenyAllWithoutDeadline(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            reason = "Cockblocker could not authenticate a session. No early release is available.";
        }
        show(reason, true, null);
    }

    public void clearAfterReleased() {
        removeOverlay();
    }

    public void clearForBreak() {
        removeOverlay();
    }

    public boolean isShowing() {
        return overlay != null;
    }

    private void show(String message, boolean recovery, Intent remediation) {
        if (windowManager == null || !canDraw()) {
            removeOverlay();
            return;
        }
        if (overlay != null && lastWasRecovery == recovery && safeEquals(lastMessage, message)) {
            return;
        }
        removeOverlay();
        try {
            int dp = Math.round(16 * context.getResources().getDisplayMetrics().density);

            LinearLayout panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp + 4, dp, dp + 4, dp);
            GradientDrawable shell = new GradientDrawable();
            shell.setShape(GradientDrawable.RECTANGLE);
            shell.setColor(0xFF0D1322);
            shell.setCornerRadius(dp);
            shell.setStroke(Math.max(1, Math.round(context.getResources()
                    .getDisplayMetrics().density)), 0xFF232B42);
            panel.setBackground(shell);

            TextView text = new TextView(context);
            text.setText(message);
            text.setTextColor(0xFFEDF0F7);
            text.setTextSize(14f);
            text.setLineSpacing(0f, 1.3f);
            panel.addView(text, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            if (recovery && remediation != null) {
                Button button = new Button(context);
                button.setAllCaps(false);
                button.setText("Open repair settings");
                button.setTextSize(13);
                button.setTypeface(Typeface.DEFAULT_BOLD);
                button.setMinHeight(0);
                button.setMinimumHeight(0);
                button.setTextColor(0xFF07101F);
                GradientDrawable accent = new GradientDrawable();
                accent.setShape(GradientDrawable.RECTANGLE);
                accent.setColor(0xFF7BA2F5);
                accent.setCornerRadius(dp / 2f);
                button.setBackground(accent);
                LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                buttonParams.topMargin = Math.round(12 * context.getResources()
                        .getDisplayMetrics().density);
                panel.addView(button, buttonParams);
                button.setOnClickListener(v -> {
                    // Removing the view before launching Settings prevents an overlay trap.
                    removeOverlay();
                    Intent safe = new Intent(remediation);
                    safe.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(safe);
                    } catch (RuntimeException ignored) {
                        // Recovery remains visible in the app when a route is unavailable.
                        show(message, true, null);
                    }
                });
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    TYPE_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            windowManager.addView(panel, params);
            overlay = panel;
            lastWasRecovery = recovery;
            lastMessage = message;
        } catch (RuntimeException ignored) {
            // Overlay permission or a system window restriction must never release a session.
            removeOverlay();
        }
    }

    private boolean canDraw() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    private boolean isProtectedSurface(String packageName) {
        if (packageName == null || packageName.isEmpty()
                || context.getPackageName().equals(packageName)
                || "android".equals(packageName)
                || packageName.endsWith(".systemui")) {
            return true;
        }
        // Settings is not exempt: a locked Settings surface is exactly the case this
        // banner exists for. Only surfaces the signed record never redirects are.
        synchronized (criticalPackages) {
            return criticalPackages.contains(packageName);
        }
    }

    private void removeOverlay() {
        if (overlay != null && windowManager != null) {
            try {
                windowManager.removeView(overlay);
            } catch (RuntimeException ignored) {
                // The view may already have been detached by the system.
            }
        }
        overlay = null;
        lastMessage = null;
        lastWasRecovery = false;
    }

    private static boolean safeEquals(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }
}
