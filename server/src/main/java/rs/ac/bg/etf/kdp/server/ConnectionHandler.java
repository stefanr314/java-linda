package rs.ac.bg.etf.kdp.server;


import java.io.IOException;

/**
 * Runs the whole conversation on one accepted connection, after the Hello has been read.
 *
 * <p>The accept-loop thread that read the Hello calls {@link #run()} directly and stays there for
 * the lifetime of the connection. It must not hand off to another pooled thread: with nobody
 * blocked in {@code readObject()}, incoming messages sit in the kernel buffer until TCP stalls.
 *
 * <p>Implementations are confined to that one thread and need no synchronization of their own.
 * State they share with the heartbeat sweep or the scheduler lives in {@link WorkstationContext}
 * and is guarded there.
 */
public interface ConnectionHandler {

	void run() throws IOException, ClassNotFoundException;
}