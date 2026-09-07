package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.protocol.FileChunk;

import java.io.IOException;
import java.util.List;

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
	 * Report finished status of job.
	 *
	 * @param jobId id of finished job
	 */
	void finished(JobId jobId);

	/**
	 * Report failed status of job.
	 *
	 * @param jobId  id of failed job.
	 * @param reason reason of failure.
	 */
	void failed(JobId jobId, String reason);

	/**
	 * Method for sending the file chunks of results to server.
	 *
	 * @param chunk file chunk holding data.
	 * @throws IOException upon working with sinker.
	 */
	void sendChunk(FileChunk chunk) throws IOException;

	/**
	 * Method for sending sentinel value to notify all results sent.
	 *
	 * @param jobId          id of job which results have been sent.
	 * @param deliveredFiles all delivered files.
	 */
	void outputFilesEnd(JobId jobId, List<String> deliveredFiles);
}