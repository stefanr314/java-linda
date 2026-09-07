package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.*;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.IOException;
import java.io.ObjectInput;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

	private final CloseableMessageSink messageSink;
	private final ObjectInput in;

	private final WorkstationInfo info;

	private final WorkstationRegistrator registrator;
	private final JobRegistry jobRegistry;
	private final Scheduler scheduler;

	private final FileChunkReceiver fileChunkReceiver;

	private final Path baseDirPath;

	/*
	Separate pool for sending file chunks to the workstation. Writes must be conducted in separate thread since the
	main thread will not read incoming messages properly.
	 */
	private final ExecutorService writerPool = Executors.newFixedThreadPool(
			4,
			(runner) -> new Thread(runner, "file-chunk-writer-")
	);

	public WorkstationHandler(CloseableMessageSink messageSink, ObjectInput in,
							  WorkstationRegistrator registrator,
							  WorkstationInfo info, JobRegistry jobRegistry, Scheduler scheduler, Path baseDirPath) {
		this.messageSink = messageSink;
		this.in = in;
		this.registrator = registrator;
		this.info = info;

		this.jobRegistry = jobRegistry;

		this.scheduler = scheduler;

		this.fileChunkReceiver = new StationResultsReceiver(info.osName());
		this.baseDirPath = baseDirPath;
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

				LOGGER.info("Workstation: %s has accepted the job: %s. Job is not yet started"
						.formatted(context.hostName(), jobAccepted.jobId()));

				JobContext job = getJob(jobAccepted.jobId());
				if (job == null) {
					context.send(new JobNotPresent(jobAccepted.jobId()));
					continue;
				}

				JobSpec specification = job.specification();

				List<String> filenames = new ArrayList<>(specification.inputFiles());
				filenames.add(specification.jobFilename());

				Path jobInputDir = baseDirPath.resolve("job_" + jobAccepted.jobId().value()).resolve("input");

				writerPool.submit(() -> {
					try {
						context.send(new InputFilesStart());
						if (new FileChunkSender(context::send).sendFiles(jobAccepted.jobId(), filenames,
								jobInputDir, job::isFileTransmissionStopped)) {

							context.send(new InputFilesEnd(jobAccepted.jobId()));
						} else {
							job.resetFileTransmission();
						}
					} catch (IOException writeException) {
						LOGGER.log(Level.WARNING, "IO exception while writing the file chunks; check the paths", writeException);
						try {
							context.send(new JobFilesFailure(jobAccepted.jobId(), "Internal server error."));
						} catch (IOException e) {
							// station gone???
						}
					}
				});
			} else if (message instanceof JobRunning running) {

				LOGGER.info("Job %s has been started on station: %s".formatted(running.jobId(), context.hostName()));
				jobRegistry.running(running.jobId());
			} else if (message instanceof JobRejected rejected) {

				//signal writer to stop writing
				JobContext job = getJob(rejected.jobId());
				if (job == null) {
					context.send(new JobNotPresent(rejected.jobId()));
					continue;
				}

				job.stopFileTransmission();

				// station rejected the job - cleanup must be conducted
				// release the slot of this station
				context.releaseSlot();

				// change the status of job (was scheduled) and put it back to the ready
				jobRegistry.requeued(rejected.jobId());

				// try rescheduling it back
				scheduler.scheduleReadyJobs();
			} else if (message instanceof JobFinished finished) {

				// job dir at this point will already exist just create the output dir
				Path outputDirPath = baseDirPath.resolve("job_" + finished.jobId().value()).resolve("output");

				try {
					DirCreator.createDir(outputDirPath);
				} catch (IOException diskException) {
					// todo: handle me
					LOGGER.log(Level.WARNING, "Disk exception upon creating output dir.", diskException);
				}

				LOGGER.info("Job %s has been finished. Output results to be received...".formatted(finished.jobId()));
			} else if (message instanceof FileChunk fileChunk) {

				try {
					JobId jobId = fileChunk.jobId();
					Path writeToPath = baseDirPath.resolve("job_" + jobId.value()).resolve("output");

					fileChunkReceiver.acceptChunkAndWrite(fileChunk, writeToPath);
				} catch (IOException e) {
					// these should not break the station down
					LOGGER.log(Level.SEVERE, "File IO system failed.", e);
				}
			} else if (message instanceof OutputFilesEnd filesEnd) {
				LOGGER.info("Results RECEIVED for job: " + filesEnd.jobId().value());

				LOGGER.fine(() -> {
					StringBuilder returnMessage = new StringBuilder("All files received. Files received: ");
					for (String delivered : filesEnd.deliveredFiles()) {
						returnMessage.append(delivered);
					}

					return returnMessage.toString();
				});

				jobRegistry.finished(filesEnd.jobId());
				context.releaseSlot();
			} else if (message instanceof JobFailed failed) {

				LOGGER.log(Level.WARNING,
						"Job with id: %s FAILED. REASON of failure: %s".formatted(failed.jobId().value(), failed.reason()));

				jobRegistry.failed(failed.jobId(), failed.reason());
				context.releaseSlot();
			} else if (message instanceof Bye ignored) {

				return;
			} else {

				context.send(new Failure("Unknown message type provided: " + message.getClass().getSimpleName()));
			}
		}
	}

	private JobContext getJob(JobId jobId) {
		Optional<JobContext> optionalJob = jobRegistry.find(jobId);

		return optionalJob.orElse(null);
	}
}