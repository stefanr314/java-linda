package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.*;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.IOException;
import java.io.ObjectInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientHandler implements ConnectionHandler {

	private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());

	private final CloseableMessageSink messageSink;
	private final ObjectInput in;
	private final JobRegistry jobRegistry;
	private final String clientConnected;
	private final Scheduler scheduler;
	private final FileChunkReceiver fileReceiver;

	private final Path baseDirPath;

	private JobSpec jobSpec;
	private JobId currentJobId;

	public ClientHandler(CloseableMessageSink messageSink,
						 ObjectInput in,
						 JobRegistry jobRegistry,
						 String clientConnected,
						 Scheduler scheduler, Path baseDirPath) {

		this.messageSink = messageSink;
		this.in = in;
		this.jobRegistry = jobRegistry;

		this.clientConnected = clientConnected;
		this.scheduler = scheduler;
		this.baseDirPath = baseDirPath;

		this.fileReceiver = new ClientInputFilesReceiver();
	}

	@Override
	public void run() throws IOException, ClassNotFoundException {

		UserContext userContext = new UserContext(messageSink, clientConnected);
		userContext.send(new Reply("Welcome client %s.".formatted(clientConnected)));

		try {
			loop(userContext);
		} catch (IOException e) {
			// try catching the exception that are regular end time exception (server does handle this in some measure)
			// if user is gone before the server finds out and at that time moment the station fails, the message
			// sent by the HB will be caught here (by read operation) with the Socket Exception (broken pipe or so).
			// That's sign to abort the job -> BUT WHAT CONNECTS THE CLIENT AND THE JOB (save the jobContext from
			// below perhaps?) -> also it's tricky to check whether the station died here on just something else
			// happened so the socket is close (ne mogu da ugasim posao ako jednostavnoe ne znam da li to treba da
			// uradim jer je hb uocio mrtvu stanicu i klijent nije dostupan ili jednostavno pukao socket ka klijentu
			// znaci zato mi treba neki flag na job contextu stanica mrtva ili nesto slicno).
			throw new RuntimeException(e); //fixme
		} finally {
			userContext.disconnect();  // close the user context

			// close all open input files
			fileReceiver.abandon();  // this function is pure so calling it more than once yields the same outcome

			// remove from registry all jobs with status RECEIVING - there can be only one such file since input
			// files transfer is sequential
			if (currentJobId != null) {
				// moving to failed status so we can see it in job log.
				jobRegistry.failed(
						currentJobId,
						"Client disconnected mid-way whilest transferring input files."
				);

				Path jobPath = baseDirPath.resolve("job_" + currentJobId.value());
				DirCreator.recursivelyDeleteDirOnPath(jobPath);
			}
		}
	}

	private void loop(UserContext userContext) throws IOException, ClassNotFoundException {
		for (; ; ) {
			Object received = in.readObject();

			if (received instanceof JobSubmitCommand jobSubmit) {

				// just support files transfer in sequence.
				if (jobSpec != null && currentJobId != null
						&& jobRegistry.find(currentJobId)
						.map(j -> j.status() == JobStatus.RECEIVING)
						.orElse(false)) {

					userContext.send(new Failure("Finish uploading the previous job first"));
					continue;
				}

				// firstly add it to the job registry - create the job id.
				JobContext job = jobRegistry.register(
						(currentJobId = new JobId(UUID.randomUUID().toString())),
						userContext,
						(jobSpec = jobSubmit.jobSpec())
				);

				// send the confirmation
				userContext.send(new JobRegistered(job.jobId()));
			} else if (received instanceof InputFilesStart inputFilesStart) {
				JobId jobId = inputFilesStart.jobId();

				if (jobRegistry.find(jobId).isEmpty()) {
					continue;  // early return if someone managed to outpass the initial intro message
				}

				Path inputDir = baseDirPath.resolve("job_" + jobId.value()).resolve("input");

				try {
					DirCreator.createDir(inputDir);
					userContext.send(new ReadyToAcceptInputFiles(jobId));
				} catch (IOException diskException) {

					LOGGER.log(Level.WARNING, "Creation of input dir failed.", diskException);
					userContext.send(new JobFilesFailure(
							jobId,
							"Job was rejected due to error on server. Please try again later."));
					// since it was present in job registry and in job log just mark that in log somewhere
					jobRegistry.failed(jobId, "Unexpected input dir creation failure.");

					// this entry job serves no point just deleting it
					jobRegistry.remove(jobId);

					// since job receipt failed just null all this fields
					currentJobId = null;
					jobSpec = null;
				}

			} else if (received instanceof FileChunk fileChunk) {

				// this part is mandatory since client never stops file chunk sending mid-way but server must protect
				// against this
				Optional<JobContext> jobContext = jobRegistry.find(fileChunk.jobId());
				if (jobContext.isEmpty() || jobContext.get().status() != JobStatus.RECEIVING) {

					continue;  // early return otherwise file receiver will work with non-existent files
				}

				if (jobSpec == null) {
					// order must be satisfied -> otherwise NPE will arise.
					userContext.send(new Failure("Received a file chunk before any job was submitted"));
					continue;
				}

				boolean fileChunkContainsJobFilename = fileChunk.fileName().equals(jobSpec.jobFilename());

				if (!jobSpec.inputFiles().contains(fileChunk.fileName()) && !fileChunkContainsJobFilename) {

					internalFileRejection(
							userContext,
							fileChunk.jobId(),
							"Constraint on input files broken. Job is " +
									"rejected and cleaned from server."
					);

					continue;
				}

				Path inputDir = baseDirPath.resolve("job_" + fileChunk.jobId().value()).resolve("input");

				try {
					fileReceiver.acceptChunkAndWrite(fileChunk, inputDir)
							.ifPresent(filepath -> {
								LOGGER.info("File received and saved on: " + filepath); // these logs are too verbose
							});
				} catch (IOException diskException) {
					LOGGER.log(Level.WARNING,
							"Error when working with files. Disk exception happened.",
							diskException);

					internalFileRejection(
							userContext,
							fileChunk.jobId(),
							"Server error occurred whilest working with files. Please try again."
					);
				}
			} else if (received instanceof InputFilesEnd filesReceived) {

				// NOTE: this object (set of bytes) represents the SENTINEL VALUE OF input file chunks transfer.
				// After receiving this object and performing actions this handler thread can collect other job
				// requests from the same client (if TCP guarantees are met) -> this is mandatory in order that
				// jobSpec holds proper value (otherwise job spec can interleave).

				Optional<JobContext> optionalJob = jobRegistry
						.find(filesReceived.jobId());

				JobContext job;
				if (optionalJob.isEmpty() || (job = optionalJob.get()).status() != JobStatus.RECEIVING) {
					userContext.send(
							new JobFilesFailure(filesReceived.jobId(),
									"Server could not accept the job; try again later")
					);
					continue;  // return early
				}
				JobSpec spec = job.specification();

				List<String> expected = new ArrayList<>(spec.inputFiles());
				expected.add(spec.jobFilename());

				Path inputDir = baseDirPath
						.resolve("job_" + filesReceived.jobId().value())
						.resolve("input");

				// take the path and check whether exists
				List<String> missing = expected
						.stream()
						.filter(filename -> !Files.isRegularFile(inputDir.resolve(filename)))
						.toList();

				if (!missing.isEmpty()) {
					internalFileRejection(userContext, filesReceived.jobId(),
							"Missing input files: " + missing + ". Please submit again.");
					continue;
				}

				LOGGER.fine("All input file bytes have been received for job:" + filesReceived.jobId().value());

				// not mandatory but explicit null-ing rather
				currentJobId = null;

				// transit state to READY
				jobRegistry.ready(filesReceived.jobId());

				// call the delegator/scheduler in help
				scheduler.scheduleReadyJobs();

				userContext.send(new JobQueued(filesReceived.jobId()));  // let the user know
//			} else if (received instanceof CheckJobResultCommand jobResult) {
//				// check the job result if status done
//			} else if (received instanceof CheckJobStatusCommand checkJobStatusCommand) {
//				// check job status
//			} else if (received instanceof AbortJobCommand checkAbortJobCommand) {
//				// abort the job
//			} else if (received instanceof JobStoppedResponse jobStoppedResponse) {
//				// respond to the job that was stopped by dead workstation

				// client either aborted or delegated the job to next free station
				// IF ABORTED CALL THE CLASS FOR ABORTION

				// IF DELEGATED CALL SCHEDULER TO DELEGATE ONCE AGAIN (I hope so)
			} else if (received instanceof Bye ignored) {

				// client closed the connection everything should keep running anyway
				return;
			} else {

				messageSink.send(new Failure("Unknown message received: " + received.getClass()));
			}
		}
	}

	private void internalFileRejection(UserContext userContext, JobId jobId, String reason) throws IOException {
		// close open files - (mandatory to close open files before deleting them on windows)
		fileReceiver.abandon();

		// moving job status to failed so user can query it.
		jobRegistry.failed(jobId, reason);

		// delete job dir and everything inside
		Path jobDir = baseDirPath.resolve("job_" + jobId.value());
		DirCreator.recursivelyDeleteDirOnPath(jobDir);

		// null-ing current job;
		currentJobId = null;

		// null-ing the job specification
		jobSpec = null;

		// constraint broken - declare job rejected
		userContext.send(
				new JobFilesFailure(jobId, reason)
		);
	}
}