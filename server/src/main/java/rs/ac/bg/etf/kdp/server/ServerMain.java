package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.protocol.Failure;
import rs.ac.bg.etf.kdp.common.protocol.Hello;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for the central server: accepts connections from
 * {@code linda-client} and {@code workstation} processes and dispatches
 * each to a {@link ConnectionHandler} on a single, shared cached thread
 * pool.
 */
public final class ServerMain implements AutoCloseable {

	private static final Logger LOGGER = Logger.getLogger(ServerMain.class.getName());

	private static final long HEARTBEAT_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(10);
	private static final long HEARTBEAT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

	private final ExecutorService executor = Executors.newCachedThreadPool();
	private final ServerSocket serverSocket;

	private final WorkstationRegistry workstationRegistry = new WorkstationRegistry();

	private final JobRegistry jobRegistry = new JobRegistry();

	private final HeartbeatDaemon heartbeat;

	private final ConnectionHandlerFactory connectionHandlerFactory =
			new ConnectionHandlerFactory(workstationRegistry, jobRegistry);

	private final Map<Socket, Boolean> connections = new ConcurrentHashMap<>();

	private volatile boolean running;

	public ServerMain(int port) throws IOException {

		if (port < 0) throw new IllegalArgumentException();

		this.serverSocket = new ServerSocket(port);
		this.running = true;
		this.heartbeat = new HeartbeatDaemon(HEARTBEAT_INTERVAL_MILLIS, HEARTBEAT_TIMEOUT_NANOS, workstationRegistry);
	}

	public static void main(String[] args) {
		int port = (args.length > 0 && args[0] != null) ? Integer.parseInt(args[0]) : 4040;

		try (ServerMain server = new ServerMain(port)) {
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					server.close();
				} catch (IOException ignored) {
				}
			}));

			LOGGER.log(Level.INFO, "Listening on port: " + server.port());

			server.serve();
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, "IOException occurred: " + e.getMessage(), e);
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE,
					"Exception of type" + e.getClass().getSimpleName() + " occurred: " + e.getMessage(), e);
		}
	}

	/**
	 * Main method for running the server instance; accept communication init on server socket and delegate it to
	 * thread from poll. This is regular concurrent behaviour of every server.
	 *
	 */
	public void serve() {
		heartbeat.start();
		LOGGER.info("Heartbeat daemon thread started.");

		try {
			while (running) {
				Socket accepted = serverSocket.accept();
				connections.put(accepted, Boolean.TRUE);
				executor.submit(() -> handle(accepted));
			}
		} catch (IOException e) {
			if (running) LOGGER.log(Level.SEVERE, "Failed to accept the connection: " + e.getMessage(), e);
		}
	}

	/**
	 * Handle upcoming connection request; deny upon hello message not being sent. Thread confined code.
	 *
	 * @param socket - socket representing the opened communication channel
	 */
	private void handle(Socket socket) {
		// ORDER MUST BE RESPECTED -> deadlock otherwise. Socket must be closed when returning from method!
		try (socket;
			 ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
			out.flush();
			try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
				Object received = in.readObject();

				if (!(received instanceof Hello hello)) {
					// handle the failure response since entry message is not recognized
					out.writeObject(new Failure("Entry protocol message incorrect. Please provide proper hello " +
							"message type. Type received " + received.getClass().getSimpleName()));
					out.flush();
					return;
				}

				connectionHandlerFactory.getHandler(hello, socket, out, in).run();
			}
		} catch (EOFException | SocketException e) {
			// normal behaviour upon receiving sentinel value from other ended communication side;
		} catch (IOException | ClassNotFoundException e) {
			LOGGER.log(Level.SEVERE,
					"Exception of type" + e.getClass().getSimpleName()
							+ " occurred with message: " + e.getMessage(), e);
		} finally {
			connections.remove(socket);
		}
	}

	@Override
	public void close() throws IOException {
		if (!running) return;  // prevent double cleaning triggered by main thread awakened by shutdown hook

		LOGGER.log(Level.INFO, "Closing the server...");

		running = false;
		serverSocket.close();
		heartbeat.close();

		// close all the opened running connections
		connections.keySet().forEach(socket ->
		{
			try {
				socket.close();
			} catch (IOException ignored) {
			}
		});

		// shutdown the threads even the waiting ones
		executor.shutdownNow();

		try {
			if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
				LOGGER.log(Level.SEVERE, "Threads where not shutdown properly.");
			}
		} catch (InterruptedException e) {
			// re-try if interrupt happened
			executor.shutdownNow();

			Thread.currentThread().interrupt();
		}
	}

	public int workstationsSize() {
		return workstationRegistry.size();
	}

	public int port() {
		return serverSocket.getLocalPort();
	}
}