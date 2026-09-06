package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Sent form server to station when job could not be found in job registry.
 *
 * @param jobId id of missing job.
 */
public record JobNotPresent(JobId jobId) implements Message {
}