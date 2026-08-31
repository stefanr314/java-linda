package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;
import rs.ac.bg.etf.kdp.common.JobStatus;
import rs.ac.bg.etf.kdp.common.exceptions.JobNotPresentInRegistryException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-wide table of all known jobs, {@code Map<JobId, JobContext>}.
 */
public final class JobRegistry {

	private static final Logger LOGGER = Logger.getLogger(JobRegistry.class.getName());

	private final Map<JobId, JobContext> jobs = new ConcurrentHashMap<>();

	// required by specification
	private final AtomicLong jobCounter = new AtomicLong();
	private final JobLog jobLog;

	public JobRegistry(JobLog jobLog) {
		this.jobLog = Objects.requireNonNull(jobLog);
	}

	/**
	 * Creates and registers a fresh {@link JobContext} for {@code jobId}.
	 *
	 * @param jobId the job to register
	 * @return context of job to be sent back to the client
	 */
	public JobContext register(JobId jobId, UserContext userContext, JobSpec spec) {
		Objects.requireNonNull(jobId);
		Objects.requireNonNull(userContext);
		Objects.requireNonNull(spec);

		JobContext context = new JobContext(jobId, userContext, spec, jobCounter.getAndIncrement());
		jobs.put(jobId, context);

		jobLog.append(context);

		LOGGER.log(Level.INFO, "Registered {0}", context);
		return context;  // NOTE: handy later for the client return type
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
		Objects.requireNonNull(jobId);

		jobs.remove(jobId);
	}

	/**
	 * Method for returning ready jobs with respect to FIFO based on arrived time.
	 *
	 * @return list of FIFO ready jobs based on arrived times.
	 */
	public List<JobContext> readyJobs() {
		return jobs.values().stream()
				.filter(context -> context.status() == JobStatus.READY)
				.sorted(Comparator.comparing(JobContext::arrivedAt))
				.toList();
	}

	/**
	 * Method for checking active (i.e. non-terminal) jobs on workstation. This is the only place that connects jobs
	 * and workstations.
	 *
	 * @param workstationHostname station to look for jobs
	 * @return list of active (non-terminal) jobs on station
	 */
	public List<JobContext> activeJobsOn(String workstationHostname) {
		return jobs.values().stream()
				.filter(ctx -> ctx.assignedWorkstations().contains(workstationHostname))
				.filter(ctx -> !ctx.status().isTerminal())
				.toList();
	}


	public void assignedTo(JobId jobId, String hostName) {
		getContext(jobId).assignNewWorkstation(hostName);
		jobLog.append(getContext(jobId));
	}


	// this method has some side effects
	public void aborted(JobId jobId) {
		// performing the cleanup
	}

	public boolean scheduled(JobId jobId) {
		return transit(jobId, JobStatus.SCHEDULED);
	}

	// method that should change status from scheduled to ready
	public void requeued(JobId jobId) {
		// TODO count the number of tries to assign the particular job to the station - just give up after hitting
		//  the threshold
		transit(jobId, JobStatus.READY);
	}

	public void running(JobId jobId) {
		// todo: ws info is required for performing the write to set connections.
		transit(jobId, JobStatus.RUNNING);
	}

	// private method for changing the status of jobs -> must be thread safe -> delegated to stack confinement and
	// atomic operations on collaborators
	private boolean transit(JobId jobId, JobStatus next) {
		JobContext context = getContext(jobId);

		if (!context.tryChangeStatus(next)) {
			return false;
		}

		// conduct some side effect logic before returning
		jobLog.append(context);

		return true;
	}

	private JobContext getContext(JobId jobId) {
		JobContext context;
		if ((context = jobs.get(Objects.requireNonNull(jobId))) == null) {
			throw new JobNotPresentInRegistryException(jobId);
		}
		return context;
	}
}