package rs.ac.bg.etf.kdp.common;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states of a job, as tracked by the server's job registry and
 * append-only job log.
 */
public enum JobStatus {

	/**
	 * Submitted, waiting to be assigned a workstation.
	 */
	READY,

	/**
	 * Assigned to a workstation, not yet started.
	 */
	SCHEDULED,

	/**
	 * Actively executing on a workstation.
	 */
	RUNNING,

	/**
	 * Finished successfully.
	 */
	DONE,

	/**
	 * Finished with an error.
	 */
	FAILED,

	/**
	 * Terminated before completion at the user's request.
	 */
	ABORTED;

	static {
		READY.possibleNext = EnumSet.of(JobStatus.SCHEDULED, JobStatus.ABORTED);
		SCHEDULED.possibleNext = EnumSet.of(JobStatus.RUNNING, JobStatus.FAILED, JobStatus.ABORTED, JobStatus.READY);

		RUNNING.possibleNext = EnumSet.of(JobStatus.DONE, JobStatus.FAILED, JobStatus.ABORTED, JobStatus.READY);

		DONE.possibleNext = EnumSet.noneOf(JobStatus.class);
		FAILED.possibleNext = EnumSet.noneOf(JobStatus.class);
		ABORTED.possibleNext = EnumSet.noneOf(JobStatus.class);
	}

	private Set<JobStatus> possibleNext;

	public boolean canAdvanceTo(JobStatus next) {
		return possibleNext.contains(next);
	}

	public boolean isTerminal() {
		return possibleNext.isEmpty();
	}
}