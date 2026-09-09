package rs.ac.bg.etf.kdp.client;

import rs.ac.bg.etf.kdp.common.FileChunkSender;
import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;
import rs.ac.bg.etf.kdp.common.JobStatus;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StreamCorruptedException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * End-user facing client used to submit jobs to the server and, later, check on or retrieve them.
 *
 * <p>Deliberately not tied to a single live connection. The assignment lets a client disconnect
 * immediately after submitting and come back at any point, so every operation here works from a
 * {@link JobId} alone: {@link #disconnect()} closes the socket without touching the job, and
 * {@link #connect()} opens a fresh one. Nothing about a running job depends on this object
 * existing.
 *
 * <p>Status queries and result retrieval are not implemented yet — the server has no wire
 * endpoint to answer either, so {@link #queryStatus}, {@link #awaitCompletion} and
 * {@link #fetchResults} throw {@link UnsupportedOperationException} for now. Submission is fully
 * implemented and is what this client is for today.
 */
public final class JobClient implements AutoCloseable {

	private static final Logger LOGGER = Logger.getLogger(JobClient.class.getName());

	/**
	 * Read timeout on the socket. Generous, because a large upload can put seconds between
	 * messages, but finite so a vanished server does not hang the client forever,
	 */
	private static final int READ_TIMEOUT_MILLIS = 60_000;

	private static final Path DEFAULT_HISTORY_FILE = Path.of("job-history.log");

	private final String serverHost;
	private final int serverPort;
	private final String user;
	private final JobHistory history;

	private Socket socket;
	private ObjectOutputStream out;
	private ObjectInputStream in;

	public JobClient(String serverHost, int serverPort, String user) {
		this(serverHost, serverPort, user, DEFAULT_HISTORY_FILE);
	}

	public JobClient(String serverHost, int serverPort, String user, Path historyFile) {
		this.serverHost = serverHost;
		this.serverPort = serverPort;
		this.user = user;
		this.history = new JobHistory(historyFile);
	}

	/**
	 * The record of every job outcome this client has produced, kept across restarts.
	 */
	public JobHistory history() {
		return history;
	}

	/**
	 * Opens a connection and completes the handshake. Safe to call again after a disconnect.
	 *
	 * @throws IOException if the connection cannot be established, the handshake is refused, or
	 *                     the peer does not speak the java-linda protocol at all (a
	 *                     {@link StreamCorruptedException} from the {@link ObjectInputStream}
	 *                     constructor — e.g. a plain web server answering on that port)
	 */
	public void connect() throws IOException, ClassNotFoundException {
		if (socket != null && !socket.isClosed()) return;

		Socket newSocket = new Socket(serverHost, serverPort);
		newSocket.setSoTimeout(READ_TIMEOUT_MILLIS);

		ObjectOutputStream newOut;
		ObjectInputStream newIn;
		try {
			newOut = new ObjectOutputStream(newSocket.getOutputStream());
			newOut.flush();
			newIn = new ObjectInputStream(newSocket.getInputStream());
		} catch (StreamCorruptedException notOurServer) {
			newSocket.close();
			throw new IOException(
					"Server at " + serverHost + ":" + serverPort + " does not speak the java-linda protocol",
					notOurServer);
		} catch (IOException failure) {
			newSocket.close();
			throw failure;
		}

		socket = newSocket;
		out = newOut;
		in = newIn;

		try {
			send(new ClientHello(user));
			Object ack = read();
			if (ack instanceof Failure failure) {
				throw new IOException("Handshake refused: " + failure.message());
			}
		} catch (IOException | ClassNotFoundException failure) {
			disconnect();
			throw failure;
		}

		LOGGER.log(Level.INFO, "Connected as {0}", user);
	}

	/**
	 * Checks a job locally before it ever reaches the wire: every input file plus the job jar must
	 * exist and be readable under {@code sourceDir}. Assumes {@code spec} already passed its own
	 * constructor checks (file-count limits, blank/path-like names) — those throw from
	 * {@code new JobSpec(...)} itself and never reach this method.
	 *
	 * @return human-readable problems found; empty if the job is fine to submit
	 */
	public List<String> validate(JobSpec spec, Path sourceDir) {
		List<String> problems = new ArrayList<>();

		List<String> filesToCheck = new ArrayList<>(spec.inputFiles());
		filesToCheck.add(spec.jobFilename());

		for (String filename : filesToCheck) {
			Path path = sourceDir.resolve(filename);
			if (!Files.isRegularFile(path)) {
				problems.add("Missing file: " + filename);
			} else if (!Files.isReadable(path)) {
				problems.add("File not readable: " + filename);
			}
		}
		return problems;
	}

	/**
	 * Submits a job and uploads everything it needs to run, following the lock-step protocol
	 * exactly: send, read the one reply, only then move on. Every outcome — success or failure —
	 * is recorded to {@link #history()} the moment it is known, so a client killed right after this
	 * call returns still has the record.
	 *
	 * <p>Four phases, in this order and no other:
	 * <ol>
	 *   <li>{@link JobSubmitCommand} -&gt; {@link JobRegistered} | {@link Failure}</li>
	 *   <li>{@link InputFilesStart} -&gt; {@link ReadyToAcceptInputFiles} | {@link JobFilesFailure}</li>
	 *   <li>a {@link FileChunk} stream, with no replies read in between</li>
	 *   <li>{@link InputFilesEnd} -&gt; {@link JobQueued} | {@link JobFilesFailure}</li>
	 * </ol>
	 *
	 * @param spec      what to run
	 * @param sourceDir local directory holding the job file and every input file named in the spec
	 * @return the id the server assigned
	 * @throws IOException if the server refuses the job at any step, or a local file vanished or
	 *                     the connection broke while uploading
	 */
	public JobId submit(JobSpec spec, Path sourceDir) throws IOException, ClassNotFoundException {
		send(new JobSubmitCommand(spec));

		Object response = read();
		JobId jobId;
		if (response instanceof JobRegistered registered) {
			jobId = registered.jobId();
		} else if (response instanceof Failure failure) {
			recordHistory("-", spec.jobFilename(), "FAILED: " + failure.message());
			throw new IOException("Submission refused: " + failure.message());
		} else {
			throw new IOException("Unexpected reply: " + response.getClass().getSimpleName());
		}

		List<String> toUpload = new ArrayList<>(spec.inputFiles());
		toUpload.add(spec.jobFilename());

		try {
			send(new InputFilesStart(jobId));

			Object readyReply = read();

			if (readyReply instanceof JobFilesFailure failure) {
				throw new IOException("Server refused the job: " + failure.reason());
			} else if (!(readyReply instanceof ReadyToAcceptInputFiles)) {
				throw new IOException("Unexpected reply: " + readyReply.getClass().getSimpleName());
			}

			// Nothing cancels a client-side upload today, so the guard is a constant. The parameter
			// exists because the same sender serves directions where cancellation is real.
			new FileChunkSender(this::send).sendFiles(jobId, toUpload, sourceDir, () -> false);

			send(new InputFilesEnd(jobId));
			Object finalReply = read();
			if (finalReply instanceof JobFilesFailure failure) {
				throw new IOException("Server refused the job: " + failure.reason());
			} else if (!(finalReply instanceof JobQueued)) {
				throw new IOException("Unexpected reply: " + finalReply.getClass().getSimpleName());
			}
		} catch (IOException uploadFailed) {
			// Covers both a server-side JobFilesFailure and a local failure while streaming (a file
			// vanished after validate() ran, or the socket broke) — either way this job failed and the
			// caller may move on to the next one.
			recordHistory(jobId.value(), spec.jobFilename(), "FAILED: " + uploadFailed.getMessage());
			throw uploadFailed;
		}

		recordHistory(jobId.value(), spec.jobFilename(), "SUBMITTED");
		LOGGER.log(Level.INFO, "Submitted job {0} with {1} file(s)",
				new Object[]{jobId, toUpload.size()});
		return jobId;
	}

	private void recordHistory(String jobId, String jobFilename, String status) {
		try {
			history.append(jobId, jobFilename, status);
		} catch (IOException historyWriteFailed) {
			LOGGER.log(Level.WARNING, "Could not write to job history file", historyWriteFailed);
		}
	}

	/**
	 * Not implemented: the server has no endpoint for answering a status query yet.
	 */
	public JobStatus queryStatus(JobId jobId) throws IOException {
		throw new UnsupportedOperationException(
				"Status queries are not supported yet: the server has no endpoint for them.");
	}

	/**
	 * Not implemented: depends on {@link #queryStatus}.
	 */
	public JobStatus awaitCompletion(JobId jobId, long pollMillis, long timeoutMillis)
			throws IOException, InterruptedException {
		throw new UnsupportedOperationException(
				"awaitCompletion is not supported yet: it depends on queryStatus, which the server " +
						"cannot answer.");
	}

	/**
	 * Not implemented: the server has no endpoint for delivering result files yet.
	 */
	public List<Path> fetchResults(JobId jobId, Path targetDir) throws IOException {
		throw new UnsupportedOperationException(
				"Fetching results is not supported yet: the server has no endpoint for delivering them.");
	}

	/**
	 * Closes the current connection without aborting anything. The job keeps running.
	 */
	private void disconnect() {
		if (socket == null) return;
		try {
			send(new Bye());
		} catch (IOException ignored) {
			// server may already be gone; closing below is what matters
		}
		try {
			socket.close();
		} catch (IOException ignored) {
		}
		socket = null;
	}

	@Override
	public void close() {
		disconnect();
	}

	private void send(Object message) throws IOException {
		out.writeObject(message);

		out.reset();
		out.flush();
	}

	private void send(FileChunk chunk) throws IOException {
		send((Object) chunk);
	}

	private Object read() throws IOException, ClassNotFoundException {
		return in.readObject();
	}
}