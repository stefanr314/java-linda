package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;

import java.nio.file.Path;

/**
 * Record depicting collected results upon workstation successfully implementing the job.
 *
 * @param jobId   id of job that results are collected of.
 * @param spec    specification of completed job.
 * @param workDir path to stored output results.
 */
public record CollectedResults(JobId jobId, JobSpec spec, Path workDir) {
}