package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.JobId;

import java.io.IOException;

public interface JobReporter {

	void running(JobId jobId) throws IOException;

	void finished(CollectedResults results) throws IOException;

	void failed(JobId jobId, String reason);
}