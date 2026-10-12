package com.cockblocker.app;

import android.app.Application;

import com.cockblocker.app.data.SessionRecord;
import com.cockblocker.app.data.StateRepository;
import com.cockblocker.app.service.SessionWatchdogService;
import com.cockblocker.app.trust.TrustAnchorGate;

/** Process-wide bootstrap for the signed state repository and trust gate. */
public final class CockblockerApplication extends Application {
    private StateRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        SessionWatchdogService.ensureNotificationChannel(this);
        repository = StateRepository.get(this);
        // Touch the process-wide trust checks without retaining component state.
        TrustAnchorGate.evaluateHealth(this);
        repository.initialize();
        restoreWatchdog();
    }

    /**
     * A force-stopped package is never brought back by the system, and the boot, end and
     * checkpoint alarms that normally call {@code start()} are cancelled along with it.
     * Nothing else re-armed the watchdog, and expiry is recomputed only inside the health
     * loop, so after {@code am force-stop} the session would keep blocking with no way to
     * end except clearing app data. Restoring the loop is the only thing that closes that.
     *
     * <p>Best effort on purpose: this can run in the background, where Android 12+ refuses
     * a foreground-service start. The accessibility service binding and BootReceiver remain
     * the foreground paths, and a refused start leaves the journal exactly as it was.
     */
    private void restoreWatchdog() {
        SessionRecord record;
        try {
            record = repository.read();
        } catch (RuntimeException unavailable) {
            return;
        }
        if (record == null || !record.isActivePlan()) {
            return;
        }
        try {
            SessionWatchdogService.start(this);
        } catch (RuntimeException refused) {
            // Background start refused. Nothing was released; the next foreground launch
            // or boot completes the restore.
        }
    }

    public StateRepository getRepository() {
        return repository;
    }
}
