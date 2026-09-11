package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Message when job has reached the aborted state.
 *
 * @param jobId id of job
 */
public record JobAborted(JobId jobId) implements Message {
}