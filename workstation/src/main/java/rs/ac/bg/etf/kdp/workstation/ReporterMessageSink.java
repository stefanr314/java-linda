package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.protocol.JobFinished;
import rs.ac.bg.etf.kdp.common.protocol.JobRunning;

import java.io.IOException;

/**
 * Class that only knows how to send the objects over the net and has behaviour defined with the {@link JobReporter}
 * interface. Server as concrete reporter used by {@link JobExecutor}.
 */
public class ReporterMessageSink implements JobReporter {
	private final MessageSink sink;
	private final FileChunkSender fileChunkSender;

	public ReporterMessageSink(MessageSink sink) {
		this.sink = sink;
		this.fileChunkSender = new FileChunkSender(this.sink);
	}

	@Override
	public void running(JobId jobId) throws IOException {
		sink.send(new JobRunning(jobId));
	}

	@Override
	public void finished(CollectedResults collectedResults) throws IOException {
		sink.send(new JobFinished(collectedResults.jobId()));

		// delegate to structure that knows how to read files and write object to channel
		fileChunkSender.acceptResultsAndSend(collectedResults);
	}

	@Override
	public void failed(JobId jobId, String reason) {

	}
}