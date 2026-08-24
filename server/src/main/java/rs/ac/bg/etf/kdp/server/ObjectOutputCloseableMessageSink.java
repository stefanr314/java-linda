package rs.ac.bg.etf.kdp.server;

import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectOutputStream;

/**
 * Concrete {@link CloseableMessageSink} for sending objects over the provided stream and socket.
 */
public class ObjectOutputCloseableMessageSink implements CloseableMessageSink {
	private final ObjectOutputStream out;
	private final Closeable socket;

	private final Object writeLock = new Object();  // private inner lock, to prevent outer lock holdings


	public ObjectOutputCloseableMessageSink(ObjectOutputStream out, Closeable socket) {
		this.out = out;
		this.socket = socket;
	}

	@Override
	public void send(Object message) throws IOException {
		synchronized (writeLock) {
			out.writeObject(message);
			// Clears the back-reference table. Without it, a message equal to one sent earlier goes
			// out as a mere back-reference and the peer sees the stale object; the table also grows
			// without bound on a long-lived connection.

			out.reset();
			out.flush();
		}
	}

	@Override
	public void close() {
		try {
			socket.close();  // idempotent operation as required
		} catch (IOException ignored) {
			// here the exception is ignored since close on Closeable is required to be idempotent
		}
	}
}