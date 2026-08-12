package rs.ac.bg.etf.kdp.common;

/**
 * Lifecycle states of a job, as tracked by the server's job registry and
 * append-only job log.
 */
public enum JobStatus {

    /** Submitted, waiting to be assigned a workstation. */
    READY,

    /** Assigned to a workstation, not yet started. */
    SCHEDULED,

    /** Actively executing on a workstation. */
    RUNNING,

    /** Finished successfully. */
    DONE,

    /** Finished with an error. */
    FAILED,

    /** Terminated before completion at the user's request. */
    ABORTED
}
