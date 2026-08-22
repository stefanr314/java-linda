package rs.ac.bg.etf.kdp.server;

import java.io.IOException;

public class ClientHandler implements ConnectionHandler {
	@Override
	public void run() throws IOException, ClassNotFoundException {
		throw new UnsupportedOperationException("To be implemented");

		// when I take a better a look all of this can be done in a loop right away - just check the request type

		// request types - job generation, job check, job abort, iF JOB IS FINISHED but CLIENT IS NOT REACHABLE WRITE
		// IT TO SOME STRUCTURE - so then check whether that client has a job pending for him and return it straight
		// away

		// check for data - should be jar data (read in chunks) WHAT TO DO IF NOT - send(new Failure) return;
		// PERFORMED ON CLIENT OUT CHANNEL NOT THE WORKSTATION ONE - THIS SHOULD ALSO BE SEPARATED IN SOME CLIENT
		// CONTEXT

		// SO THIS HANDLER HAS TO FIRSTLY CHECK WHETHER THE CLIENT HAS JOB IN CLIENT CONTEXT - CLIENT CAN BE
		// DESCRIBED WITH THE HELP OF SOCKET.port (this is the limitation since there is no registration of clients
		// to the app)

		// job id generation - this should be received tbh

		// reach for the scheduler - i.e. registry

		// await for the response

		// if found send job request to the workstation use out channel (should be forwarded from factory)

		// wait to see the job in job registry - this is the responsibility of workstation handler to write it to the
		// registry if everything is fine - either block until i see ws handler has written da ws accepted the job or
		// attach an listener

		// if not present just return the message that station is not available right now

		// if this line reached just run the loop listen for clients requests - check job status, perhaps he

	}
}