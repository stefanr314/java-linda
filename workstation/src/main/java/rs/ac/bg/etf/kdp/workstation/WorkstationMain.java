package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.*;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Headless entry point for a workstation node: connects to the server,
 * advertises its {@link rs.ac.bg.etf.kdp.common.WorkstationInfo}, and
 * waits for jobs and {@code eval()} work to run.
 *
 * <p>Pass {@code --headless} to run without a UI; see {@link
 * WorkstationGui} for the (placeholder) graphical entry point.
 */
public final class WorkstationMain implements AutoCloseable {

	private static final Path BASE_PATH = Paths.get(System.getProperty("java.io.tmpdir"), "workstation_jobs");

	private final static Logger LOGGER = Logger.getLogger(WorkstationMain.class.getSimpleName());

	private final static long INITIAL_SO_TIMEOUT = TimeUnit.SECONDS.toMillis(60);

	private final Socket socket;
	private final ObjectOutputStream out;
	private final MessageSink sink;

	private final ExecutorService workers;

	private final JobReporter reporter;

	private final JobExecutor jobExecutor;

	private final FileChunkReceiver fileReceiver = new ClientInputFilesReceiver();

	private final String serverHostname;
	private final int serverPort;

	private final String os;
	private final String hostname;
	private final String javaVersion;
	private final int parallelismCapacity;

	public WorkstationMain(String serverHostname, int serverPort, int capacity) throws IOException {
		this.socket = new Socket(serverHostname, serverPort);
		this.socket.setSoTimeout((int) INITIAL_SO_TIMEOUT);

		this.out = new ObjectOutputStream(socket.getOutputStream());
		this.sink = new ObjectMessageSink(out);

		this.parallelismCapacity = capacity;
		this.workers = Executors.newFixedThreadPool(
				parallelismCapacity,
				(runner) -> new Thread(runner, "worker-")
		);

		this.reporter = new ReporterMessageSink(sink);

		this.serverHostname = serverHostname;
		this.serverPort = serverPort;

		this.jobExecutor = new JobExecutor(parallelismCapacity, reporter, workers);

		this.os = System.getProperty("os.name");
		this.javaVersion = getJavaVersionFromRuntime();
		this.hostname = "ws-" + UUID.randomUUID().toString().substring(0, 16);

		DirCreator.createDir(BASE_PATH);
	}

	public static void main(String[] args) {
		Map<String, String> mappedArgs = parseArgs(args);
		boolean headless = mappedArgs.containsKey("headless");

		if (!headless) {
			WorkstationGui.main(args);
			return;
		}

		String serverHostname = getFromKeyOptionsOrDefault(
				mappedArgs, new String[]{"host", "h"}, "localhost"
		);
		int serverPort = Integer.parseInt(getFromKeyOptionsOrDefault(
				mappedArgs, new String[]{"port", "p"}, "4040")
		);
		int capacity = Integer.parseInt(
				getFromKeyOptionsOrDefault(mappedArgs, new String[]{"capacity", "c"}, "2")
		);

		try (WorkstationMain workstation = new WorkstationMain(serverHostname, serverPort, capacity)) {
			LOGGER.info(workstation.workstationInfo().toString());

			// required to destroy all processes upon closing of the parent process
			Runtime.getRuntime().addShutdownHook(new Thread(workstation.jobExecutor::destroyAll));
			workstation.run();
		} catch (IOException e) {
			System.err.println("IO exception with message: " + e.getMessage());
		}
	}

	private static Map<String, String> parseArgs(String[] args) {
		Map<String, String> mappedArgs = new HashMap<>();

		// LEGIT STATES
		// --port 4090 --host localhost --capacity 2 --headless
		// -p 4090 -h localhost -c 3 --headless
		// --port=3030 --host=localhost --capacity=4 --headless
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];

