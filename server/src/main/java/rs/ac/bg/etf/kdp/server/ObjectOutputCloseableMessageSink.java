package rs.ac.bg.etf.kdp.server;

import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Concrete {@link CloseableMessageSink} for sending objects over the provided stream and socket.
 */
public class ObjectOutputCloseableMessageSink implements CloseableMessageSink {
	private final ObjectOutputStream out;
	private final Closeable socket;

	/*
	Write lock is now a fair lock. Fairness price is okay since not many writers will contest on it frequently (the
	writing process itself is more expensive than fairness mechanism).
	 */
	private final ReentrantLock writeLock = new ReentrantLock(true);


	public ObjectOutputCloseableMessageSink(ObjectOutputStream out, Closeable socket) {
		this.out = out;
		this.socket = socket;
	}

	@Override
	public void send(Object message) throws IOException {
		writeLock.lock();
		try {
			out.writeObject(message);
			// Clears the back-reference table. Without it, a message equal to one sent earlier goes
			// out as a mere back-reference and the peer sees the stale object; the table also grows
			// without bound on a long-lived connection.

			out.reset();
			out.flush();
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public void close() {
		try {
			socket.close();  // idempotent operation as required (performed on Socket instance)
		} catch (IOException ignored) {
			// here the exception is ignored since close on Closeable is required to be idempotent
		}
	}
}