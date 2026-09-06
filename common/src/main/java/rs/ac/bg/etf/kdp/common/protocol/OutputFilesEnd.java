package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Marks the end of a run of result {@link FileChunk}s.
 *
 * @param jobId          the job whose results were sent
 * @param deliveredFiles names actually sent; compare against the spec to detect outputs the job
 *                       never produced
 */

public record OutputFilesEnd(JobId jobId, java.util.List<String> deliveredFiles) implements Message {
}