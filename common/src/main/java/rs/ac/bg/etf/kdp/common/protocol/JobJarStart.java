package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

public record JobJarStart(JobId jobId, String jobJarFilename) implements Message {
}