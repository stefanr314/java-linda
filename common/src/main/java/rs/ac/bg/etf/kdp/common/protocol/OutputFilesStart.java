package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Marks the start of a run of result {@link FileChunk}s sent from server to client, mirroring
 * {@link InputFilesStart} on the client-to-server upload path.
 *
 * @param jobId the job whose results are about to be sent
 */
public record OutputFilesStart(JobId jobId) implements Message {
}
