package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.WorkstationInfo;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;

public final class WorkstationContext {
	private final WorkstationInfo info;
	private final Socket socket;
	private final ObjectOutputStream out;

	private final Object writeLock = new Object();  // keeping it private so callers can't acquire lock

	private volatile long reportedNanoTime;
	private volatile long roundTripTime;

	public WorkstationContext(WorkstationInfo info, Socket socket, ObjectOutputStream out) {
		this.info = info;
		this.socket = socket;
		this.out = out;
		this.reportedNanoTime = System.nanoTime();
	}

	/**
	 * Sending messages to workstations is done with the help of this method. This method must provide
	 * synchronization since multiple thread have obligations to write to workstations.
	 *
	 * <p>
	 * Reset is conducted to prevent out of memory exceptions and sending stale data due to internal cache
	 * mechanism.
	 * </p>
	 *
	 * @param message object/message to be written/sent.
	 * @throws IOException regular exceptions when working with I/O streams (socket in this case).
	 */
	public void send(Object message) throws IOException {
		synchronized (writeLock) {
			out.writeObject(message);

			out.reset();
			out.flush();
		}
	}

	public void disconnect() {
		try {
			socket.close();
		} catch (IOException ignored) {
			// ignore it
		}
	}

	public WorkstationInfo workstationInfo() {
		return info;  // direct reference fine since its record class
	}

	public String hostName() {
		return info.hostName();
	}

	@Override
	public String toString() {
		return info.toString();
	}

	public boolean staleTimeoutElapsed(long timeoutNanos) {
		return System.nanoTime() - this.reportedNanoTime > timeoutNanos;
	}

	// NOTE: this method is package private so the heartbeat mechanism must live in the same package as the
	// workstation context; letting this method be public might be too dangerous.
	void reportAt(long nanoTime) {
		this.reportedNanoTime = nanoTime;
	}

	public void recordRTT(long roundTripTime) {
		this.roundTripTime = roundTripTime;
	}

	public long roundTripTime() {
		return roundTripTime;
	}
}