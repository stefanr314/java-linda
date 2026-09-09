package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.*;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.IOException;
import java.io.ObjectInput;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
	Separate thread for writing of file chunks to socket.
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

	public WorkstationHandler(CloseableMessageSink messageSink, ObjectInput in,
							  WorkstationRegistrator registrator,
							  WorkstationInfo info, JobRegistry jobRegistry,
							  Scheduler scheduler, Path baseDirPath) {
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

			fileChunkReceiver.abandon(); // if station died upon sending the results just close open files

			chunkWriters.shutdownNow();  // station is dead so no use of writer thread - visible immediately to
			// writers since interrupt flag is polled

			// delete all directories for jobs that were running when station died - if transfer started but station
			// died mid-way it's required to delete these output dirs since they hold partial result values.
			List<JobContext> jobsOn = jobRegistry.activeJobsOn(context.hostName());
			for (JobContext jobContext : jobsOn) {
				Path outputDirPath = baseDirPath
						.resolve("job_" + jobContext.jobId().value())
						.resolve("output");

				DirCreator.recursivelyDeleteDirOnPath(outputDirPath);
			}

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

				JobId jobId = jobAccepted.jobId();
				LOGGER.info("Workstation: %s has accepted the job: %s. Job is not yet started"
						.formatted(context.hostName(), jobId));

				JobContext job = getJob(jobId);
				if (job == null) {
					context.send(new JobNotPresent(jobId));
					continue;
				}

				JobSpec specification = job.specification();

				List<String> filenames = new ArrayList<>(specification.inputFiles());
				filenames.add(specification.jobFilename());

				Path jobInputDir = baseDirPath
						.resolve("job_" + jobId.value())
						.resolve("input");

				chunkWriters.submit(() -> {
					try {
						context.send(new InputFilesStart(jobId));

						if (new FileChunkSender(context::send).sendFiles(
										jobId,
										filenames,
										jobInputDir,
										() -> job.isFileTransmissionStopped() || Thread.currentThread().isInterrupted())
								.allPresentFilesSent()
						) {

							context.send(new InputFilesEnd(jobId));
						} else if (!Thread.currentThread().isInterrupted()) {
							job.resetFileTransmission();
						}
					} catch (FileSystemException serverSideProblem) {
						// The input files are gone or unreadable on our side. Rescheduling would send the job to
						// another station only to fail there for the same reason, so fail it once and let the client
						// resubmit.

						LOGGER.log(Level.SEVERE, "Job input files unusable on the server", serverSideProblem);

						try {
							context.send(
									new JobFilesFailure(jobId, "Internal server error.")
							);
						} catch (IOException ignored) {
							// station gone anyway - do nothing
						}

						if (jobRegistry.failed(jobId, "Input files unreadable on the server")) {
							context.releaseSlot();
						}

					} catch (IOException writeException) {
						// station is gone here probably so try to reschedule the job once again.

						LOGGER.log(
								Level.WARNING,
								"IO exception while writing the file chunks. Station is possible gone.",
								writeException
						);

						context.releaseSlot();
						jobRegistry.requeued(jobId);

						// try rescheduling it back
						scheduler.scheduleReadyJobs();
					}
				});
			} else if (message instanceof JobRunning running) {

				LOGGER.info(
						"Job %s has been started on station: %s"
								.formatted(running.jobId(), context.hostName())
				);
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
				// note: is there a way to not schedule it back to same station...
				jobRegistry.requeued(rejected.jobId());

				// try rescheduling it back
				scheduler.scheduleReadyJobs();
			} else if (message instanceof JobFinished finished) {

				// job dir at this point will already exist just create the output dir
				Path outputDirPath = baseDirPath
						.resolve("job_" + finished.jobId().value())
						.resolve("output");

				try {
					DirCreator.createDir(outputDirPath);
				} catch (IOException diskException) {
					LOGGER.log(Level.SEVERE, "Disk exception upon creating output dir.", diskException);

					jobRegistry.failed(finished.jobId(), "Internal server error upon dir creation");

					context.send(new AbortResultTransfer(finished.jobId()));

					context.releaseSlot();
				}

				LOGGER.info(
						"Job %s has been finished. Output results to be received..."
								.formatted(finished.jobId())
				);
			} else if (message instanceof FileChunk fileChunk) {

				JobId jobId = fileChunk.jobId();
				Path outputDir = baseDirPath.resolve("job_" + jobId.value()).resolve("output");

				try {
					fileChunkReceiver.acceptChunkAndWrite(fileChunk, outputDir);
				} catch (IOException diskException) {

					LOGGER.log(Level.SEVERE, "Could not store results for " + jobId, diskException);

					fileChunkReceiver.abandon();
					DirCreator.recursivelyDeleteDirOnPath(outputDir);

					// results could not be collected properly -> JOB MUST NOT REACH DONE STATE
					if (jobRegistry.failed(jobId, "Internal server error upon receiving file chunks.")) {
						context.releaseSlot(); // station is technically free 
					}

					context.send(new AbortResultTransfer(jobId));
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

				// todo something with output files - these are the actually delivered files

				if (jobRegistry.finished(filesEnd.jobId())) {
					context.releaseSlot(); // prevent the release being called twice - internal mechanism would
					// prevent unexpected value - this prevents double call (just fancy xD)
				}
			} else if (message instanceof JobFailed failed) {

				LOGGER.log(
						Level.WARNING,
						"Job with id: %s FAILED. REASON of failure: %s"
								.formatted(failed.jobId().value(), failed.reason())
				);

				if (jobRegistry.failed(failed.jobId(), failed.reason())) {

					context.releaseSlot();
				}
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