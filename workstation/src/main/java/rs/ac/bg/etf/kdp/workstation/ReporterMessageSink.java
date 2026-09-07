package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.protocol.FileChunk;
import rs.ac.bg.etf.kdp.common.protocol.JobFailed;
import rs.ac.bg.etf.kdp.common.protocol.JobRunning;
import rs.ac.bg.etf.kdp.common.protocol.OutputFilesEnd;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class that only knows how to send the objects over the net and has behaviour defined with the {@link JobReporter}
 * interface. Server as concrete reporter used by {@link JobExecutor}.
 * <p>Class must be <em>thread safe</em></p>
 */
public non-sealed class ReporterMessageSink implements JobReporter {

	private static final Logger LOGGER = Logger.getLogger(ReporterMessageSink.class.getName());

	private final MessageSink sink;

	public ReporterMessageSink(MessageSink sink) {
		this.sink = sink;
	}

	@Override
	public void running(JobId jobId) {
		trySend(new JobRunning(jobId), "running of job with id: " + jobId.value());
	}

	@Override
	public void finished(JobId jobId) {
		trySend(new JobRunning(jobId), "finish of job with id: " + jobId.value());
	}

	@Override
	public void failed(JobId jobId, String reason) {
		trySend(new JobFailed(jobId, reason), "failure of job with id: " + jobId);
	}

	@Override
	public void sendChunk(FileChunk chunk) throws IOException {
		// The only reporting method that propagates: a dead connection mid-transfer must stop the
		// sender, whereas a lost status report has nothing left to achieve.
		sink.send(chunk);
	}

	@Override
	public void outputFilesEnd(JobId jobId, List<String> deliveredFiles) {
		trySend(new OutputFilesEnd(jobId, deliveredFiles), "end of results for " + jobId);
	}

	private void trySend(Object message, String what) {
		try {
			sink.send(message);
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Could not report " + what
					+ "; the control connection is gone", e);
		}
	}
}