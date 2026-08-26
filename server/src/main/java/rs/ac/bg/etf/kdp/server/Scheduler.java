package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.protocol.JobSubmitCommand;

import java.io.IOException;
import java.util.Optional;

public final class Scheduler {
	private final JobRegistry jobRegistry;
	private final WorkstationRegistry workstationRegistry;

	public Scheduler(JobRegistry jobRegistry, WorkstationRegistry workstationRegistry) {
		this.jobRegistry = jobRegistry;
		this.workstationRegistry = workstationRegistry;
	}

	// NOTE I can make this return true and false; for example if no slots available return false so the handler can
	// delegate it to queue of
	// FIXME: since the job will be present in the jobRegistry
	public void scheduleJob(JobSubmitCommand jobSubmit) {
		// use the workstation registry to find the available station beware that at this time station may be
		// disconnected
		Optional<WorkstationContext> optContext = workstationRegistry.tryFindFreeStation();
		if (optContext.isEmpty()) return;

		WorkstationContext context = optContext.get();

		try {
			context.send(jobSubmit);
		} catch (IOException e) {
			// if exception thrown when writing to the station it's required to release the slot hold for that station
			// note: i can be more specific about the exception
			context.releaseSlot();
		}
	}

	public void reportedStation(WorkstationContext workstationContext) {
		// station just reported try to send her some jobs if any in waiting queue (job registry with ready jobs).
	}
}