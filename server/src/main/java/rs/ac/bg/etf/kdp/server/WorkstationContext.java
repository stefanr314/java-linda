package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.WorkstationInfo;

import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class WorkstationContext {
	private final WorkstationInfo info;
	private final Closeable socket;
	private final ObjectOutputStream out;

	private final Object writeLock = new Object();  // keeping it private so callers can't acquire lock

	private final AtomicInteger availableSlots;

	// NOTE: these two fields are not required to be atomically manipulated since only one thread (ws handler) will
	// write to them - just a good guarantee if number of writers change in future. Volatile long will do the same
	private final AtomicLong reportedNanoTime = new AtomicLong(System.nanoTime());
	private final AtomicLong roundTripTime = new AtomicLong(0L);

	public WorkstationContext(WorkstationInfo info, Closeable socket, ObjectOutputStream out) {
		Objects.requireNonNull(info);

		this.info = info;
		this.socket = socket;
		this.out = out;

		this.availableSlots = new AtomicInteger(info.parallelJobCapacity());
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


	public boolean tryAcquireSlot() {
		// atomically try to decrease value if available slots > 0

		int previous = availableSlots.getAndUpdate(free -> free > 0 ? free - 1 : free);
		return previous > 0;
	}

	// NOTE: needed upon receiving job finished, job aborted, job failed...
	public void releaseSlot() {
		availableSlots.getAndUpdate(value -> Math.min(value + 1, this.info.parallelJobCapacity()));
	}

	public int availableSlots() {
		return this.availableSlots.get();
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
		return System.nanoTime() - this.reportedNanoTime.get() > timeoutNanos;
	}
	// NOTE: this method is package private so the heartbeat mechanism must live in the same package as the
	// workstation context; letting this method be public might be too dangerous.

	void reportAt(long nanoTime) {
		this.reportedNanoTime.set(nanoTime);
	}

	public void recordRTT(long roundTripTime) {
		this.roundTripTime.set(roundTripTime);
	}

	public long roundTripTime() {
		return roundTripTime.get();
	}
}