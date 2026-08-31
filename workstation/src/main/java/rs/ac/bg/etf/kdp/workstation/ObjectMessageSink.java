package rs.ac.bg.etf.kdp.workstation;

import java.io.IOException;
import java.io.ObjectOutputStream;

public final class ObjectMessageSink implements MessageSink {
	private final ObjectOutputStream out;
	private final Object senderLock = new Object();

	public ObjectMessageSink(ObjectOutputStream out) {
		this.out = out;
	}

	@Override
	public void send(Object message) throws IOException {
		synchronized (senderLock) {
			out.writeObject(message);
			out.reset();
			out.flush();
		}
	}
}