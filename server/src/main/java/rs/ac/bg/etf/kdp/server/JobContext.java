package rs.ac.bg.etf.kdp.server;

import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobStatus;
import rs.ac.bg.etf.kdp.common.WorkstationInfo;

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
        return connections;
    }

    public Set<WorkstationInfo> assignedWorkstations() {
        return assignedWorkstations;
    }

    public JobStatus status() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }
}
