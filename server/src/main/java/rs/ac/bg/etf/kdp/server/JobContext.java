package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;
import rs.ac.bg.etf.kdp.common.JobStatus;

import java.io.Closeable;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Everything the server holds for a single job: its {@link TupleSpace},
 * the set of live connections attached to it, the workstations it has
 * been assigned to, and its current status.
 *
 * <p>A job's {@code TupleSpace} is the only isolation between concurrently
 * running jobs — tags/tuples are not namespaced beyond this per-job
 * instance.
 */
public final class JobContext {

	private static final Logger LOGGER = Logger.getLogger(JobContext.class.getName());

	private final JobId jobId;

	/*
	Tuple space for writing the data (tuples and templates). Lives on server. REQUIRED ON FOR LINDA JOBS.
	 */
	private final TupleSpace tupleSpace = new TupleSpace();

	/*
	Connection(s) to outer workstation(s) that perform the job execution - ONLY ON LINDA TASK. Must be closed on
	terminal states and disconnection of station(s). TODO: consider workstation context instead
	 */
	private final Set<CloseableMessageSink> connections = ConcurrentHashMap.newKeySet();
	private final Set<String> assignedWorkstations = ConcurrentHashMap.newKeySet();

	private final UserContext userContext;
	private final JobSpec spec;
	// required to save if job gets delegated from broken station to working one
	private final Object statusLock = new Object();
	// job counter received by server - serves no purpose, just required by specification of project
	private final long jobNumber;
	private final Instant arrivedAt = Instant.now();
	private String failureReason;
	private volatile Instant completedAt;

	private volatile JobStatus status = JobStatus.READY;  // NOTE: volatile overkill if synchronization used

	public JobContext(JobId jobId, UserContext userContext, JobSpec spec, long jobCounter) {
		this.jobId = jobId;
		this.userContext = userContext;
		this.spec = spec;
		this.jobNumber = jobCounter;
	}

	public JobId jobId() {
		return jobId;
	}

	public UserContext userContext() {
		return userContext;
	}

	public JobSpec specification() {
		return spec;
	}

	public Instant arrivedAt() {
		return arrivedAt;
	}

	public Optional<Instant> completedAt() {
		return Optional.of(completedAt);
	}

	public Optional<String> failureReason() {
		return Optional.ofNullable(failureReason);
	}

	void recordFailure(String reason) {
		this.failureReason = Objects.requireNonNull(reason);
	}

	public TupleSpace tupleSpace() {
		return tupleSpace;
	}

	public Set<Closeable> connections() {
		return Set.copyOf(connections);
	}

	public Set<String> assignedWorkstations() {
		return Set.copyOf(assignedWorkstations);
	}

	void assignNewWorkstation(String hostname) {
		assignedWorkstations.add(Objects.requireNonNull(hostname));
	}

	public JobStatus status() {
		return status;
	}


	/**
	 * Method for trying to change the status if new status transition is allowed according to the
	 * {@link JobStatus#canAdvanceTo(JobStatus newJobStatus)}. If transition not allowed false value is returned.
	 *
	 * <p>
	 * Synchronization is mandatory since the method will be called by multiple threads running to change the
	 * status of job.
	 * </p>
	 *
	 * @param jobStatus new status to try setting upon.
	 * @return whether the operation managed to succeed.
	 * @author stefanr
	 */
	boolean tryChangeStatus(JobStatus jobStatus) {
		synchronized (statusLock) {
			if (status.canAdvanceTo(jobStatus)) {
				status = jobStatus;
				if (jobStatus.isTerminal()) completedAt = Instant.now();

				return true;
			} else {

				return false;
			}
		}
	}

	/**
	 * Releases everything this job holds. Both steps are required and neither substitutes for the
	 * other: closing the tuple space wakes threads parked in {@code await()}, closing the
	 * connections wakes threads blocked in a socket read.
	 * <p>
	 * If job performed is not Linda job that this method
	 * performs nothing useful.
	 * </p>
	 */
	void releaseResources() {
		tupleSpace.close();
		connections.forEach(CloseableMessageSink::close);
		connections.clear();
	}


	public void removeFailedStation(CloseableMessageSink socket, String workstationHostname) {
		connections.remove(Objects.requireNonNull(socket));
		assignedWorkstations.remove(Objects.requireNonNull(workstationHostname));
	}

	@Override
	public String toString() {
		return "JobContext[#" + jobNumber + ", " + jobId + ", " + status
				+ (assignedWorkstations.isEmpty() ? "" : ", on " + assignedWorkstations) + "]";
	}
}