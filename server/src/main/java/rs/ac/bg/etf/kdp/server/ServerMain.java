package rs.ac.bg.etf.kdp.server;

/**
 * Entry point for the central server: accepts connections from
 * {@code linda-client} and {@code workstation} processes and dispatches
 * each to a {@link ConnectionHandler} on a single, shared cached thread
 * pool.
 */
public final class ServerMain {

    private ServerMain() {
    }

    public static void main(String[] args) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
