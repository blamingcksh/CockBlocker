package com.cockblocker.app.platform;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

/**
 * Narrow, memory-only Usage Access fallback. Accessibility remains the primary
 * foreground signal; this reader keeps only the newest foreground package and
 * event timestamp and never retains or persists event history.
 */
public final class UsageReader {
    // The window has to cover the caller's poll interval. A caller polling every 15s with
    // a 2s window only ever observes a foreground event in the 2s immediately after the
    // app was opened, so a user who opened a blocked app and then sat still read as "no
    // observation" on every later tick and the paused-app banner silently vanished. The
    // read stays memory-only and bounded: it scans the window and keeps the newest event.
    private static final long DEFAULT_WINDOW_MS = 60_000L;
    private static final long MAX_WINDOW_MS = 60_000L;

    private final Context context;

    public UsageReader(Context context) {
        this.context = context.getApplicationContext();
    }

    public UsageObservation latestForeground() {
        return latestForeground(DEFAULT_WINDOW_MS);
    }

    public UsageObservation latestForeground(long windowMs) {
        try {
            UsageStatsManager manager = (UsageStatsManager) context.getSystemService(
                    Context.USAGE_STATS_SERVICE);
            if (manager == null) {
                return null;
            }
            long boundedWindow = Math.max(1L, Math.min(MAX_WINDOW_MS, windowMs));
            long end = System.currentTimeMillis();
            long begin = end - boundedWindow;
            UsageEvents events = manager.queryEvents(begin, end);
            if (events == null) {
                return null;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            UsageObservation newest = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                boolean foreground = type == UsageEvents.Event.MOVE_TO_FOREGROUND
                        || type == UsageEvents.Event.ACTIVITY_RESUMED;
                if (!foreground || event.getPackageName() == null) {
                    continue;
                }
                if (newest == null || event.getTimeStamp() >= newest.eventTimeMs) {
                    newest = new UsageObservation(event.getPackageName(), event.getTimeStamp());
                }
            }
            return newest;
        } catch (SecurityException ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static final class UsageObservation {
        private final String packageName;
        private final long eventTimeMs;

        public UsageObservation(String packageName, long eventTimeMs) {
            this.packageName = packageName;
            this.eventTimeMs = eventTimeMs;
        }

        public String getPackageName() {
            return packageName;
        }

        public String packageName() {
            return packageName;
        }

        public long getEventTimeMs() {
            return eventTimeMs;
        }

        public long eventTimeMs() {
            return eventTimeMs;
        }
    }
}
