package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

public record JobFailed(JobId jobId, String reason) implements Message {
}