package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Job rejected by workstation. Message sent back to the server.
 *
 * @param jobId  id of job
 * @param reason reason of failure.
 */
public record JobRejected(JobId jobId, String reason) implements Message {
}