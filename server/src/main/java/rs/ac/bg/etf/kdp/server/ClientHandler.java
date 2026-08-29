package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.IOException;
import java.io.ObjectInput;
import java.util.UUID;

public class ClientHandler implements ConnectionHandler {

	private final CloseableMessageSink messageSink;
	private final ObjectInput in;
	private final JobRegistry jobRegistry;
	private final String clientConnected;
	private final Scheduler scheduler;

	public ClientHandler(CloseableMessageSink messageSink,
						 ObjectInput in,
						 JobRegistry jobRegistry,
						 String clientConnected,
						 Scheduler scheduler) {

		this.messageSink = messageSink;
		this.in = in;
		this.jobRegistry = jobRegistry;
		this.clientConnected = clientConnected;
		this.scheduler = scheduler;
	}

	@Override
	public void run() throws IOException, ClassNotFoundException {

		UserContext userContext = new UserContext(messageSink, clientConnected);
		userContext.send(new Reply("Welcome client %s.".formatted(clientConnected)));

		try {
			loop(userContext);
		} catch (IOException e) {
			// try catching the exception that are regular end time exception (server does handle this in some measure)
			throw new RuntimeException(e); //fixme
		} finally {
			userContext.disconnect();  // close the user context
		}
		// when I take a better a look all of this can be done in a loop right away - just check the request type

		// request types - job submit, job check, job abort, iF JOB IS FINISHED but CLIENT IS NOT REACHABLE WRITE
		// IT TO SOME STRUCTURE ??; it will be in Job Registry with status DONE

		// check for data - should be jar data (read in chunks) WHAT TO DO IF NOT - send(new Failure) return;
		// PERFORMED ON CLIENT OUT CHANNEL NOT THE WORKSTATION ONE - THIS SHOULD ALSO BE SEPARATED IN SOME CLIENT
		// CONTEXT ???; d fak this means first of all data does not need be jar client can make different requests
		// does not need to perform posting the jar data immediately

		// SO THIS HANDLER HAS TO FIRSTLY CHECK WHETHER THE CLIENT HAS JOB IN CLIENT CONTEXT - CLIENT CAN BE
		// DESCRIBED WITH THE HELP OF SOCKET.port (this is the limitation since there is no registration of clients
		// to the app) ??; no it does not it's fine if there is no job in job registry d fak is client context just
		// job id is required to be somehow saved on client side write it to some structure so that client can query
		// it, or not event this is obligatory client just needs to keep the program running but can explicitly close
		// the socket connection

		// job id generation - this should be received tbh ?? yes the client has to have the job id generated and saved

		// reach for the scheduler - i.e. registry ?? what registry, I need the scheduler that will use the
		// workstation reg to reach the context and send the job so scheduler is bridge between job registry and
		// workstations

		// await for the response

		// if found send job request to the workstation use out channel (should be forwarded from factory)

		// wait to see the job in job registry - this is the responsibility of workstation handler to write it to the
		// registry if everything is fine - either block until i see ws handler has written da ws accepted the job or
		// attach a listener ??; actually not quite the job should be in registry straight away just the status changes

		// if not present just return the message that station is not available right now ??; not really rather keep
		// the job in queue for some time perhaps a station will become available eventually (this keep requested job
		// time can be manipulated)


	}

	private void loop(UserContext userContext) throws IOException, ClassNotFoundException {
		for (; ; ) {
			Object received = in.readObject();

			if (received instanceof JobSubmitCommand jobSubmit) {

				// firstly add it to the job registry - create the job id.
				JobContext job = jobRegistry.register(new JobId(UUID.randomUUID().toString()), userContext,
						jobSubmit.jobSpec());

				// send the confirmation
				userContext.send(new JobRegistered(job.jobId()));

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

				// IF DELEGATED CALL SCHEDULER TO DELEGATE ONCE AGAIN (i hope so)
			} else if (received instanceof Bye ignored) {
				// client closed the connection everything should keep running anyway
				return;
			} else {
				messageSink.send(new Failure("Unknown message received: " + received.getClass()));
			}
		}
	}
}