package rs.ac.bg.etf.kdp.client;

import rs.ac.bg.etf.kdp.common.*;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * End-user facing client used to submit jobs to the server and, later, check on or retrieve them.
 *
 * <p>Deliberately not tied to a single live connection: every public method that talks to the
 * server starts with {@link #ensureConnected()}, which opens a socket only if none is open (or the
 * previous one was closed). A caller never has to connect explicitly, and a job's lifetime belongs
 * to the server's registry, not to this object or its socket. {@link #disconnect()} stays available
 * for callers that want to close the connection explicitly; it may be called at any point without
 * affecting the job.
 */
public final class JobClient implements AutoCloseable {

	private static final Logger LOGGER = Logger.getLogger(JobClient.class.getName());
	/**
	 * Read timeout on the socket. Generous, because a large upload can put seconds between
	 * messages, but finite so a vanished server does not hang the client forever,
	 */
	private static final int READ_TIMEOUT_MILLIS = 60_000;

	private static final int HANDSHAKE_TIMEOUT_MILLIS = (int) TimeUnit.SECONDS.toMillis(5);

	private static final Path DEFAULT_HISTORY_FILE = Path.of("job-history.log");
	private static final Path RESULTS_SUMMARY_FILE = Path.of("results.txt");

	private final String serverHost;
	private final int serverPort;
	private final String user;
	private final JobHistory history;

	/**
	 * Output file names announced by a job's own {@link JobSpec}, remembered per {@link JobId} from
	 * {@link #submit} so {@link #fetchResults} can report any that never arrived. Only ever
	 * populated for jobs submitted by this very object - a job fetched by id alone (e.g. after a
	 * restart, in the repl) has no entry here, and the comparison is simply skipped for it.
	 */
	private final Map<JobId, List<String>> expectedOutputs = new HashMap<>();
	private final ObjectInputFilter filter = ObjectInputFilter.Config.createFilter(
			"maxdepth=15;" +
					"maxarray=100000;" +
					"rs.ac.bg.etf.**;" +
					"java.util.*;java.lang.*;java.time.*;java.io.*;" +
					"!*"
	);
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

