package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Sent from client to server to request that a job be aborted.
 *
 * @param jobId id of job to abort.
 */
public record AbortJobCommand(JobId jobId) implements Message {
}
