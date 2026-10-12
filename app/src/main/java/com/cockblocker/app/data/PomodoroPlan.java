package com.cockblocker.app.data;

/** Immutable, bounded settings for one signed focus/break sequence or straight timer. */
public final class PomodoroPlan {
    public static final int MIN_FOCUS_MINUTES = 1;
    public static final int MAX_FOCUS_MINUTES = 90;
    public static final int MAX_STRAIGHT_FOCUS_MINUTES = 12 * 60;
    public static final int MIN_BREAK_MINUTES = 1;
    public static final int MAX_BREAK_MINUTES = 30;
    public static final int MIN_SESSIONS = 1;
    public static final int MAX_SESSIONS = 12;

    private final long focusDurationMs;
    private final long breakDurationMs;
    private final int totalSessions;
    private final int currentSession;

    public PomodoroPlan(long focusDurationMs, long breakDurationMs,
                        int totalSessions, int currentSession) {
        boolean straight = breakDurationMs == 0L
                && totalSessions == 1
                && currentSession == 1;
        long maximumFocus = straight
                ? minutesToMillis(MAX_STRAIGHT_FOCUS_MINUTES)
                : minutesToMillis(MAX_FOCUS_MINUTES);
        boolean validFocus = focusDurationMs >= minutesToMillis(MIN_FOCUS_MINUTES)
                && focusDurationMs <= maximumFocus;
        boolean validBreak = straight || (breakDurationMs >= minutesToMillis(MIN_BREAK_MINUTES)
                && breakDurationMs <= minutesToMillis(MAX_BREAK_MINUTES));
        boolean validSessions = totalSessions >= MIN_SESSIONS
                && totalSessions <= MAX_SESSIONS
                && currentSession >= 1
                && currentSession <= totalSessions;
        if (!validFocus || !validBreak || !validSessions) {
            throw new IllegalArgumentException("invalid focus plan");
        }
        this.focusDurationMs = focusDurationMs;
        this.breakDurationMs = breakDurationMs;
        this.totalSessions = totalSessions;
        this.currentSession = currentSession;
    }

    public static PomodoroPlan initial(int focusMinutes, int breakMinutes, int totalSessions) {
        return new PomodoroPlan(minutesToMillis(focusMinutes), minutesToMillis(breakMinutes),
                totalSessions, 1);
    }

    public static PomodoroPlan straight(int focusMinutes) {
        return new PomodoroPlan(minutesToMillis(focusMinutes), 0L, 1, 1);
    }

    public static PomodoroPlan legacy(long focusDurationMs) {
        int minutes = (int) Math.max(MIN_FOCUS_MINUTES,
                Math.min(MAX_FOCUS_MINUTES, Math.round(focusDurationMs / 60000.0f)));
        return initial(minutes, MIN_BREAK_MINUTES, 1);
    }

    public PomodoroPlan nextFocus() {
        if (isStraight() || currentSession >= totalSessions) {
            throw new IllegalStateException("no focus sessions remain");
        }
        return new PomodoroPlan(focusDurationMs, breakDurationMs, totalSessions, currentSession + 1);
    }

    public long getFocusDurationMs() { return focusDurationMs; }
    public long getBreakDurationMs() { return breakDurationMs; }
    public int getTotalSessions() { return totalSessions; }
    public int getCurrentSession() { return currentSession; }
    public boolean isStraight() { return breakDurationMs == 0L && totalSessions == 1; }
    public boolean isPomodoro() { return !isStraight(); }
    public boolean isFinalFocus() { return isStraight() || currentSession >= totalSessions; }
    public int getFocusMinutes() { return (int) Math.round(focusDurationMs / 60000.0f); }
    public int getBreakMinutes() { return (int) Math.round(breakDurationMs / 60000.0f); }

    public static long minutesToMillis(int minutes) {
        return minutes * 60_000L;
    }

    public String getFocusLabel() {
        int minutes = getFocusMinutes();
        if (minutes < 60) {
            return minutes + " min";
        }
        int hours = minutes / 60;
        int remainder = minutes % 60;
        return remainder == 0 ? hours + " hr" : hours + "h " + remainder + "m";
    }
    @Override
    public String toString() {
        if (isStraight()) {
            return "PomodoroPlan{" + getFocusLabel() + " straight timer}";
        }
        return "PomodoroPlan{" + getFocusMinutes() + "m focus, "
                + getBreakMinutes() + "m break, " + currentSession + "/" + totalSessions + "}";
    }
}
