package rs.ac.bg.etf.kdp.server;

import java.io.Closeable;
import java.io.IOException;

/**
 * Sink for sending the messages (writing them to appropriate stream) and performing the close operation.
 */
public interface CloseableMessageSink extends Closeable {

	void send(Object message) throws IOException;

	@Override
	void close();
}