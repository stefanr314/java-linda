package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;

public record JobSubmitCommand(JobId jobId, JobSpec jobSpec) implements Message {
}