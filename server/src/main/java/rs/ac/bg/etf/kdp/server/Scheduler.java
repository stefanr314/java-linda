package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.protocol.JobDispatch;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

public final class Scheduler {

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
	 * such as the same ready job being forwarded to multiple stations on execution. But explicit synchronization
	 * (with intrinsic lock per se) can be swapped with the leightweight atomic operation which checks and sets the
	 * status of job in one go. If another thread took the precendance just proceed to the next ready job in queue.
	 * </p>
	 */
	public void scheduleReadyJobs() {
		// NOTE: ready jobs is just a snapshot so upon tacking the ready jobs it's required to hold a lock and try to
		// update the status of the job to scheduled

		// get the ready jobs
		List<JobContext> readyJobs = jobRegistry.readyJobs();
		if (readyJobs.isEmpty()) return;

		// it's required to firstly try to set the state and then to act upon it, since vise verse might lead to data
		// races and execution/forwarding duplication of single job. With this one thread works with one ready job at
		// exact moment.
		for (JobContext job : readyJobs) {
			// try to change status to scheduled - prior user client request for job abortion has occurred
			if (!jobRegistry.scheduled(job.jobId())) continue;

			// use the workstation registry to find the available station beware that at this time station may be
			// disconnected - which result in holding a lock for a gone station. If no stations found just return.
			Optional<WorkstationContext> optContext = workstationRegistry.tryFindFreeStation();
			if (optContext.isEmpty()) {
				jobRegistry.requeued(job.jobId());
				return;
			}

			WorkstationContext station = optContext.get();
			jobRegistry.assignedTo(job.jobId(), station.hostName());

			try {
				station.send(new JobDispatch(job.jobId(), job.specification())); // the socket might be
				// closed at this moment - if workstation initiates the graceful shutdown this might be sent SO WS
				// HANDLER MUST CHECK THIS TOO - OR DELEGATE IT TO THE REGISTRATOR unregister
				// todo: so its required to check whether there are some jobs in scheduled state too when unregister
				//  happens
			} catch (IOException e) {
				// if exception thrown when writing to the station it's required to release the slot hold for that station
				// and return the status to ready once again.
				LOGGER.info("Station socket not reachable. On station: " + station.hostName());

				station.releaseSlot();
				jobRegistry.requeued(job.jobId());
			}
		}
	}
}