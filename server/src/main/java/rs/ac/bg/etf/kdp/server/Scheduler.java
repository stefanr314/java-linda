package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.protocol.EvalDispatch;
import rs.ac.bg.etf.kdp.common.protocol.JobDispatch;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

public final class Scheduler {

	/*
	Limit of rescheduling allowed. First i.e. initial schedule is not counted by the present counter implementation
	method.
	 */
	private static final int MAX_RESCHEDULING = 3;

	private static final Logger LOGGER = Logger.getLogger(Scheduler.class.getName());

	private final JobRegistry jobRegistry;
	private final WorkstationRegistry workstationRegistry;

	public Scheduler(JobRegistry jobRegistry, WorkstationRegistry workstationRegistry) {
		this.jobRegistry = jobRegistry;
		this.workstationRegistry = workstationRegistry;
	}

	/**
	 * Method for scheduling ready jobs. Scheduling is performed on the snapshot of ready jobs, meaning the status of
	 * job can change mid-way whilest scheduling. To prevent unexpected behaviour atomic action of trying to change
	 * the status (with the support of underlying allowed advancing states) is required. This however can not prevent
	 * the race between user actions (i.e. upon aborting the job) and forwarding the job to workstations; that type
	 * is strictly the responsibility of job registry to properly set the status and if terminal not to change it.
	 * <p>
	 * This method can be called by multiple threads leading to undesired outcomes when executed by multiple threads,
	 * such as the same ready job being forwarded to multiple stations for execution. But explicit synchronization
	 * (with intrinsic lock per se) can be swapped with the leightweight atomic operation which checks and sets the
	 * status of job in one go. If another thread took the precendance just proceed to the next ready job in queue.
	 * </p>
	 */
	public void scheduleReadyJobs() {

		List<JobContext> readyJobs = jobRegistry.readyJobs();
		if (readyJobs.isEmpty()) return;

		if (workstationRegistry.workstations().isEmpty()) return;  // no stations present just return

		// it's required to firstly try to set the state and then to act upon it, since vise verse might lead to data
		// races and execution/forwarding duplication of single job. With this one thread works with one ready job at
		// exact moment.
		for (JobContext job : readyJobs) {
			if (!jobRegistry.scheduled(job.jobId())) continue;

			Optional<WorkstationContext> optContext = workstationRegistry.tryFindFreeStationExcept(job.rejectedBy());
			if (optContext.isEmpty()) {
				optContext = workstationRegistry.tryFindFreeStation();  // if no other stations present
			}
			if (optContext.isEmpty()) {
				jobRegistry.requeued(job.jobId());
				return;
			}

			WorkstationContext station = optContext.get();

			// first schedule is not counted
			if (job.incrementReschedulingCounter() > MAX_RESCHEDULING) {
				jobRegistry.failed(job.jobId(), "Rescheduling hit the limit. " +
						"Job could not be run on any stations.");
				station.releaseSlot();
				continue;
			}

			jobRegistry.assignedTo(job.jobId(), station.hostName());

			try {
				station.send(new JobDispatch(job.jobId(), job.specification()));

				// if send proceeds to the other side, check is required to see if station is still alive
				if (workstationRegistry.find(station.hostName()).orElse(null) != station) {

					jobRegistry.requeued(job.jobId());
				}
			} catch (IOException e) {
				LOGGER.info("Station socket not reachable. On station: " + station.hostName());

				station.releaseSlot();
				jobRegistry.requeued(job.jobId());
			}
		}
	}

	/**
	 * Dispatches a single eval worker to a station the caller ({@link LindaHandler}) has already
	 * reserved a slot on. Unlike {@link #scheduleReadyJobs()} this never requeues on failure: a
	 * worker that starts after its parent job has finished would write into a tuple space nobody
	 * reads anymore, so on any failure the child job is simply failed and the slot released.
	 *
	 * @param childJob           the already-registered child job context
	 * @param parentJobId        id of the job whose {@code eval()} call spawned this worker
	 * @param station            the already-reserved station to dispatch to
	 * @param serializedRunnable the serialized {@code Runnable} to run on the worker
	 */
	public void dispatchEval(JobContext childJob, JobId parentJobId, WorkstationContext station,
							 byte[] serializedRunnable) {
		JobId childId = childJob.jobId();

		jobRegistry.ready(childId);
		if (!jobRegistry.scheduled(childId)) {
			// job reached a terminal state before we could schedule it (e.g. server shutdown) -
			// nothing left to dispatch, just give the slot back
			station.releaseSlot();
			return;
		}

		jobRegistry.assignedTo(childId, station.hostName());

		try {
			station.send(new EvalDispatch(childId, parentJobId, childJob.specification(), serializedRunnable));
		} catch (IOException e) {
			LOGGER.info("Station socket not reachable for eval dispatch. On station: " + station.hostName());

			station.releaseSlot();
			jobRegistry.failed(childId, "Station unreachable for eval dispatch");  // this unblocks linda
			// handler
		}
	}
}