package rs.ac.bg.etf.kdp.client;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bare client for smoke-testing the submit path end to end: connect, say hello, submit one job,
 * print whatever the server reports back.
 *
 * <p>
 * Unlike a workstation, a client is allowed to disconnect at any point and come back later for
 * results, so this deliberately keeps reading only as long as it feels like it. Everything the job
 * needs lives in the server's registry, not on this connection.
 * </p>
 */
public final class ClientMain implements AutoCloseable {

	private static final Logger LOGGER = Logger.getLogger(ClientMain.class.getName());

	/*
	Socket timeout on read
	 */
	private static final int READ_TIMEOUT_MILLIS = 30_000;

	private final Socket socket;
	private final ObjectOutputStream out;
	private final ObjectInputStream in;
	private final String user;

	public ClientMain(String serverHost, int serverPort, String user) throws IOException {
		this.socket = new Socket(serverHost, serverPort);
		this.socket.setSoTimeout(READ_TIMEOUT_MILLIS);
		this.user = user;

		this.out = new ObjectOutputStream(socket.getOutputStream());
		this.out.flush();
		this.in = new ObjectInputStream(socket.getInputStream());
	}

	public static void main(String[] args) {
		String host = args.length > 0 ? args[0] : "localhost";
		int port = args.length > 1 ? Integer.parseInt(args[1]) : 4040;

		// todo: job submit process entails the actual job jar
		// A job that needs nothing: prints a line and exits 0. Enough to prove the workstation can
		// actually launch a process and report back.
		JobSpec spec = new JobSpec(
				"java -version",
				List.of("lib1", "lib2", "lib3"),
				List.of());

		try (ClientMain client = new ClientMain(host, port, "Giampaolo Ricci")) {
			client.handshake(client.user);

			JobId jobId = client.submit(spec);
			// TODO: write the job id to some structure in order to query it

			LOGGER.info("Submitted " + jobId + ", watching progress...");

			client.printProgressUntilQuiet();
		} catch (IOException | ClassNotFoundException e) {
			LOGGER.log(Level.SEVERE, "Client failed", e);
		}
	}

	/**
	 * Sends the hello and fails fast if the server refuses us.
	 */
	public void handshake(String user) throws IOException, ClassNotFoundException {
		send(new ClientHello(user));

		Object ack = in.readObject();
		if (ack instanceof Failure failure) {
			throw new IOException("Handshake refused: " + failure.message());
		}
		LOGGER.log(Level.INFO, "Connected: {0}", ack);
	}

	/**
	 * Submits one job and returns the id the server assigned.
	 *
	 * <p>
	 * Submission is asynchronous by design: the server answers as soon as the job is registered,
	 * long before any workstation has seen it. Anything after this is progress reporting, not part
	 * of the submission.
	 * </p>
	 */
	public JobId submit(JobSpec spec) throws IOException, ClassNotFoundException {
		send(new JobSubmitCommand(spec));

		Object response = in.readObject();

		if (response instanceof JobRegistered registered) {
			LOGGER.log(Level.INFO, "Server accepted the job as {0}", registered.jobId());
			return registered.jobId();
		} else if (response instanceof Failure failure) {
			throw new IOException("Submission refused: " + failure.message());
		} else {
			throw new IOException("Unexpected reply: " + response.getClass().getSimpleName());
		}
	}

	/**
	 * Prints progress until the connection goes quiet or the server stops talking.
	 *
	 * <p>
	 * Only for watching the flow by hand. A real client polls for status by job id instead, so
	 * it can close this connection immediately after submitting.
	 * </p>
	 */
	public void printProgressUntilQuiet() throws ClassNotFoundException {
		try {
			for (; ; ) {
				Object received = in.readObject();
				if (received instanceof Bye) return;
			}
		} catch (EOFException | SocketException e) {
			LOGGER.log(Level.INFO, "trace: ", e);
			LOGGER.info("  <- server closed the connection");
		} catch (IOException e) {
			// SocketTimeoutException lands here too: nothing arrived within READ_TIMEOUT_MILLIS,
			// which for this throwaway client just means we have seen enough.
			LOGGER.info("  <- nothing more to read (" + e.getClass().getSimpleName() + ")");
		}
	}

	private void send(Object message) throws IOException {
		out.writeObject(message);
		// Clears the back-reference table, so a message equal to an earlier one is not sent as a
		// stale back-reference.
		out.reset();
		out.flush();
	}

	@Override
	public void close() throws IOException {
		try {
			send(new Bye());
		} catch (IOException ignored) {
			// server may already be gone; closing below is what matters
		}
		socket.close();
	}
}