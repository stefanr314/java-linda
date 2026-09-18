package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobStatus;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only record of every job the server has ever seen: arrival time,
 * job number, assigned workstation name, completion time, and current
 * status.
 */
public final class JobLog {

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
		// todo write me to file
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