package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.DirCreator;
import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.IOException;
import java.io.ObjectInput;
import java.nio.file.Path;
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

	private Path jobDir;
	private Path jobJarDir;
	private Path inputDir;
	private String jobJarName;
	private JobSpec jobSpec;

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
			// if state of job was new when connection broke delete it from the registry
		}
	}

	private void loop(UserContext userContext) throws IOException, ClassNotFoundException {
		for (; ; ) {
			Object received = in.readObject();

			if (received instanceof JobSubmitCommand jobSubmit) {

				// firstly add it to the job registry - create the job id.
				JobContext job = jobRegistry.register(
						new JobId(UUID.randomUUID().toString()),
						userContext,
						(jobSpec = jobSubmit.jobSpec())
				);

				jobDir = baseDirPath.resolve("job_" + job.jobId());
				jobJarDir = jobDir.resolve("job");
				inputDir = jobDir.resolve("input");

				try {
					DirCreator.createDirs(jobJarDir, inputDir);
				} catch (IOException diskException) {

					LOGGER.log(Level.WARNING, "Creation of dir failed.", diskException);
					userContext.send(new JobRejected(job.jobId(),
							"Job was rejected due to error on server. Please try again later."));
					jobRegistry.remove(job.jobId());

					continue;
				}

				// send the confirmation
				userContext.send(new JobRegistered(job.jobId()));
			} else if (received instanceof JobJarStart jobJar) {

				jobJarName = jobJar.jobJarFilename();
			} else if (received instanceof FileChunk fileChunk) {

				Path workDir = fileChunk.fileName().equals(jobJarName) ? jobJarDir : inputDir;

				if (!jobSpec.inputFiles().contains(fileChunk.fileName())) {

					internalFailJobRejection(
							userContext,
							fileChunk,
							"Constraint on input files broken. Job is " +
									"rejected and cleaned from server."
					);

					continue;
				}

				try {
					fileReceiver.acceptChunkAndWrite(fileChunk, workDir).ifPresent(filepath -> {
						LOGGER.info("File received and saved on: " + filepath);
						// todo: anything else???
					});

					userContext.send(new FileChunkAck());  // send ack so the client can continue file chunk sending
				} catch (IOException diskException) {
					LOGGER.log(Level.WARNING, "Error when working with files. Disk exception happened.", diskException);

					internalFailJobRejection(
							userContext,
							fileChunk,
							"Server error occurred whilest working with files. Please try again."
					);
				}
			} else if (received instanceof JobJarEnd jobJarEnd) {

				LOGGER.fine("All job jar bytes received for job: " + jobJarEnd.jobId());
			} else if (received instanceof InputFilesStart ignored) {

				LOGGER.fine("Receiving input files...");
			} else if (received instanceof InputFilesEnd filesReceived) {

				LOGGER.fine("All input file bytes have been received for job:" + filesReceived.jobId().value());

				// transit state to READY
				jobRegistry.ready(filesReceived.jobId());

				// call the delegator/scheduler in help
				scheduler.scheduleReadyJobs();
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

	private void internalFailJobRejection(UserContext userContext, FileChunk fileChunk, String reason) throws IOException {
		// close open files
		fileReceiver.abandon();

		// delete job dir and everything inside
		if (jobDir != null)
			DirCreator.recursivelyDeleteDirOnPath(jobDir);

		// remove job
		jobRegistry.remove(fileChunk.jobId());

		// constraint broken - declare job rejected
		userContext.send(new JobRejected(fileChunk.jobId(),
				reason));
	}
}