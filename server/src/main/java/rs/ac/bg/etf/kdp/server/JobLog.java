package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobStatus;
import rs.ac.bg.etf.kdp.common.PathUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only record of every job the server has ever seen: arrival time,
 * job number, assigned workstation name, completion time, and current
 * status.
 */
public final class JobLog {

	private static final Logger LOGGER = Logger.getLogger(JobLog.class.getName());
	private static final Path BASE_PATH = PathUtil.getServerBasePath();

	private final List<JobLogEntry> entries = new CopyOnWriteArrayList<>();

	/**
	 * Appends a new entry to the log. Existing entries are never modified
	 * or removed.
	 *
	 * @param job the entry to append
	 */
	public void append(JobContext job, String detail) {
		JobLogEntry logEntry = new JobLogEntry(
				Instant.now(),
				job.jobNumber(),
				job.jobId(),
				job.status(),
				String.join(", ", job.assignedWorkstations()),
				detail
		);
		entries.add(logEntry);
		writeToFile(logEntry);
	}

	// writing done in record toString() form. it's ok for reading...
	private void writeToFile(JobLogEntry logEntry) {
		try {
			// rely on underlying buffer of 8kB; for these purposes it's enough; some entries might be lost in buffer
			Files.writeString(BASE_PATH.resolve("server-logs.log"),
					logEntry.toString() + System.lineSeparator(),
					StandardCharsets.UTF_8,
					StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Entry not written to log.", e);
		}
	}

	/**
	 * Returns an immutable snapshot of every entry appended so far, in
	 * append order.
	 *
	 * @return the full log
	 */
	public List<JobLogEntry> entries() {
		return List.copyOf(entries);
	}

	/**
	 * One immutable line of the audit trail. Snapshot.
	 */
	public record JobLogEntry(Instant at,
							  long jobNumber,
							  JobId jobId,
							  JobStatus status,
							  String workstation,
							  String detail) {
	}
}