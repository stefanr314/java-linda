package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Record for sending the failed status of job with a reason of failure.
 *
 * @param jobId  id of job
 * @param reason reason of failure.
 */
public record JobFailed(JobId jobId, String reason) implements Message {
}