			newSocket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);  // early fail silent server
			newIn = new ObjectInputStream(newSocket.getInputStream());
		} catch (StreamCorruptedException notOurServer) {
			newSocket.close();
			throw new IOException(
					"Server at " + serverHost + ":" + serverPort + " does not speak the java-linda protocol",
					notOurServer);
		} catch (SocketTimeoutException timeout) {
			newSocket.close();
			throw timeout;
		} catch (IOException failure) {
			newSocket.close();
			throw failure;
		}

		newSocket.setSoTimeout(READ_TIMEOUT_MILLIS);  // reset back
		socket = newSocket;
		out = newOut;
		in = newIn;

		in.setObjectInputFilter(filter);

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
	 * Connects if there is no live connection, or the previous one was closed. Every public method
	 * below that talks to the server calls this first, which is what makes this client lazy: nothing
	 * has to call {@link #connect()} up front. {@link #connect()} is itself idempotent (a no-op if
	 * already connected), so this is just that call under a name that says why it's there.
	 */
	private void ensureConnected() throws IOException, ClassNotFoundException {
		connect();
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
		ensureConnected();

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

		expectedOutputs.put(jobId, spec.outputFiles());
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
	 * Asks the server for a job's current status. The server keeps no subscription state for a
	 * client that may vanish at any time, so this is a single request/reply - callers wanting to
	 * wait for completion must poll it themselves, or use {@link #awaitCompletion}.
	 *
	 * @throws IOException if the job is unknown to the server or the reply is otherwise unexpected
	 */
	public JobStatus queryStatus(JobId jobId) throws IOException, ClassNotFoundException {
		ensureConnected();

		send(new JobStatusQuery(jobId));
		Object response = read();

		if (response instanceof JobStatusResponse statusResponse) {
			return statusResponse.jobStatus();
		} else if (response instanceof JobNotPresent) {
			throw new IOException("Job unknown to server: " + jobId.value());
		} else {
			throw new IOException("Unexpected reply: " + response.getClass().getSimpleName());
		}
	}

	/**
	 * Polls {@link #queryStatus} every {@code pollMillis} until the job reaches a terminal status or
	 * {@code timeoutMillis} elapses. Polling, not a server push, by design: the server keeps no
	 * subscription for a client that might disconnect at any moment.
	 *
	 * @return the terminal status reached
	 * @throws IOException if the timeout elapses first, or a query fails
	 */
	public JobStatus awaitCompletion(JobId jobId, long pollMillis, long timeoutMillis)
			throws IOException, ClassNotFoundException, InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

		for (; ; ) {
			JobStatus status = queryStatus(jobId);
			if (status.isTerminal()) return status;

			if (System.nanoTime() >= deadline) {
				throw new IOException(
						"Timed out waiting for job " + jobId.value() + " to finish; last status was " + status);
			}

			Thread.sleep(pollMillis);
		}
	}

	/**
	 * Retrieves a finished job's results, following the same lock-step pattern as {@link #submit}:
	 * {@link JobResultQuery} -&gt; {@link OutputFilesStart}, a {@link FileChunk} stream, then
	 * {@link OutputFilesEnd}. Each file is written to {@code targetDir} as its chunks arrive - never
	 * buffered whole, since a job may return files tens of megabytes large.
	 *
	 * <p>Whatever terminal outcome the server reports - the results, a failure, or an abort - this
	 * sends {@link ResultsReceived} back so the server can delete the job's temp dir; a job that was
	 * FAILED or ABORTED has no files to write but is still acknowledged the same way. Only the
	 * success path records {@code RESULTS_SAVED} to history and a summary line to {@code results.txt}.
	 *
	 * @return the local paths written, empty if the job did not finish successfully
	 * @throws IOException if the job is unknown, not finished yet, failed/aborted, or the transfer breaks
	 */
	public List<Path> fetchResults(JobId jobId, Path targetDir) throws IOException, ClassNotFoundException {
		if (tryJobStatusAlreadyKnown(jobId)) {
			throw new IOException("Job result already known. Check the history log.");
		}

		ensureConnected();

		send(new JobResultQuery(jobId));
		Object response = read();

		if (response instanceof JobNotPresent) {
			throw new IOException("Job unknown to server: " + jobId.value());
		} else if (response instanceof JobNotTerminated notTerminated) {
			throw new IOException(
					"Job has not finished yet (status=" + notTerminated.actualStatus() + "): " + jobId.value());
		} else if (response instanceof JobFailed failed) {
			send(new ResultsReceived(jobId));
			recordHistory(jobId.value(), jobFilenameFor(jobId), "FAILED: " + failed.reason());
			throw new IOException("Job failed: " + failed.reason());
		} else if (response instanceof JobAborted) {
			send(new ResultsReceived(jobId));
			recordHistory(jobId.value(), jobFilenameFor(jobId), "ABORTED");
			throw new IOException("Job was aborted: " + jobId.value());
		} else if (!(response instanceof OutputFilesStart)) {
			throw new IOException("Unexpected reply: " + response.getClass().getSimpleName());
		}

		DirCreator.createDir(targetDir);  // create the results dir

		// special receiver for clients
		FileChunkReceiver receiver = new ClientResultsReceiver();
		List<Path> written = new ArrayList<>();
		List<String> delivered;

		try {
			for (; ; ) {
				Object message = read();
				if (message instanceof FileChunk chunk) {
					receiver.acceptChunkAndWrite(chunk, targetDir).ifPresent(written::add);
				} else if (message instanceof OutputFilesEnd end) {
					delivered = end.deliveredFiles();
					break;
				} else {
					throw new IOException(
							"Unexpected reply during result transfer: " + message.getClass().getSimpleName());
				}
			}
		} catch (IOException io) {
			LOGGER.log(Level.INFO, "IO exception upon fetching the results", io);
			throw io;
		} finally {
			receiver.abandon();
		}

		List<String> expected = expectedOutputs.get(jobId);
		if (expected != null) {
			List<String> missing = expected.stream().filter(name -> !delivered.contains(name)).toList();
			if (!missing.isEmpty()) {
				LOGGER.log(Level.WARNING, "Job {0} never produced: {1}", new Object[]{jobId, missing});
			}
		}

		send(new ResultsReceived(jobId));

		recordHistory(jobId.value(), jobFilenameFor(jobId), "RESULTS_SAVED");
		appendResultsSummary(jobId.value() + "\tDONE\t" + written);

		LOGGER.log(Level.INFO, "Fetched {0} file(s) for job {1}", new Object[]{written.size(), jobId});
		return written;
	}

	/**
	 * Best-effort lookup of the filename a job was submitted under, for the history entry that
	 * {@link #fetchResults} writes. Only the history file survives a restart, so this reads the most
	 * recent entry recorded for {@code jobId}; falls back to the id itself if nothing is found.
	 */
	private String jobFilenameFor(JobId jobId) {
		try {
			return history.loadAll().stream()
					.filter(entry -> entry.jobId().equals(jobId.value()))
					.reduce((first, second) -> second)
					.map(JobHistory.Entry::jobFilename)
					.orElse(jobId.value());
		} catch (IOException historyReadFailed) {
			return jobId.value();
		}
	}

	private boolean tryJobStatusAlreadyKnown(JobId jobId) {
		try {
			return history.loadAll().stream()
					.filter(entry -> entry.jobId().equals(jobId.value()))
					.reduce((first, second) -> second)
					.map(JobHistory.Entry::status)
					.filter(lastStatus -> !lastStatus.equals("SUBMITTED"))
					.isPresent();
		} catch (IOException historyReadFailed) {
			LOGGER.info("Job status could not bee read from history log file.");
			return false;
		}
	}

	private void appendResultsSummary(String line) {
		try {
			Files.writeString(RESULTS_SUMMARY_FILE, line + System.lineSeparator(),
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Could not write to results.txt", e);
		}
	}

	/**
	 * Closes the current connection without aborting anything. The job keeps running. May be called
	 * explicitly at any point; every other public method reopens a connection lazily via
	 * {@link #ensureConnected()} if it finds none open.
	 */
	public void disconnect() {
		if (socket == null) {
			LOGGER.info("You have already disconnected from this server.");
			return;
		}
		;
		try {
			send(new Bye());
		} catch (IOException ignored) {
			// server may already be gone; closing below is what matters
		}
		try {
			socket.close();
		} catch (IOException ignored) {
		}
		LOGGER.info("You have successfully  disconnected from server: " + serverHost);
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