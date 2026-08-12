package rs.ac.bg.etf.kdp.lindaclient;

import java.net.Socket;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.Linda;

/**
 * Client-side {@link Linda} implementation. Every call is turned into a
 * request sent over a plain {@code java.net} socket to the server and
 * blocks on {@code ObjectInputStream.readObject()} for the reply; the
 * server performs the actual matching and any blocking wait. Tuples never
 * live on the client side.
 *
 * <p>Instances are obtained through {@link LindaFactory#get()}, not
 * constructed directly by user job code.
 */
public final class LindaProxy implements Linda {

    private final String host;
    private final int port;
    private final JobId jobId;
    private Socket socket;

    /**
     * Creates a proxy bound to a job's tuple space. The socket connection
     * itself is established lazily on first use.
     *
     * @param host  the server's host name
     * @param port  the server's port
     * @param jobId the job whose tuple space this proxy talks to
     */
    public LindaProxy(String host, int port, JobId jobId) {
        this.host = host;
        this.port = port;
        this.jobId = jobId;
    }

    @Override
    public void out(String[] tuple) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void in(String[] tuple) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean inp(String[] tuple) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void rd(String[] tuple) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean rdp(String[] tuple) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void eval(String name, Runnable thread) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
