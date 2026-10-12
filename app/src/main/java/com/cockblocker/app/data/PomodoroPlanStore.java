package com.cockblocker.app.data;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the user's editable plan; the active copy is signed into the journal on START. */
public final class PomodoroPlanStore {
    private static final String PREFS = "pomodoro_plan";
    private static final String FOCUS_MINUTES = "focus_minutes";
    private static final String BREAK_MINUTES = "break_minutes";
    private static final String SESSION_COUNT = "session_count";
    private static final String STRAIGHT_MODE = "straight_mode";

    private final SharedPreferences preferences;

    public PomodoroPlanStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public PomodoroPlan load() {
        int focus = preferences.getInt(FOCUS_MINUTES, 25);
        if (preferences.getBoolean(STRAIGHT_MODE, false)) {
            return PomodoroPlan.straight(clamp(focus, PomodoroPlan.MIN_FOCUS_MINUTES,
                    PomodoroPlan.MAX_STRAIGHT_FOCUS_MINUTES));
        }
        int pause = clamp(preferences.getInt(BREAK_MINUTES, 5),
                PomodoroPlan.MIN_BREAK_MINUTES, PomodoroPlan.MAX_BREAK_MINUTES);
        int sessions = clamp(preferences.getInt(SESSION_COUNT, 4),
                PomodoroPlan.MIN_SESSIONS, PomodoroPlan.MAX_SESSIONS);
        return PomodoroPlan.initial(clamp(focus, PomodoroPlan.MIN_FOCUS_MINUTES,
                PomodoroPlan.MAX_FOCUS_MINUTES), pause, sessions);
    }

    public void save(PomodoroPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan is required");
        }
        preferences.edit()
                .putInt(FOCUS_MINUTES, plan.getFocusMinutes())
                .putInt(BREAK_MINUTES, plan.getBreakMinutes())
                .putInt(SESSION_COUNT, plan.getTotalSessions())
                .putBoolean(STRAIGHT_MODE, plan.isStraight())
                .apply();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
