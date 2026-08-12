package rs.ac.bg.etf.kdp.common;

import java.io.Serializable;

/**
 * Outcome of a finished job, as fetched by the {@code client} module.
 *
 * @param jobId       the job this result belongs to
 * @param status      the final status; one of {@link JobStatus#DONE},
 *                    {@link JobStatus#FAILED} or {@link JobStatus#ABORTED}
 * @param exitCode    the exit code of the job's OS process
 * @param outputFiles paths to the output files produced by the job
 */
public record JobResult(JobId jobId, JobStatus status, int exitCode, java.util.List<String> outputFiles)
        implements Serializable {
}
