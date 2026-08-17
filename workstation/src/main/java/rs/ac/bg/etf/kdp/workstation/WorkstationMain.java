package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.WorkstationInfo;

import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Headless entry point for a workstation node: connects to the server,
 * advertises its {@link rs.ac.bg.etf.kdp.common.WorkstationInfo}, and
 * waits for jobs and {@code eval()} work to run.
 *
 * <p>Pass {@code --headless} to run without a UI; see {@link
 * WorkstationGui} for the (placeholder) graphical entry point.
 */
public final class WorkstationMain {

	private final Socket socket;
	private final int parallelismCapacity;

	private final ExecutorService workers;
	private final String os;
	private final String javaVersion;

	private WorkstationMain(String hostname, int serverPort, int capacity) throws IOException {
		this.socket = new Socket(hostname, serverPort);
		this.parallelismCapacity = capacity;

		this.workers = Executors.newFixedThreadPool(parallelismCapacity);
		this.os = System.getProperty("os.name");
		this.javaVersion = getJavaVersionFromRuntime();
	}

	public static void main(String[] args) {
		List<String> arguments = Arrays.asList(args);
		boolean headless = arguments.contains("--headless");

		if (!headless) {
			WorkstationGui.main(args);
			return;
		}

		String hostname = arguments.get(0) != null ? arguments.get(0) : "localhost";
		int port = arguments.get(1) != null ? Integer.parseInt(arguments.get(1)) : 4040;
		int capacity = arguments.get(2) != null ? Integer.parseInt(arguments.get(2)) : 2;
	}

	public WorkstationInfo workstationInfo() {
		return new WorkstationInfo(os, javaVersion, parallelismCapacity);
	}

	private void connectToServer() {

	}

	private String getJavaVersionFromRuntime() {
		Runtime.Version version = Runtime.version();

		return "%d.%d".formatted(version.feature(), version.interim());
	}
}