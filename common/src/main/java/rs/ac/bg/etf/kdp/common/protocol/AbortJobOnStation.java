package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Sent from server to workstation to request that a job's process be killed.
 *
 * @param jobId id of job to abort.
 */
public record AbortJobOnStation(JobId jobId) implements Message {
}
