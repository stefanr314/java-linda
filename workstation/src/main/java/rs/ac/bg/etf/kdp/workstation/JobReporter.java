package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Reports job progress <em>to the server</em>. Best-effort by design: every method swallows transport
 * failures, because a supervising thread has no way to act on one &mdash; the connection it would use
 * to complain is the connection that just died. Losing the control connection kills the whole
 * workstation anyway, and that is handled by the control loop, not here.
 */
public sealed interface JobReporter permits ReporterMessageSink {

	/**
	 * Report running status of job.
	 *
	 * @param jobId job id that gets to running state.
	 */
	void running(JobId jobId);

	/**
	 * Report finished status of job. This method (indirectly) sends results back to server.
	 *
	 * @param results collected results with all metadata.
	 */
	void finished(CollectedResults results);

	/**
	 * Report failed status of job.
	 *
	 * @param jobId  id of failed job.
	 * @param reason reason of failure.
	 */
	void failed(JobId jobId, String reason);
}