			if (arg.startsWith("--") && arg.contains("=")) {
				String[] split = arg.substring(2).split("=", 2);
				mappedArgs.put(split[0], split[1]);
			} else if (arg.startsWith("-")) {
				arg = arg.replaceFirst("^-+", "");

				if (i + 1 < args.length && !args[i + 1].startsWith("-"))
					mappedArgs.put(arg, args[++i]);  // skip it for next iteration
				else
					mappedArgs.put(arg, null);
			} //handle improper values
		}

		return mappedArgs;
	}

	private static String getFromKeyOptionsOrDefault(Map<String, String> optionMap,
													 String[] options,
													 String defaultValue) {
		for (String option : options) {
			if (optionMap.containsKey(option))
				return optionMap.get(option);
		}

		return defaultValue;
	}

	public void run() {
		try (socket;
			 out) {
			out.flush();
			try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
				sink.send(new WorkstationHello(workstationInfo()));

				// covered by initial so timeout i.e. wait a minute until server responds
				Object ack = in.readObject();

				if (ack instanceof Failure failure) throw new IOException("Handshake refused: " + failure.message());
				if (ack instanceof Registered registered)
					socket.setSoTimeout(registered.heartbeatPolicy().socketTimeoutMillis());

				// the rest of communication
				waitForWork(in);
			}

		} catch (EOFException | SocketException e) {
			// server was closed
			LOGGER.log(Level.INFO, "Server closed its socket or an end of communication reached.");
		} catch (SocketTimeoutException timeout) {
			// this is the part for reconnecting with the server if server available
			LOGGER.log(
					Level.INFO,
					"Server unreachable. Try reconnecting. This implementation just closes the socket " +
							"and terminates"
			);
		} catch (IOException | ClassNotFoundException e) {
			LOGGER.log(Level.SEVERE, "Error upon trying to communicate with the server: " + e.getMessage(), e);
		}
	}

	/*
	Thread confined code.
	 */
	private void waitForWork(ObjectInput in) throws IOException, ClassNotFoundException {
		for (; ; ) {
			Object received = in.readObject();

			if (!(received instanceof Message)) {
				sink.send(new Failure("Unknown frame " + received.getClass().getSimpleName()));
				continue;
			}

			if (received instanceof Ping ping) {

				LOGGER.info("Server ping received, ponging back...");
				sink.send(new Pong(ping.timeNanos()));
			} else if (received instanceof Pong pong) {
				// server is alive - separate thread required for connection check
				// fixme dead code currently - low priority
			} else if (received instanceof JobDispatch jobDispatch) {
				// initial message request from server's scheduler (INPUT FLOW)

				if (jobExecutor.accept(jobDispatch.jobId(), jobDispatch.jobSpec())) {

					sink.send(new JobAccepted(jobDispatch.jobId()));
				} else {

					sink.send(new JobRejected(jobDispatch.jobId(), "All workers occupied."));
				}
			} else if (received instanceof InputFilesStart inputFilesStart) {
				// initial introductory message for start of receipt of input files (INPUT FLOW)

				Path jobDir = BASE_PATH
						.resolve("job_" + inputFilesStart.jobId().value());

				try {
					DirCreator.createDir(jobDir);
				} catch (IOException diskException) {
					LOGGER.log(Level.WARNING, "Internal disk exception. Dir creation failed", diskException);

					sink.send(new JobRejected(inputFilesStart.jobId(), "Internal disk error."));

					jobExecutor.jobReleaser(inputFilesStart.jobId());
				}
			} else if (received instanceof FileChunk chunk) {
				// path for receiving input files in chunks (INPUT FLOW)

				Path jobDir = BASE_PATH.resolve("job_" + chunk.jobId().value());

				try {
					fileReceiver.acceptChunkAndWrite(chunk, jobDir).ifPresent(
							filepath -> LOGGER.info(
									"File received and saved on: " + filepath
							)
					);
				} catch (IOException diskException) {
					LOGGER.log(
							Level.WARNING,
							"Error when working with files. Disk exception happened.",
							diskException
					);

					reactToFileReceiptFailure(
							chunk.jobId(),
							() -> {
								try {
									sink.send(
											new JobRejected(
													chunk.jobId(),
													"Error upon receiving job input files. " +
															"Input files have not been received."
											)
									);
								} catch (IOException e) {
									LOGGER.log(
											Level.WARNING,
											"Unable to send job rejection to server. Socket " +
													"communication broken.",
											e
									);
								}
							}
					);
				}
			} else if (received instanceof InputFilesEnd filesEnd) {

				LOGGER.info("All files received for job: " + filesEnd.jobId());

				Path jobDir = BASE_PATH.resolve("job_" + filesEnd.jobId().value());

				jobExecutor.execute(filesEnd.jobId(), jobDir, serverHostname, serverPort);

			} else if (received instanceof JobFilesFailure filesFailure) {

				// server suffered internal error - delete input dir
				reactToFileReceiptFailure(filesFailure.jobId(), () -> {
				});
			} else if (received instanceof AbortResultTransfer abortResultTransfer) {
				// raise the flag to stop the transfer of file chunks (OUTPUT FLOW)

				LOGGER.info("Server suffered internal error while receiving results for job id: "
						+ abortResultTransfer.jobId().value());

				jobExecutor.stopResultTransfer(abortResultTransfer.jobId());

			} else if (received instanceof ResultsReceived resultsReceived) {
				// server has received the results it's safe to delete the job dir
				Path jobDir = BASE_PATH.resolve("job_" + resultsReceived.jobId().value());
				DirCreator.recursivelyDeleteDirOnPath(jobDir);

			} else if (received instanceof JobNotPresent jobNotPresent) {
				// job was not found on server (INPUT FLOW)

				// fixme do something ??
				LOGGER.log(
						Level.WARNING,
						"Job not recognized by server. Job id: "
								+ jobNotPresent.jobId().value()
				);
			} else if (received instanceof Bye ignored) {
				LOGGER.info("Server sent bye message.");

				return; // communication ended
			} else {
				sink.send(new Failure("Message not recognized: " + received.getClass()));
			}
		}
	}

	@Override
	public void close() throws IOException {
		socket.close();
		workers.shutdownNow();

		try {
			if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
				LOGGER.log(Level.WARNING, "Pool was not drained.");
			}
		} catch (InterruptedException e) {
			// Re-try if interrupted.
			workers.shutdownNow();

			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Method for returning workstation info.
	 *
	 * @return workstation info value holder.
	 */
	public WorkstationInfo workstationInfo() {
		return new WorkstationInfo(hostname, os, javaVersion, parallelismCapacity);
	}

	private void reactToFileReceiptFailure(JobId jobId, Runnable reaction) throws IOException {
		fileReceiver.abandon();

		Path inputPath = BASE_PATH.resolve("job_" + jobId.value()).resolve("input");

		DirCreator.recursivelyDeleteDirOnPath(inputPath);

		jobExecutor.jobReleaser(jobId);
		reaction.run();
	}

	private String getJavaVersionFromRuntime() {
		Runtime.Version version = Runtime.version();

		return "%d.%d".formatted(version.feature(), version.interim());
	}
}