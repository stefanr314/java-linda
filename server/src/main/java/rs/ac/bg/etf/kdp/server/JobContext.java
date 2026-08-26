package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobStatus;
import rs.ac.bg.etf.kdp.common.WorkstationInfo;

import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
	private final TupleSpace tupleSpace = new TupleSpace();
	private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
	private final Set<WorkstationInfo> assignedWorkstations = ConcurrentHashMap.newKeySet();
	private volatile JobStatus status = JobStatus.READY;
	private volatile boolean stationActive = true;  // HB will affect this value if station is not active anymore

	//TODO timestamp of job

	// TODO: perhaps leave here the reference to the User context to write to it's channel

	public JobContext(JobId jobId) {
		this.jobId = jobId;
	}

	public JobId jobId() {
		return jobId;
	}

	public TupleSpace tupleSpace() {
		return tupleSpace;
	}

	public Set<Socket> connections() {
		return Set.copyOf(connections);
	}

	public Set<WorkstationInfo> assignedWorkstations() {
		return Set.copyOf(assignedWorkstations);
	}

	public JobStatus status() {
		return Enum.valueOf(JobStatus.class, status.toString()); //TODO check me
	}

	public void setStatus(JobStatus status) {
		this.status = status;
	}
}