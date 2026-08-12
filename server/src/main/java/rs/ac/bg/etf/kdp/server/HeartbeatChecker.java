package rs.ac.bg.etf.kdp.server;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Periodically verifies that every registered workstation is still alive,
 * so the {@link Scheduler} never hands work to one that has gone away.
 */
public final class HeartbeatChecker {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final long intervalSeconds;

    /**
     * @param intervalSeconds how often, in seconds, to check every
     *                        workstation's liveness
     */
    public HeartbeatChecker(long intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    /** Starts the periodic check. */
    public void start() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /** Stops the periodic check. */
    public void stop() {
        scheduler.shutdownNow();
    }

    private void checkOnce() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    long intervalSeconds() {
        return intervalSeconds;
    }
}
