package rs.ac.bg.etf.kdp.workstation;

import java.nio.file.Path;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Launches a user job as a separate OS process via {@link ProcessBuilder}.
 *
 * <p>The child is started with {@code -cp <job.jar>:<linda-client.jar>}
 * and {@code -Dlinda.host=}, {@code -Dlinda.port=}, {@code -Dlinda.job=}
 * system properties so it can obtain a {@link
 * rs.ac.bg.etf.kdp.common.Linda} handle via {@code LindaFactory.get()}.
 * Two reader threads drain the child's stdout and stderr.
 */
public final class JobProcessLauncher {

    /**
     * Starts {@code jobJar} as a child JVM wired up to talk to the server
     * about {@code jobId}.
     *
     * @param jobJar      the user job's jar file
     * @param lindaClientJar the linda-client jar, placed on the child's classpath
     * @param serverHost  the server's host name
     * @param serverPort  the server's port
     * @param jobId       the job id the child process should connect with
     * @return the started process
     */
    public Process launch(Path jobJar, Path lindaClientJar, String serverHost, int serverPort, JobId jobId) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
