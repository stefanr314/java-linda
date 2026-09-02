package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;

import java.nio.file.Path;

public record CollectedResults(JobId jobId, JobSpec spec, Path workDir) {
}