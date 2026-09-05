package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Record for terminating the input files transport from client to server due to internal IO error on server.
 *
 * @param jobId  id of job that gets dismissed
 * @param reason the reason of failure
 */
public record JobFilesFailure(JobId jobId, String reason) implements Message {
}