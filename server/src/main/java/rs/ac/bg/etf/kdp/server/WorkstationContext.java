package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.FileChunkSender;
import rs.ac.bg.etf.kdp.common.WorkstationInfo;
import rs.ac.bg.etf.kdp.common.protocol.InputFilesEnd;
import rs.ac.bg.etf.kdp.common.protocol.InputFilesStart;
import rs.ac.bg.etf.kdp.common.protocol.JobFilesFailure;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class WorkstationContext {
	private final WorkstationInfo info;
	private final CloseableMessageSink messageSink;

	private final ExecutorService asyncWriter = Executors.newSingleThreadExecutor(
			r -> {
				Thread t = new Thread(r, "async-writer");

				t.setDaemon(true);
				return t;
			}
	);

	/*
	Separate threads for writing of file chunks to socket.
	 */
	private final ExecutorService chunkWriters = Executors.newFixedThreadPool(
			2,
			(runner) -> {
				Thread thread = new Thread(
						runner,
						"file-chunk-writer-" + UUID.randomUUID().getLeastSignificantBits());

				thread.setDaemon(true);

				return thread;
			}
	);

	private final AtomicInteger availableSlots;

	// NOTE: these two fields are not required to be atomically manipulated since only one thread (ws handler) will
	// write to them - just a good guarantee if number of writers change in future. Volatile long will do the same
	private final AtomicLong reportedNanoTime = new AtomicLong(System.nanoTime());
	private final AtomicLong roundTripTime = new AtomicLong(0L);

	public WorkstationContext(WorkstationInfo info, CloseableMessageSink messageSink) {
		this.info = Objects.requireNonNull(info);
		this.messageSink = messageSink;

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
		messageSink.send(message);
	}

	/**
	 * <em>Fire and forget</em>. Asynchronously send the message using the single thread executor. Used by heartbeat
	 * mechanism since sending must not be blocking (even theoretically).
	 *
	 * @param message message to sent to object channel.
	 */
	public void sendAsync(Object message) {
		asyncWriter.execute(() -> {
			try {
				send(message);
			} catch (IOException stationDead) {
				disconnect();  // broken socket -> gone station
			}
		});
	}

	public void sendFileChunks(Runnable chunkSender) {
		chunkWriters.execute(chunkSender);
	}

	public void disconnect() {
		messageSink.close();
		chunkWriters.shutdownNow(); // station is dead so no use of writer thread - visible immediately to
		// writers since interrupt flag is polled
		asyncWriter.shutdownNow();
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

	public boolean staleTimeoutElapsed(long timeoutNanos) {
		return System.nanoTime() - this.reportedNanoTime.get() > timeoutNanos;
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

	// NOTE: this method is package private so the heartbeat mechanism must live in the same package as the
	// workstation context; letting this method be public might be too dangerous.
	void reportAt(long nanoTime) {
		this.reportedNanoTime.set(nanoTime);
	}

	void recordRTT(long roundTripTime) {
		this.roundTripTime.set(roundTripTime);
	}

	public long roundTripTime() {
		return roundTripTime.get();
	}
}