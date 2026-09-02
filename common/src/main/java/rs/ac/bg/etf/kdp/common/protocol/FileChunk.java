package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

public record FileChunk(JobId jobId, String fileName, int sequence,
						byte[] data, boolean last) implements Message {
}