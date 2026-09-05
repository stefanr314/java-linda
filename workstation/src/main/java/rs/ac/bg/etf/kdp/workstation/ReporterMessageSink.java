package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.FileChunkSender;
import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.protocol.JobFailed;
import rs.ac.bg.etf.kdp.common.protocol.JobFinished;
import rs.ac.bg.etf.kdp.common.protocol.JobRunning;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
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
		try {
			sink.send(new JobRunning(jobId));
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Could not report running of " + jobId
					+ "; the control connection is gone", e);
		}
	}

	@Override
	public void finished(CollectedResults collectedResults) {
		try {
			Objects.requireNonNull(collectedResults);
			
			sink.send(new JobFinished(collectedResults.jobId()));

			JobId jobId = collectedResults.jobId();
			ArrayList<String> filenames = new ArrayList<>(collectedResults.spec().outputFiles());

			// send the loggers too
			Path logs = Path.of("logs");
			String stdoutLog = logs.resolve("stdout.log").toString();
			String stderrLog = logs.resolve("stderr.log").toString();

			filenames.add(stderrLog);
			filenames.add(stdoutLog);

			Path workDir = collectedResults.workDir();

			// delegate to structure that knows how to read files and write object to channel
			new FileChunkSender(this.sink::send).sendFiles(jobId, filenames, workDir, () -> false);
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Could not collected results; The control connection is gone. Results are " +
					"nowhere to be reported.", e);
		}

	}

	@Override
	public void failed(JobId jobId, String reason) {
		try {
			sink.send(new JobFailed(jobId, reason));
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Could not report failure of " + jobId
					+ "; the control connection is gone", e);
		}
	}
}