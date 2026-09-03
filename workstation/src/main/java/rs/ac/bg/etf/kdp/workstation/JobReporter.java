package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Reports job progress to the server. Best-effort by design: every method swallows transport
 * failures, because a supervising thread has no way to act on one — the connection it would use
 * to complain is the connection that just died. Losing the control connection kills the whole
 * workstation anyway, and that is handled by the control loop, not here.
 */
public interface JobReporter {

	void running(JobId jobId);

	void finished(CollectedResults results);

	void failed(JobId jobId, String reason);
}