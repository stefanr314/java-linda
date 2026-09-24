package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.JobId;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages pending user decisions when a workstation running a job is lost.
 *
 * <p>When a station is lost, if the client is unreachable, the job is aborted immediately.
 * Otherwise, a timeout is scheduled and the question is kept pending until the client either
 * decides (reschedule or abort) via a status poll / decision command, or the timeout elapses.</p>
 */
public final class DecisionBroker implements AutoCloseable {

	private static final Logger LOGGER = Logger.getLogger(DecisionBroker.class.getName());
	private static final long DEFAULT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);
	private final JobRegistry jobRegistry;
	private final Scheduler scheduler;
	private final long timeoutMillis;
	// Guards pending decisions across handler threads, scheduler, and the single timeout thread
	private final Map<JobId, PendingDecision> pendingDecisions = new ConcurrentHashMap<>();
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread daemon = new Thread(runnable, "decision-broker");
		daemon.setDaemon(true);
		return daemon;
	});

	public DecisionBroker(JobRegistry jobRegistry, Scheduler scheduler) {
		this(jobRegistry, scheduler, DEFAULT_TIMEOUT_MILLIS);
	}

	public DecisionBroker(JobRegistry jobRegistry, Scheduler scheduler, long timeoutMillis) {
		this.jobRegistry = Objects.requireNonNull(jobRegistry);
		this.scheduler = Objects.requireNonNull(scheduler);
		this.timeoutMillis = timeoutMillis;
	}

	/**
	 * Called when a workstation was lost while running the given job.
	 *
	 * <p>If the user context is not connected, the job is aborted immediately without scheduling
	 * any timer. Otherwise, records the question and schedules an abort after the timeout.</p>
	 */
	public void askAboutLostStation(JobContext job, String lostHost) {
		UserContext uc = job.userContext();
		if (uc == null || !uc.isConnected()) {
			LOGGER.log(Level.INFO,
					"User for job {0} is not connected; aborting immediately after losing workstation {1}",
					new Object[]{job.jobId(), lostHost});
			jobRegistry.aborted(job.jobId());
			return;
		}

		String reason = "Workstation " + lostHost + " was lost";
		Instant deadline = Instant.now().plusMillis(timeoutMillis);

		ScheduledFuture<?> future = executor.schedule(() -> {
			PendingDecision removed = pendingDecisions.remove(job.jobId());
			if (removed != null) {
				LOGGER.log(Level.FINE, "Decision timer fired for job {0}; attempting abort", job.jobId());
				jobRegistry.aborted(job.jobId());
			}
		}, timeoutMillis, TimeUnit.MILLISECONDS);

		pendingDecisions.put(job.jobId(), new PendingDecision(reason, deadline, future));
	}

	/**
	 * Returns the pending question for the job, if any, for the status reply.
	 *
	 * @return question text containing reason and seconds remaining, or null if nothing is pending
	 */
	public String pendingFor(JobId jobId) {
		PendingDecision pending = pendingDecisions.get(jobId);
		if (pending == null) {
			return null;
		}

		long remainingSeconds = Math.max(0, Duration.between(Instant.now(), pending.deadline()).toSeconds());
		return pending.reason() + " (" + remainingSeconds + "s remaining)";
	}

	/**
	 * Applies the user's decision for the job: removes the pending decision, cancels the timer,
	 * and either requeues or aborts the job.
	 *
	 * @param jobId      id of the job
	 * @param reschedule true to put back in READY queue, false to abort
	 * @return true if a decision was pending and processed; false otherwise
	 */
	public boolean decide(JobId jobId, boolean reschedule) {
		PendingDecision pending = pendingDecisions.remove(jobId);
		if (pending == null) {
			return false;
		}

		pending.future().cancel(false);

		if (reschedule) {
			Optional<JobContext> jobContext = jobRegistry.find(jobId);
			if (jobContext.isEmpty()) {
				return false;
			}
			jobContext.get().resetReschedulingCounter();  // reset when user wants reschedule
			jobRegistry.requeued(jobId);
			scheduler.scheduleReadyJobs();
		} else {
			jobRegistry.aborted(jobId);
		}
		return true;
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}

	private record PendingDecision(String reason, Instant deadline, ScheduledFuture<?> future) {
	}
}