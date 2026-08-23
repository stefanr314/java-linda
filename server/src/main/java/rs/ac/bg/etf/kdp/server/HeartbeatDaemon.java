package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.HeartbeatPolicy;
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
 * <p>
 * Responsibility of this mechanism is to detect stale/dead connections and close them to prevent server resources
 * from being exhausted.
 * </p>
 */
public class HeartbeatDaemon implements AutoCloseable {

	private final static Logger LOGGER = Logger.getLogger(HeartbeatDaemon.class.getName());

	private final long intervalMillis;
	private final long timeoutNanos;
	private final WorkstationRegistry workstationRegistry;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "heartbeat checker");

		t.setDaemon(true);
		return t;
	});
	private final Runnable runner = this::sweeper;

	public HeartbeatDaemon(HeartbeatPolicy policy, WorkstationRegistry workstationRegistry) {
		this.intervalMillis = policy.intervalMillis();
		this.timeoutNanos = policy.timeoutNanos();
		this.workstationRegistry = workstationRegistry;
	}

	public void start() {
		LOGGER.fine("Heartbeat mechanism started");
		scheduler.scheduleWithFixedDelay(runner, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
	}

	/**
	 * Timestamp will not be used by other machine in distributed system (workstation in this case). It will be
	 * used by the server machine to determine the round-trip time (the machine that eventually sent the ping message)
	 * and staleness detection. That requires monotic nanoTime clock to be used, since currentTimeMillis is OS's
	 * wall-clock, and it's prone to abrupt changes (system or user).
	 * <p>
	 * Worth mentioning is the fact that this type of iteration on list of workstations derived from concurrent hash
	 * map is not thread safe by design even though the returned list is the copy so no NPE will arise; the issue
	 * becomes visible if the operation conducted on the workstation instances ( that at the time of checking might
	 * not even live in registry) are not idempotent - meaning they alter the state of server invariants. With
	 * methods {@link WorkstationContext#send} and {@link WorkstationContext#disconnect} this is not the case since
	 * upon disconnected workstation, they (methods) merely throw errors that are logged (if send can not be
	 * performed) or just ignored (if disconnect is called on already closed socket). This behaviour is expected.
	 * </p>
	 */
	private void sweeper() {
		// check for available stations - not thread safe by design decision
		for (WorkstationContext workstation : workstationRegistry.workstations()) {
			// check if ws is stale
			if (workstation.staleTimeoutElapsed(timeoutNanos)) {
				LOGGER.log(Level.WARNING,
						"Workstation %s did not respond before the timeout.".formatted(workstation.hostName()));
				//TODO: client must determine the future of job
				workstation.disconnect();
				continue;
			}

			// send messages
			try {
				workstation.send(new Ping(System.nanoTime()));
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