package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;
import rs.ac.bg.etf.kdp.common.JobStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

	private final JobId jobId;

	/*
	Tuple space for writing the data (tuples and templates). Lives on server. REQUIRED ON FOR LINDA JOBS.
	 */
	private final TupleSpace tupleSpace = new TupleSpace();

	/*
	Connection(s) to outer workstation(s) that perform the job execution - ONLY ON LINDA TASK. Must be closed on
	terminal states and disconnection of station(s).
	 */
	private final Set<CloseableMessageSink> connections = ConcurrentHashMap.newKeySet();

	private final Set<String> assignedWorkstations = ConcurrentHashMap.newKeySet();

	private final Set<String> rejectedByStations = ConcurrentHashMap.newKeySet();

	private final JobSpec spec;

	// required to save if job gets delegated from broken station to working one
	private final Object statusLock = new Object();

	// job counter received by server - serves no purpose, just required by specification of project
	private final long jobNumber;
	private final Instant arrivedAt = Instant.now();
	private final AtomicInteger rescheduledTimes = new AtomicInteger(0);

	private volatile UserContext userContext;
	private String failureReason;
	private volatile Instant completedAt;
	private volatile JobStatus status = JobStatus.RECEIVING;  // NOTE: volatile overkill if synchronization used
	private volatile boolean fileTransmissionStopped;

	public JobContext(JobId jobId, UserContext userContext, JobSpec spec, long jobCounter) {
		this.jobId = Objects.requireNonNull(jobId);
		this.userContext = Objects.requireNonNull(userContext);
		this.spec = Objects.requireNonNull(spec);
		this.jobNumber = jobCounter;
	}

	public JobId jobId() {
		return jobId;
	}

	public UserContext userContext() {
		return userContext;  // giving away context :(
	}

	void refreshUserContext(UserContext fresh) {
		Objects.requireNonNull(fresh);
		this.userContext = fresh;
	}

	public JobSpec specification() {
		return spec;
	}

	public Instant arrivedAt() {
		return arrivedAt;
	}

	public long jobNumber() {
		return jobNumber;
	}

	public Optional<Instant> completedAt() {
		return Optional.ofNullable(completedAt);
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

	void recordRejection(String workstationName) {
		Objects.requireNonNull(workstationName);

		rejectedByStations.add(workstationName);
	}

	public Set<String> rejectedBy() {
		return Set.copyOf(rejectedByStations);
	}

	void addLindaConnection(CloseableMessageSink newConnection) {
		Objects.requireNonNull(newConnection);

		connections.add(newConnection);
	}

	void removeLindaConnection(CloseableMessageSink closed) {
		Objects.requireNonNull(closed);

		connections.remove(closed);
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

	public boolean isFileTransmissionStopped() {
		return fileTransmissionStopped;
	}

	/**
	 * Method that sets the external flag to stop a transmission so the writer can see the new flag and stop file
	 * transmission. Writer thread is separated from reader thread so <em>visibility matters.</em>
	 */
	void stopFileTransmission() {
		fileTransmissionStopped = true;
	}

	/**
	 * Method for resetting transmission flag so the files can be transmitted again.
	 */
	void resetFileTransmission() {
		fileTransmissionStopped = false;
	}

	int incrementReschedulingCounter() {
		return rescheduledTimes.getAndIncrement();
	}

	void resetReschedulingCounter() {
		rescheduledTimes.set(0);
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
	 * connections wakes threads blocked in a socket read. Clearing the set of assigned workstations is mandatory.
	 * <p>
	 * If job performed is not Linda job than this method
	 * performs cleanup of assigned workstations.
	 * </p>
	 */
	void releaseResources() {
		tupleSpace.close();
		connections.forEach(CloseableMessageSink::close);
		connections.clear();
		assignedWorkstations.clear();
		rejectedByStations.clear();
	}

	/**
	 * Method for preparing the job for being run on different station. All other working nodes must be informed
	 * that job will be reset -> so just close their connections (easiest way of informing them xD).
	 * <p>Prior to closing their connections it's required to wake them up if waiting on tuple space match.
	 * {@link TupleSpace#reset()} performs that.</p>
	 */
	void prepareRequeue() {
		tupleSpace.reset();
		connections.forEach(CloseableMessageSink::close);
		connections.clear();
		assignedWorkstations.clear();
	}


	/**
	 * Method for removing station from assigned stations. Connection will be closed and removed from
	 * {@code connections} with adequate LindaHandler.
	 *
	 * @param workstationHostname name of workstation to remove
	 */
	public void removeAssignedWorkstation(String workstationHostname) {
		assignedWorkstations.remove(Objects.requireNonNull(workstationHostname));
	}

	@Override
	public String toString() {
		return "JobContext[#" + jobNumber + ", " + jobId + ", " + status
				+ (assignedWorkstations.isEmpty() ? "" : ", on " + assignedWorkstations) + "]";
	}
}