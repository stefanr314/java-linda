package rs.ac.bg.etf.kdp.server;

import java.io.IOException;

// NOTE USE OF CLIENT CONTEXT YIELDS THE USE OF CLIENT REGISTRY ?? not quite its thread confined rather
public final class UserContext {
	private final String user;
	private final CloseableMessageSink messageSink;

	public UserContext(CloseableMessageSink messageSink, String user) {
		this.messageSink = messageSink;
		this.user = user;
	}

	public void send(Object message) throws IOException {
		// this means that the connection is closed at this moment
		// so just change the job status to FAILED and signal processes to stop execution of that JOB ID
		// however that is not the responsibility of user context so just bubble the exception
		messageSink.send(message);
	}

	public void disconnect() {
		messageSink.close();
	}
}