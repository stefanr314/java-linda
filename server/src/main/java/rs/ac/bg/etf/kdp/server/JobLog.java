package rs.ac.bg.etf.kdp.server;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobStatus;

/**
 * Append-only record of every job the server has ever seen: arrival time,
 * job number, assigned workstation name, completion time, and current
 * status.
 */
public final class JobLog {

    /**
     * One append-only entry in the log.
     *
     * @param jobId            the job this entry describes
     * @param arrivalTime      when the job was submitted
     * @param workstationName  the workstation it ran on, once assigned
     * @param completionTime   when the job finished, once it has
     * @param status           the job's status as of this entry
     */
    public record LogEntry(JobId jobId, Instant arrivalTime, String workstationName, Instant completionTime,
            JobStatus status) {
    }

    private final List<LogEntry> entries = new CopyOnWriteArrayList<>();

    /**
     * Appends a new entry to the log. Existing entries are never modified
     * or removed.
     *
     * @param entry the entry to append
     */
    public void append(LogEntry entry) {
        entries.add(entry);
    }

    /**
     * Returns an immutable snapshot of every entry appended so far, in
     * append order.
     *
     * @return the full log
     */
    public List<LogEntry> entries() {
        return List.copyOf(entries);
    }
}
