package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Record for querying job result state.
 *
 * @param jobId id of job.
 */
public record JobResultQuery(JobId jobId) implements Message {
}