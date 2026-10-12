package com.cockblocker.app.service;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import com.cockblocker.app.data.SessionRecord;
import com.cockblocker.app.data.SessionState;
import com.cockblocker.app.data.StateRepository;
import com.cockblocker.app.platform.SafetySurfaceResolver;
import com.cockblocker.app.trust.GateResult;
import com.cockblocker.app.trust.TrustAnchorGate;

import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Package-name-only accessibility guard. It never inspects node text, window
 * content, or coordinates and never synthesizes taps.
 */
public final class LockAccessibilityService extends AccessibilityService {
    private static final long SAME_PACKAGE_DEBOUNCE_MS = 750L;

    /** What the guard does with one foreground window. */
    enum Guard {
        ALLOW,
        REDIRECT,
        TAMPER_AND_SEND_HOME,
        TAMPER_STAY
    }

    private StateRepository repository;
    private String lastPackage;
    private long lastEventElapsedMs;
    private boolean homeResponseSent;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        repository = StateRepository.get(getApplicationContext());
        repository.initialize();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || repository == null) {
            return;
        }
        String packageName = event.getPackageName() == null
                ? null : event.getPackageName().toString();
        if (packageName == null || packageName.isEmpty()) {
            return;
        }

        SessionRecord record;
        try {
            record = repository.read();
        } catch (RuntimeException ignored) {
            return;
        }
        if (record == null || !record.isEnforcing()) {
            resetTransitionState();
            return;
        }

        Guard guard = decide(record, packageName, getPackageName(),
                SafetySurfaceResolver.redirectableCriticalPackages(this, record.getCriticalPackages()),
                SafetySurfaceResolver.resolveSettingsPackages(this),
                () -> repairOpen(record));
        if (guard == Guard.ALLOW) {
            resetTransitionState();
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (packageName.equals(lastPackage) && now - lastEventElapsedMs < SAME_PACKAGE_DEBOUNCE_MS) {
            return;
        }
        if (!packageName.equals(lastPackage)) {
            homeResponseSent = false;
        }
        lastPackage = packageName;
        lastEventElapsedMs = now;

        if (guard == Guard.REDIRECT) {
            // Enforcement first, cosmetics second. showBlockedRedirect starts a foreground
            // service, which Android 12+ refuses from the background; a throw there used to
            // skip sendHomeOnce entirely while lastPackage was already latched above, so the
            // blocked app sat in the foreground with neither a redirect nor a banner.
            sendHomeOnce();
            showBanner(() -> SessionWatchdogService.showBlockedRedirect(this, packageName));
            return;
        }
        repository.markTampered(record.getSessionId(), record.getSequence(),
                "A protected Settings route was opened while the session was active.");
        if (guard == Guard.TAMPER_AND_SEND_HOME) {
            sendHomeOnce();
        }
        showBanner(() -> SessionWatchdogService.showRecovery(this,
                "A protected Settings route was opened. Restore the required capability; "
                        + "the session has not been released."));
    }

    /**
     * Banner display is diagnostic and must never gate enforcement. It starts a foreground
     * service, so it can legitimately fail on Android 12+, and it must not propagate a
     * RuntimeException into the accessibility framework either.
     */
    private void showBanner(Runnable banner) {
        try {
            banner.run();
        } catch (RuntimeException refused) {
            // The redirect or the tamper record has already happened; nothing to undo.
        }
    }

    @Override
    public void onInterrupt() {
        // No taps, global actions, or state changes are performed from an interrupt.
    }

    /**
     * The session's redirect policy for one foreground window: a function of the signed
     * record and the resolved surface sets alone, so the policy itself carries no
     * platform state. {@code repairWindow} is consulted only where it can change the
     * answer, which keeps the capability probe off the common path.
     */
    static Guard decide(SessionRecord record, String packageName, String ownPackage,
                        Set<String> redirectableCritical, Set<String> settingsPackages,
                        BooleanSupplier repairWindow) {
        boolean settingsSurface = settingsPackages != null && settingsPackages.contains(packageName);
        if (packageName.equals(ownPackage)
                || record.getAllowed().contains(packageName)
                || settingsSurface && isRepairWindowOpen(repairWindow)) {
            return Guard.ALLOW;
        }
        if (settingsSurface) {
            // Settings was left out of the session's exceptions, so it stays locked.
            return Guard.REDIRECT;
        }
        // A protected route is flagged before any system-package exemption: revoking a
        // capability from a system surface is the tamper signal, not a redirect.
        if (isProtectedRoute(record.getProtectedRoutes(), packageName)) {
            return isRepairWindowOpen(repairWindow) ? Guard.TAMPER_STAY : Guard.TAMPER_AND_SEND_HOME;
        }
        // The namespace exemption protects platform surfaces, not the user's blocklist:
        // a com.android.* app the session actually blocks (a browser, a store) is
        // redirected, while a real safety surface is still exempt because
        // effectiveBlocked() has already subtracted the record's critical packages.
        if (isRedirectExemptSurface(redirectableCritical, packageName)
                && !record.effectiveBlocked().contains(packageName)) {
            return Guard.ALLOW;
        }
        return record.effectiveBlocked().contains(packageName) ? Guard.REDIRECT : Guard.ALLOW;
    }

    /**
     * Settings reopens only while a capability the session depends on is genuinely gone:
     * a healthy session cannot reach Settings to break itself, and a broken one has to
     * stay repairable. Opening a protected route is user action, not a lost capability,
     * so it never opens this window on its own.
     */
    private boolean repairOpen(SessionRecord record) {
        if (record.getState() == SessionState.ACTIVE) {
            return false;
        }
        if ("JOURNAL_UNAVAILABLE".equals(record.getLastIntegrityReason())) {
            return true;
        }
        try {
            GateResult health = TrustAnchorGate.evaluateHealth(this);
            return health == null || !health.isOk();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void sendHomeOnce() {
        if (!homeResponseSent) {
            homeResponseSent = true;
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    private void resetTransitionState() {
        homeResponseSent = false;
        lastPackage = null;
        lastEventElapsedMs = 0L;
    }

    private static boolean isRepairWindowOpen(BooleanSupplier repairWindow) {
        return repairWindow != null && repairWindow.getAsBoolean();
    }

    /** Critical surfaces plus the system packages that must never be redirected. */
    private static boolean isRedirectExemptSurface(Set<String> redirectableCritical, String packageName) {
        if (redirectableCritical != null && redirectableCritical.contains(packageName)) {
            return true;
        }
        return "android".equals(packageName)
                || "com.android.systemui".equals(packageName)
                || packageName.endsWith(".systemui")
                || packageName.startsWith("com.android.");
    }

    private static boolean isProtectedRoute(Set<String> routes, String packageName) {
        if (routes == null) {
            return false;
        }
        for (String route : routes) {
            if (route == null) {
                continue;
            }
            if (route.equals(packageName) || route.startsWith(packageName + "/")
                    || route.startsWith(packageName + ":") || route.contains("/" + packageName + "/")) {
                return true;
            }
        }
        return false;
    }
}
