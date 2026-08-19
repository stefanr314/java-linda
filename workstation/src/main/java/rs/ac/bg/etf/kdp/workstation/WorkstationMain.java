package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.WorkstationInfo;
import rs.ac.bg.etf.kdp.common.protocol.Failure;
import rs.ac.bg.etf.kdp.common.protocol.WorkstationHello;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
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

	private final static Logger LOGGER = Logger.getLogger(WorkstationMain.class.getSimpleName());

	private final String hostname;

	private final Socket socket;
	private final int parallelismCapacity;

	private final ExecutorService workers;
	private final String os;
	private final String javaVersion;

	public WorkstationMain(String serverHostname, int serverPort, int capacity) throws IOException {
		this.socket = new Socket(serverHostname, serverPort);
		this.parallelismCapacity = capacity;

		this.workers = Executors.newFixedThreadPool(parallelismCapacity);
		this.os = System.getProperty("os.name");
		this.javaVersion = getJavaVersionFromRuntime();
		this.hostname = "WS - " + UUID.randomUUID().getMostSignificantBits();
	}

	public static void main(String[] args) {
		Map<String, String> mappedArgs = parseArgs(args);
		boolean headless = mappedArgs.containsKey("headless");

		if (!headless) {
			WorkstationGui.main(args);
			return;
		}

		String serverHostname = getFromKeyOptionsOrDefault(mappedArgs, new String[]{"host", "h"}, "localhost");
		int serverPort = Integer.parseInt(getFromKeyOptionsOrDefault(mappedArgs, new String[]{"port", "p"}, "4040"));
		int capacity = Integer.parseInt(getFromKeyOptionsOrDefault(mappedArgs, new String[]{"capacity", "c"}, "2"));

		try (WorkstationMain workstation = new WorkstationMain(serverHostname, serverPort, capacity)) {
			LOGGER.info(workstation.workstationInfo().toString());

			workstation.run();
		} catch (IOException e) {
			System.err.println("IO exception with message: " + e.getMessage());
		}
	}

	private static Map<String, String> parseArgs(String[] args) {
		Map<String, String> mappedArgs = new HashMap<>();

		// LEGIT STATES
		// --port 4090 --host localhost --capacity 2 --headless
		// -p 4090 -h localhost -c 3
		// --port=3030 --host=localhost --capacity=4
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
			}
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
			 ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
			out.flush();
			try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
				out.writeObject(new WorkstationHello(workstationInfo()));
				out.flush();

				Object ack = in.readObject();

				if (ack instanceof Failure failure) throw new IOException("Handshake refused: " + failure.message());

				// the rest of communication
				waitForWork(out, in);
			}

		} catch (EOFException | SocketException e) {
			// server was closed
			LOGGER.log(Level.INFO, "Server closed its socket or and of communication reached.");
		} catch (IOException | ClassNotFoundException e) {
			LOGGER.log(Level.SEVERE, "Error upon trying to communicate with the server: " + e.getMessage(), e);
		}
	}

	//TODO: implement me
	private void waitForWork(ObjectOutput out, ObjectInput in) throws IOException, ClassNotFoundException {
		for (; ; ) {

			Object o = in.readObject();
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

	public WorkstationInfo workstationInfo() {
		return new WorkstationInfo(hostname, os, javaVersion, parallelismCapacity);
	}


	private String getJavaVersionFromRuntime() {
		Runtime.Version version = Runtime.version();

		return "%d.%d".formatted(version.feature(), version.interim());
	}
}