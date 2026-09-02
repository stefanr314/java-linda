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
		}
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

				// IF DELEGATED CALL SCHEDULER TO DELEGATE ONCE AGAIN (I hope so)
			} else if (received instanceof Bye ignored) {
				// client closed the connection everything should keep running anyway
				return;
			} else {
				messageSink.send(new Failure("Unknown message received: " + received.getClass()));
			}
		}
	}
}