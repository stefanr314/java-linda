package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

public record ReadyToAcceptInputFiles(JobId jobId) implements Message {
}