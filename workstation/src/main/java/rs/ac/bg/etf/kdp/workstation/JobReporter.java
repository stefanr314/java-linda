package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.JobId;

import java.io.IOException;

public interface JobReporter {

	void running(JobId jobId) throws IOException;

	void finished(JobId jobId, String results);

	void failed(JobId jobId, String reason);
}