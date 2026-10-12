package com.cockblocker.app.data;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public final class PomodoroPlanTest {
    @Test
    public void maximumPlanAdvancesThroughEveryFocus() {
        PomodoroPlan plan = PomodoroPlan.initial(90, 30, 12);
        assertEquals(90, plan.getFocusMinutes());
        assertEquals(30, plan.getBreakMinutes());
        assertEquals(12, plan.getTotalSessions());
        assertEquals(1, plan.getCurrentSession());

        PomodoroPlan next = plan;
        for (int expected = 2; expected <= 12; expected++) {
            next = next.nextFocus();
            assertEquals(expected, next.getCurrentSession());
        }
        assertEquals(true, next.isFinalFocus());
        assertThrows(IllegalStateException.class, next::nextFocus);
    }

    @Test
    public void planBoundsAreEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> PomodoroPlan.initial(0, 5, 4));
        assertThrows(IllegalArgumentException.class,
                () -> PomodoroPlan.initial(91, 5, 4));
        assertThrows(IllegalArgumentException.class,
                () -> PomodoroPlan.initial(25, 0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> PomodoroPlan.initial(25, 31, 4));
        assertThrows(IllegalArgumentException.class,
                () -> PomodoroPlan.initial(25, 5, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PomodoroPlan.initial(25, 5, 13));
    }
    @Test
    public void straightTimerIsBoundedToTwelveHours() {
        PomodoroPlan plan = PomodoroPlan.straight(12 * 60);
        assertEquals(true, plan.isStraight());
        assertEquals(12 * 60, plan.getFocusMinutes());
        assertEquals("12 hr", plan.getFocusLabel());
        assertEquals(0, plan.getBreakMinutes());
        assertEquals(1, plan.getTotalSessions());
        assertEquals(true, plan.isFinalFocus());
        assertThrows(IllegalStateException.class, plan::nextFocus);
        assertThrows(IllegalArgumentException.class, () -> PomodoroPlan.straight(12 * 60 + 1));
    }

    @Test
    public void recordCopiesPreserveSignedPlan() {
        PomodoroPlan plan = PomodoroPlan.initial(25, 5, 4);
        SessionRecord record = new SessionRecord(
                SessionRecord.CURRENT_SCHEMA_VERSION, "session", 1L, null,
                SessionState.IDLE, 1L, 1L, 0L, 0L, 0L, 1L, 1L, "",
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), "cert", 1, "GENESIS", "GENESIS", plan, null);
        SessionRecord active = record.withState(SessionState.ACTIVE);
        SessionRecord paused = active.withState(SessionState.BREAK);

        assertNotNull(active.getPomodoroPlan());
        assertEquals(plan.getFocusMinutes(), paused.getPomodoroPlan().getFocusMinutes());
        assertEquals(plan.getTotalSessions(), paused.getPomodoroPlan().getTotalSessions());
        assertNotNull(paused.contentHash());
    }
    @Test
    public void legacyRecordHashRemainsStable() {
        SessionRecord record = new SessionRecord(
                1, "session", 1L, null, SessionState.IDLE, 1L, 1L, 0L, 0L, 0L, 1L, 1L,
                "", Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), "cert", 1, "GENESIS", "GENESIS", null);
        assertEquals("8e906054e7ec54f6d85efd5c9de55f2e0d2bc4db2e31114070bfaa8517d9e434",
                hex(record.contentHash()));
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(String.format(java.util.Locale.US, "%02x", item & 0xff));
        }
        return value.toString();
    }
    @Test
    public void legacyMigrationAppendsInsteadOfRewritingTheTip() {
        SessionRecord legacyBase = new SessionRecord(
                1, "session", 1L, null, SessionState.IDLE, 1L, 1L, 0L, 0L, 0L, 1L, 1L,
                "", Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), "cert", 1, "GENESIS", "GENESIS", null);
        SessionRecord legacy = legacyBase.withRecordHash(legacyBase.contentHash());
        SessionRecord migrated = legacy.migrateToCurrent(null);

        assertEquals(SessionRecord.CURRENT_SCHEMA_VERSION, migrated.getSchemaVersion());
        assertEquals(legacy.getSequence() + 1L, migrated.getSequence());
        assertArrayEquals(legacy.getRecordHash(), migrated.getPreviousRecordHash());
        assertNotEquals(hex(legacy.getRecordHash()), hex(migrated.contentHash()));
    }
}
