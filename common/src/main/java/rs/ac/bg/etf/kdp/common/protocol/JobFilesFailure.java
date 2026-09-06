package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Record for terminating the input files transport either to or from server. Both client and station know how to
 * react to it.
 *
 * @param jobId  id of job that gets dismissed
 * @param reason the reason of failure
 */
public record JobFilesFailure(JobId jobId, String reason) implements Message {
}