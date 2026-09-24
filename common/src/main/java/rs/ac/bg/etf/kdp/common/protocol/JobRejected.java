package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Job rejected by workstation. Message sent back to the server.
 *
 * @param jobId    id of job.
 * @param reason   reason of failure.
 * @param capacity true if capacity request was not met and that's the reason of rejection; false otherwise.
 */
public record JobRejected(JobId jobId, String reason, boolean capacity) implements Message {
}