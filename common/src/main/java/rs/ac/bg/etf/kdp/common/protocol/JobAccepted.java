package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Job accepted by workstation.
 *
 * @param jobId id of job
 */
public record JobAccepted(JobId jobId) implements Message {
}