package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.protocol.Ping;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class that represents heartbeat daemon thread responsible for pinging the workstations on every threshold interval
 * reached (in project specification on every x seconds as declared). This thread will be aid with the use of
 * scheduled executor service.
 */
public class HeartbeatDaemon implements AutoCloseable {

	private final static Logger LOGGER = Logger.getLogger(HeartbeatDaemon.class.getName());

	private final long interval;
	private final long timeout;
	private final WorkstationRegistry workstationRegistry;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "heartbeat checker");

		t.setDaemon(true);
		return t;
	});
	private final Runnable runner = this::sweeper;

	public HeartbeatDaemon(long interval, long timeout, WorkstationRegistry workstationRegistry) {
		this.interval = interval;
		this.timeout = timeout;
		this.workstationRegistry = workstationRegistry;
	}

	public void start() {
		scheduler.scheduleWithFixedDelay(runner, 0, interval, TimeUnit.SECONDS);
	}

	private void sweeper() {
		// check for available stations
		for (WorkstationContext workstation : workstationRegistry.workstations()) {
			// check if ws is stale
			if (workstation.isStaleFor(timeout)) {

				workstation.disconnect();
				continue;
			}

			// send messages
			try {
				workstation.send(new Ping(System.currentTimeMillis()));
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Unable to send message to the workstation: " + workstation.hostName() + "." +
						" Workstation will be disconnected.");
				workstation.disconnect();
			}
		}
	}


	@Override
	public void close() {
		scheduler.shutdownNow();

		try {
			if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
				LOGGER.log(Level.WARNING, "Scheduler did not manage to close before the timeout.");
			}
		} catch (InterruptedException e) {
			scheduler.shutdownNow();  // re-try if interrupted while waiting

			Thread.currentThread().interrupt();
		}

	}
}