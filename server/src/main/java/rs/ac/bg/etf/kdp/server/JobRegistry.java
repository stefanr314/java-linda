package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobStatus;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-wide table of all known jobs, {@code Map<JobId, JobContext>}.
 * This is the root object a {@link ConnectionHandler} consults to find the
 * {@link TupleSpace} it should apply an incoming operation to.
 */
public final class JobRegistry {

	private final Map<JobId, JobContext> jobs = new ConcurrentHashMap<>();

	/**
	 * Creates and registers a fresh {@link JobContext} for {@code jobId}.
	 *
	 * @param jobId the job to register
	 * @return the newly created context
	 */
	public JobContext register(JobId jobId) {
		JobContext context = new JobContext(jobId);
		jobs.put(jobId, context);
		return context;
	}

	/**
	 * Looks up the context for {@code jobId}.
	 *
	 * @param jobId the job to look up
	 * @return the context, if the job is known
	 */
	public Optional<JobContext> find(JobId jobId) {
		return Optional.ofNullable(jobs.get(jobId));
	}

	/**
	 * Removes the context for {@code jobId}.
	 *
	 * @param jobId the job to remove
	 */
	public void remove(JobId jobId) {
		jobs.remove(jobId);
	}

	// TODO: create list of ready jobs that scheduler uses

	// TODO: everything has to be thread safe
	public void abortJob(JobId jobId) {
		// performing the cleanup
	}

	// to shallow method for abortion and failing no cleanup performed - this is not right method
	public boolean changeStatusTo(JobId jobId, JobStatus newStatus) {
		JobContext found;
		if ((found = jobs.get(jobId)) == null) return false;

		found.setStatus(newStatus);
		return true;
	}
}