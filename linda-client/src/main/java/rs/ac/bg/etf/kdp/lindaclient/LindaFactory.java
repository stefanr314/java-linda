package rs.ac.bg.etf.kdp.lindaclient;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.Linda;

/**
 * Entry point used by user job code to obtain a {@link Linda} handle. Acts as the Singleton instance on job.
 *
 * <p>The workstation launches every job as a separate JVM via
 * {@code ProcessBuilder}, passing {@code -Dlinda.host=}, {@code
 * -Dlinda.port=} and {@code -Dlinda.job=} system properties. {@link #get()}
 * reads those properties and lazily connects a {@link LindaProxy} to the
 * server on first use.
 */
public final class LindaFactory {

    private static final String HOST_PROPERTY = "linda.host";
    private static final String PORT_PROPERTY = "linda.port";
    private static final String JOB_PROPERTY = "linda.job";

    private static volatile Linda instance;

    private LindaFactory() {
    }

    /**
     * Returns the {@link Linda} handle for the current job, creating it on
     * first call from the {@code linda.host}/{@code linda.port}/{@code
     * linda.job} system properties.
     *
     * @return the process-wide {@link Linda} instance
     * @throws IllegalStateException if the required system properties are
     *         not set
     */
    public static Linda get() {
        Linda result = instance;

        if (result == null) {
            synchronized (LindaFactory.class) {
                result = instance;

                if (result == null) {
                    result = instance = createFromSystemProperties();
                }
            }
        }

        return result;
    }

    private static Linda createFromSystemProperties() {
        String host = System.getProperty(HOST_PROPERTY);
        String portValue = System.getProperty(PORT_PROPERTY);
        String jobValue = System.getProperty(JOB_PROPERTY);

        if (host == null || portValue == null || jobValue == null) {
            throw new IllegalStateException(
                    "system properties " + HOST_PROPERTY + ", " + PORT_PROPERTY + " and " + JOB_PROPERTY
                            + " must all be set");
        }

        return new LindaProxy(host, Integer.parseInt(portValue), new JobId(jobValue));
    }
}