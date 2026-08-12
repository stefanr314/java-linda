package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.WorkstationInfo;

/**
 * Picks which free workstation should receive the next piece of work.
 *
 * <p>This is a deliberately global decision, made once here rather than
 * independently by each per-connection handler thread, so that concurrent
 * {@code eval()} calls across different jobs don't overload the same
 * workstation.
 */
public final class Scheduler {

    /**
     * Selects a free workstation to run the next {@code eval()} task on.
     *
     * @return the chosen workstation's info
     */
    public WorkstationInfo selectFreeWorkstation() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /**
     * Registers a workstation as available for scheduling.
     *
     * @param info the workstation's static description
     */
    public void registerWorkstation(WorkstationInfo info) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /**
     * Removes a workstation from consideration, e.g. after a missed
     * heartbeat.
     *
     * @param info the workstation's static description
     */
    public void unregisterWorkstation(WorkstationInfo info) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
