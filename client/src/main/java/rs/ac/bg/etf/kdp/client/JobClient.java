package rs.ac.bg.etf.kdp.client;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobResult;
import rs.ac.bg.etf.kdp.common.JobSpec;
import rs.ac.bg.etf.kdp.common.JobStatus;

/**
 * End-user facing client used to submit jobs to the server and, later,
 * check on or retrieve them. A {@code JobClient} is not tied to a single
 * live connection: after {@link #disconnect()} the same job can be
 * followed up on via {@link #reconnect(JobId)}.
 */
public final class JobClient {

    private final String serverHost;
    private final int serverPort;

    public JobClient(String serverHost, int serverPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
    }

    /**
     * Submits a new job to the server.
     *
     * @param spec the job to submit
     * @return the id assigned to the new job
     */
    public JobId submit(JobSpec spec) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /** Closes the current connection to the server without aborting any job. */
    public void disconnect() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /**
     * Re-establishes a connection to the server for an already-submitted
     * job.
     *
     * @param jobId the job to reconnect to
     */
    public void reconnect(JobId jobId) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /**
     * Queries the current status of a job.
     *
     * @param jobId the job to query
     * @return the job's current status
     */
    public JobStatus queryStatus(JobId jobId) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /**
     * Fetches the result of a finished job.
     *
     * @param jobId the job to fetch results for
     * @return the job's result
     */
    public JobResult fetchResults(JobId jobId) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
