package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobStatus;

/**
 * Server response to job status query.
 *
 * @param jobId     id of job
 * @param jobStatus status of job.
 */
public record JobStatusResponse(JobId jobId, JobStatus jobStatus) implements Message {
}