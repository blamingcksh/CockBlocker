package com.cockblocker.app.service;

import com.cockblocker.app.data.SessionRecord;
import com.cockblocker.app.data.SessionState;
import com.cockblocker.app.platform.SafetySurfaceResolver;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The session redirect policy, exercised on the JVM. A null context makes
 * {@link SafetySurfaceResolver} answer from its built-in package constants, which is
 * the same answer a device gives for Settings.
 */
public final class LockAccessibilityServiceTest {
    private static final String OWN = "com.cockblocker.app";
    private static final String SETTINGS = "com.android.settings";
    private static final String ALLOWED_APP = "com.notes";
    private static final String BLOCKED_APP = "com.social";

    private final Set<String> settingsPackages = SafetySurfaceResolver.resolveSettingsPackages(null);
    private final Set<String> critical = SafetySurfaceResolver.redirectableCriticalPackages(null,
            Collections.<String>emptySet());

    @Test
    public void allowedAppStaysOpen() {
        SessionRecord record = record(SessionState.ACTIVE, set(ALLOWED_APP), set(BLOCKED_APP));

        assertEquals(LockAccessibilityService.Guard.ALLOW, decide(record, ALLOWED_APP, false));
    }

    @Test
    public void blockedAppIsRedirected() {
        SessionRecord record = record(SessionState.ACTIVE, set(ALLOWED_APP), set(BLOCKED_APP));

        assertEquals(LockAccessibilityService.Guard.REDIRECT, decide(record, BLOCKED_APP, false));
    }

    @Test
    public void settingsIsLockedWhileFocusRuns() {
        SessionRecord record = record(SessionState.ACTIVE, set(ALLOWED_APP), set(BLOCKED_APP));

        assertEquals(LockAccessibilityService.Guard.REDIRECT, decide(record, SETTINGS, false));
    }

    @Test
    public void settingsOpensWhenTheUserAllowedIt() {
        SessionRecord record = record(SessionState.ACTIVE, set(ALLOWED_APP, SETTINGS), set(BLOCKED_APP));

        assertEquals(LockAccessibilityService.Guard.ALLOW, decide(record, SETTINGS, false));
    }

    @Test
    public void settingsStaysLockedAfterAProtectedRouteVisit() {
        // TAMPERED is how a route visit is recorded; it is not a lost capability, so it
        // must not hand the rest of the session back its Settings surface.
        SessionRecord record = record(SessionState.TAMPERED, set(ALLOWED_APP), set(BLOCKED_APP));

        assertEquals(LockAccessibilityService.Guard.REDIRECT, decide(record, SETTINGS, false));
    }

    @Test
    public void settingsReopensWhenACapabilityIsMissing() {
        SessionRecord record = record(SessionState.DEGRADED, set(ALLOWED_APP), set(BLOCKED_APP));

        assertEquals(LockAccessibilityService.Guard.ALLOW, decide(record, SETTINGS, true));
    }

    @Test
    public void protectedRouteIsFlaggedAndClosedWhileEnforcementIsHealthy() {
        SessionRecord record = record(SessionState.ACTIVE, set(ALLOWED_APP), set(BLOCKED_APP),
                set("com.android.permissioncontroller/"
                        + "com.android.permissioncontroller.GrantPermissionsActivity"));

        assertEquals(LockAccessibilityService.Guard.TAMPER_AND_SEND_HOME,
                decide(record, "com.android.permissioncontroller", false));
    }

    @Test
    public void protectedRouteStaysOpenDuringRepair() {
        SessionRecord record = record(SessionState.TAMPERED, set(ALLOWED_APP), set(BLOCKED_APP),
                set("com.android.permissioncontroller/"
                        + "com.android.permissioncontroller.GrantPermissionsActivity"));

        assertEquals(LockAccessibilityService.Guard.TAMPER_STAY,
                decide(record, "com.android.permissioncontroller", true));
    }

    @Test
    public void cockblockerItselfIsNeverRedirected() {
        SessionRecord record = record(SessionState.ACTIVE, set(ALLOWED_APP), set(BLOCKED_APP, OWN));

        assertEquals(LockAccessibilityService.Guard.ALLOW, decide(record, OWN, false));
    }

    @Test
    public void safetySurfacesAreNeverRedirected() {
        SessionRecord record = record(SessionState.ACTIVE, set(ALLOWED_APP), set(BLOCKED_APP));

        assertEquals(LockAccessibilityService.Guard.ALLOW, decide(record, "com.android.systemui", false));
        assertEquals(LockAccessibilityService.Guard.ALLOW, decide(record, "com.android.emergency", false));
    }

    @Test
    public void settingsListedAsCriticalByAnOlderRecordIsStillLocked() {
        // A session signed before Settings became blockable lists it as critical. That
        // must not survive into the redirect decision.
        Set<String> legacyCritical = set(SETTINGS, "android", "com.android.systemui");
        SessionRecord record = record(SessionState.ACTIVE, set(ALLOWED_APP), set(BLOCKED_APP, SETTINGS),
                Collections.<String>emptySet(), legacyCritical);
        Set<String> redirectable = SafetySurfaceResolver.redirectableCriticalPackages(null, legacyCritical);

        assertFalse(redirectable.contains(SETTINGS));
        assertTrue(redirectable.contains("android"));
        assertEquals(LockAccessibilityService.Guard.REDIRECT, decide(record, SETTINGS, false));
    }

    private LockAccessibilityService.Guard decide(SessionRecord record, String packageName,
                                                 boolean repairOpen) {
        return LockAccessibilityService.decide(record, packageName, OWN, critical, settingsPackages,
                () -> repairOpen);
    }

    private static SessionRecord record(SessionState state, Set<String> allowed, Set<String> blocked,
                                        Set<String> routes) {
        return record(state, allowed, blocked, routes, Collections.<String>emptySet());
    }

    private static SessionRecord record(SessionState state, Set<String> allowed, Set<String> blocked) {
        return record(state, allowed, blocked, Collections.<String>emptySet(),
                Collections.<String>emptySet());
    }

    private static SessionRecord record(SessionState state, Set<String> allowed, Set<String> blocked,
                                        Set<String> routes, Set<String> criticalPackages) {
        return new SessionRecord(SessionRecord.CURRENT_SCHEMA_VERSION, "session-1", 1L, null, state,
                1000L, 1000L, 2000L, 1000L, 1000L, 1000L, 1000L, "",
                allowed, blocked, criticalPackages, routes, "cert", 1, "TEST", "TEST", null, null);
    }

    @Test
    public void blockedAppInTheAndroidNamespaceIsStillRedirected() {
        // com.android.* is a platform-namespace exemption, not a hole in the blocklist.
        // A browser or store the session blocks must be redirected like any other app.
        SessionRecord record = record(SessionState.ACTIVE, set(ALLOWED_APP), set(BLOCKED_APP, "com.android.chrome"));

        assertEquals(LockAccessibilityService.Guard.REDIRECT, decide(record, "com.android.chrome", false));
    }

    @Test
    public void criticalPackageBlockedByTheUserIsStillExempt() {
        // The namespace fix must not lock the user out of a genuine safety surface: a
        // critical package is subtracted from effectiveBlocked, so it stays ALLOW.
        SessionRecord record = record(SessionState.ACTIVE, set(ALLOWED_APP), set(BLOCKED_APP),
                Collections.<String>emptySet(), set("com.android.systemui"));

        assertEquals(LockAccessibilityService.Guard.ALLOW, decide(record, "com.android.systemui", false));
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
