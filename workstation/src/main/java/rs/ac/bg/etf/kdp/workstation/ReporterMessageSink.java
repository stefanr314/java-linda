package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.protocol.JobRunning;

import java.io.IOException;

public class ReporterMessageSink implements JobReporter {
	private final MessageSink sink;

	public ReporterMessageSink(MessageSink sink) {
		this.sink = sink;
	}

	@Override
	public void running(JobId jobId) throws IOException {
		sink.send(new JobRunning(jobId));
	}

	@Override
	public void finished(JobId jobId, String results) {

	}

	@Override
	public void failed(JobId jobId, String reason) {

	}
}