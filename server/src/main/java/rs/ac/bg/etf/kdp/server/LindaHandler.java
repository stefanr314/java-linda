package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.DirManipulator;
import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;
import rs.ac.bg.etf.kdp.common.exceptions.OutsideTupleSpaceInterruptedException;
import rs.ac.bg.etf.kdp.common.exceptions.SuspendedTupleSpaceException;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInput;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler dedicated to a single Linda client (i.e. a single job id).
 *
 * <p>
 * <b>Eval limitation:</b> {@code eval()} is served only if a workstation is free at the exact
 * moment it's called; if none is free the caller gets back a {@link Failure} instead of the
 * worker being queued. Queueing would risk starting a worker after the parent job has already
 * finished, at which point it would write into a tuple space nobody reads any more - and the
 * parent is entitled to finish while such a worker is still waiting.
 * </p>
 */
public class LindaHandler implements ConnectionHandler {

	private static final Logger LOGGER = Logger.getLogger(LindaHandler.class.getName());

	private final CloseableMessageSink sink;
	private final ObjectInput in;
	private final JobRegistry jobRegistry;
	private final Scheduler scheduler;
	private final WorkstationRegistry workstationRegistry;
	private final Path baseDirPath;
	/*
	This handler is per linda client meaning per job id. Job id will never change as long as handler lives. That's
	clear sign that upon rescheduling all the parked threads (either blocked tuple waiters or blocked reader) must
	abandon that job and handler -> tuple space in meantime will get reset.
	 */
	private final JobId jobId;

	public LindaHandler(CloseableMessageSink sink, ObjectInput in, JobRegistry jobRegistry,
						Scheduler scheduler, WorkstationRegistry workstationRegistry, Path baseDirPath,
						JobId jobId) {
		this.sink = sink;
		this.in = in;
		this.jobRegistry = jobRegistry;
		this.scheduler = scheduler;
		this.workstationRegistry = workstationRegistry;
		this.baseDirPath = baseDirPath;
		this.jobId = jobId;
	}

	@Override
	public void run() throws IOException, ClassNotFoundException {
		Optional<JobContext> optJob = jobRegistry.find(jobId);
		if (optJob.isEmpty() || optJob.get().status().isTerminal()) {
			// if job was already declared as terminated just return;
			sink.send(new JobNotPresent(jobId));
			return; // no need to proceed
		}

		JobContext job = optJob.get();
		TupleSpace workingTupleSpace = job.tupleSpace();
		job.addLindaConnection(sink);

		sink.send(new LindaRegistered("Welcome Linda client"));  //boring ass ack message

		try {
			loop(job, workingTupleSpace);
		} catch (EOFException | SocketException e) {
			// will see about catching
		} catch (SuspendedTupleSpaceException sus) {
			try {
				// the outside signal on ts either job reached terminal state or it's rescheduled
				sink.send(new Failure("Tuple space is declared emptied. Either it's cleared or closed."));
			} catch (IOException gone) {
				// connection to station might be gone here - the job.prepareReschedule() cleans the connections
				LOGGER.log(Level.FINE, "Connection already closed to station", gone);
			}
		} catch (OutsideTupleSpaceInterruptedException interrupt) {
			// just mapper of external interrupt exception - server is shutting down
		} finally {
			job.removeLindaConnection(sink);  // if sus happens it will get cleaned but false returned by underlying
			// methods
		}
	}

	private void loop(JobContext job, TupleSpace tupleSpace) throws IOException, ClassNotFoundException {
		for (; ; ) {
			Object lindaCommand = in.readObject();

			if (lindaCommand instanceof Out out) {
				try {

					tupleSpace.out(out.tuple());
					sink.send(new Ack());
				} catch (IllegalArgumentException illegal) {
					// not valid argument sent - out command contains null message
					sink.send(new Failure(illegal.getMessage()));
				}

			} else if (lindaCommand instanceof In inCommand) {
				tupleSpace.in(inCommand.template());

				sink.send(new TupleReply(inCommand.template()));
			} else if (lindaCommand instanceof Rd rd) {
				tupleSpace.rd(rd.template());

				sink.send(new TupleReply(rd.template()));
			} else if (lindaCommand instanceof Inp inp) {
				if (tupleSpace.inp(inp.template())) {
					sink.send(new TupleReply(inp.template()));
				} else {
					sink.send(new BoolReply(false));
				}
			} else if (lindaCommand instanceof Rdp rdp) {
				if (tupleSpace.rdp(rdp.template())) {
					sink.send(new TupleReply(rdp.template()));
				} else {
					sink.send(new BoolReply(false));
				}
			} else if (lindaCommand instanceof Eval eval) {
				handleEval(job, eval);
			} else {
				// default branch
				sink.send(new Failure("Command not found"));
			}
		}
	}

	/**
	 * Dispatches an {@code eval()} worker that shares this handler's job's tuple space. See the
	 * class Javadoc for the queueing limitation.
	 */
	private void handleEval(JobContext parentJob, Eval eval) throws IOException {
		Optional<WorkstationContext> optStation = workstationRegistry.tryFindFreeStation();
		if (optStation.isEmpty()) {
			sink.send(new Failure("no free workstation for eval"));
			return;
		}
		WorkstationContext station = optStation.get();

		JobId childId = new JobId(jobId.value() + "-eval-" + UUID.randomUUID());

		JobSpec parentSpec = parentJob.specification();
		JobSpec childSpec;
		try {
			childSpec = new JobSpec(
					parentSpec.jobFilename(),
					"eval:" + eval.name(),
					parentSpec.inputFiles(),
					java.util.List.of()
			);
		} catch (RuntimeException invalidSpec) {
			station.releaseSlot();
			sink.send(new Failure("Could not prepare eval worker: " + invalidSpec.getMessage()));
			return;
		}

		JobContext childJob = jobRegistry.register(childId, parentJob.userContext(), childSpec);

		Path parentInputDir = baseDirPath.resolve("job_" + jobId.value()).resolve("input");
		Path childInputDir = baseDirPath.resolve("job_" + childId.value()).resolve("input");
		try {
			DirManipulator.createDir(childInputDir);
			DirManipulator.copyDirContents(parentInputDir, childInputDir);
		} catch (IOException copyFailed) {
			LOGGER.log(Level.WARNING, "Could not prepare input files for eval worker " + childId, copyFailed);

			station.releaseSlot();
			jobRegistry.failed(childId, "Could not prepare eval worker input files");
			DirManipulator.recursivelyDeleteDirOnPath(baseDirPath.resolve("job_" + childId.value()));

			sink.send(new Failure("Could not prepare eval worker input files"));
			return;
		}

		scheduler.dispatchEval(childJob, jobId, station, eval.serializedRunnable());

		sink.send(new Ack());
	}
}