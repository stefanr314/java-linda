package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobStatus;

/**
 * Record that serves as response to clients when job has not reached terminal state.
 *
 * @param jobId
 * @param actualStatus
 */
public record JobNotTerminated(JobId jobId, JobStatus actualStatus) implements Message {
}