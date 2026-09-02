package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.WorkstationInfo;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.IOException;
import java.io.ObjectInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler dedicated for working with workstations. Handle all the communication, registering the stations. All the
 * exceptions are just propagated to the server as this is fine and expected behaviour of workstations. Workstations
 * can not disconnect like clients.
 *
 * <p>
 * Thread confined so no thread safety required.
 * </p>
 */
public class WorkstationHandler implements ConnectionHandler {

	private final static Logger LOGGER = Logger.getLogger(WorkstationHandler.class.getName());
	private static final Path BASE_DIR_PATH = Path.of(System.getProperty("java.io.tmpdir"), "server_jobs");

	private final CloseableMessageSink messageSink;
	private final ObjectInput in;
	private final WorkstationRegistrator registrator;
	private final WorkstationInfo info;
	private final JobRegistry jobRegistry;
	private final Scheduler scheduler;
	private final FileChunkReceiver fileChunkReceiver;
	private Path outputDirPath;

	public WorkstationHandler(CloseableMessageSink messageSink, ObjectInput in,
							  WorkstationRegistrator registrator,
							  WorkstationInfo info, JobRegistry jobRegistry, Scheduler scheduler) {
		this.messageSink = messageSink;
		this.in = in;
		this.registrator = registrator;
		this.info = info;
		this.jobRegistry = jobRegistry;
		this.scheduler = scheduler;
		this.fileChunkReceiver = new FileChunkReceiver();
	}

	@Override
	public void run() throws IOException, ClassNotFoundException {
		// create context - create context prior to check whether the station has already registered context
		WorkstationContext context = registrator.register(info, messageSink);

		// run the loop which serves the communication with the workstation
		try {
			loop(context);
		} finally {
			// once the socket is closed (no matter the reason) this is the only place to deregister the workstation
			// from registrator; otherwise dead workstation can be picked as candidate for processing jobs

			registrator.unregister(context);
		}
	}

	private void loop(WorkstationContext context) throws IOException, ClassNotFoundException {
		for (; ; ) {
			Object message = in.readObject();

			if (message instanceof Pong pong) {
				long now = System.nanoTime();
				context.reportAt(now);
				context.recordRTT(now - pong.returnNanoTime());
			} else if (message instanceof Ping ping) {
				// workstation should not ping server but that type of communication is not harmful tbh...
				context.reportAt(System.nanoTime());
				context.send(new Pong(ping.timeNanos()));
			} else if (message instanceof JobAccepted jobAccepted) {
				LOGGER.info("Workstation: %s has accepted the job: %s. Job is not yet started".formatted(context.hostName(),
						jobAccepted.jobId()));
			} else if (message instanceof JobRunning running) {
				LOGGER.info("Job %s has been started on station: %s".formatted(running.jobId(), context.hostName()));
				jobRegistry.running(running.jobId());  //todo: ws info is required to be passed?
			} else if (message instanceof JobRejected rejected) {
				// station rejected the job - cleanup must be conducted
				// release the slot of this station
				context.releaseSlot();

				// change the status of job (was scheduled) and put it back to the ready
				jobRegistry.requeued(rejected.jobId());

				// try rescheduling it back
				scheduler.scheduleReadyJobs();
			} else if (message instanceof JobFinished finished) {
				outputDirPath = BASE_DIR_PATH.resolve("job_" + finished.jobId().value()).resolve("output");

				// if does not exist create the new Dir
				Files.createDirectories(outputDirPath);

				LOGGER.info("Job %s has been finished. Output results to be received...".formatted(finished.jobId()));
			} else if (message instanceof FileChunk fileChunk) {
				// todo: create file receiver that knows to read the file chunk and upon reading sentinel value can
				//  signal handler about it
				try {
					JobId jobId = fileChunk.jobId();
					fileChunkReceiver.acceptChunkAndWrite(fileChunk, outputDirPath,
							context.workstationInfo().osName()).ifPresent(path -> {
						LOGGER.info("Job results for job %s have been collected. Job is DONE.".formatted(jobId));
						jobRegistry.finished(jobId);
					});
				} catch (IOException e) {
					// thse should not break the station down
					LOGGER.log(Level.SEVERE, "File IO system failed", e);
				}
			} else if (message instanceof Bye ignored) {
				return;
			} else {
				context.send(new Failure("Unknown message type provided: " + message.getClass().getSimpleName()));
			}
		}
	}
}