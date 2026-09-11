package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Record for querying job status.
 *
 * @param jobId id of job to query the status of.
 */
public record JobStatusQuery(JobId jobId) implements Message {
}