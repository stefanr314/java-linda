package rs.ac.bg.etf.kdp.server;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only record of every job the server has ever seen: arrival time,
 * job number, assigned workstation name, completion time, and current
 * status.
 */
public final class JobLog {

	//FIXME appending is not enough

	private final List<JobContext> entries = new CopyOnWriteArrayList<>();

	/**
	 * Appends a new entry to the log. Existing entries are never modified
	 * or removed.
	 *
	 * @param entry the entry to append
	 */
	public void append(JobContext entry) {
		entries.add(entry);
	}

	/**
	 * Returns an immutable snapshot of every entry appended so far, in
	 * append order.
	 *
	 * @return the full log
	 */
	public List<JobContext> entries() {
		return List.copyOf(entries);
	}
}