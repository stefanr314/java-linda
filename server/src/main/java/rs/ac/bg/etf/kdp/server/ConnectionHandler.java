package rs.ac.bg.etf.kdp.server;

import java.net.Socket;

/**
 * Handles a single accepted socket connection: reads
 * {@link rs.ac.bg.etf.kdp.common.protocol.Message}s off the wire, applies
 * them to the {@link TupleSpace} of the job the connection's
 * {@link rs.ac.bg.etf.kdp.common.protocol.Hello} named, and writes back
 * replies.
 *
 * <p>Instances run on the server's single cached thread pool alongside
 * every other connection, of every job; there is no per-job pool.
 */
public final class ConnectionHandler implements Runnable {

    private final Socket socket;
    private final JobRegistry jobRegistry;

    public ConnectionHandler(Socket socket, JobRegistry jobRegistry) {
        this.socket = socket;
        this.jobRegistry = jobRegistry;
    }

    @Override
    public void run() {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
