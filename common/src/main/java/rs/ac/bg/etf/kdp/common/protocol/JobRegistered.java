package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Confirmation message from server to client that a job has been received.
 */
public record JobRegistered(JobId jobId) implements Message {
}