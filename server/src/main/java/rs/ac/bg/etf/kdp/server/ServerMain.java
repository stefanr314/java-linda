package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.WorkstationInfo;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for the central server: accepts connections from
 * {@code linda-client} and {@code workstation} processes and dispatches
 * each to a {@link ConnectionHandler} on a single, shared cached thread
 * pool.
 */
public final class ServerMain implements AutoCloseable {

	private final ExecutorService executor = Executors.newCachedThreadPool();
	private final ServerSocket serverSocket;

	private final Map<String, WorkstationInfo> workstations = new ConcurrentHashMap<>();

	private final Map<Socket, Boolean> connections = new ConcurrentHashMap<>();

	private volatile boolean running;

	private ServerMain(int port) throws IOException {
		if (port < 0) throw new IllegalArgumentException();

		this.serverSocket = new ServerSocket(port);
		this.running = true;
	}

	public static void main(String[] args) {
		int port = (args.length > 0 && args[0] != null) ? Integer.parseInt(args[0]) : 4040;

		try (ServerMain server = new ServerMain(port)) {
			Thread shutdownHook = new Thread(() -> {
				try {
					server.close();
				} catch (IOException ignored) {
				}
			});
			Runtime.getRuntime().addShutdownHook(shutdownHook);
			System.out.println("Listening on port: " + port);
			server.serve();
		} catch (IOException e) {
			System.err.println("IO exception with message: " + e.getMessage());
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Main method for running the server instance; accept communication init on server socket and delegate it to
	 * thread from poll. This is regular concurrent behaviour of every server.
	 *
	 * @throws IOException - upon opening server socket
	 */
	public void serve() throws IOException {
		while (running) {
			//FIXME: beware all opened connection must be properly shutdown
			Socket accepted = serverSocket.accept();
			connections.put(accepted, Boolean.TRUE);
			executor.submit(() -> handle(accepted));
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

				// TODO: write strategy with hello strategy acknowledgement
				if (hello instanceof WorkstationHello wsHello) {
					// register the workstation; TODO: this will be done by the scheduler class
					workstations.put(wsHello.host(), wsHello.wsInfo());

					// return successful registration
					out.writeObject(new Reply("Workstation %s successfully registered".formatted(wsHello.host())));
					out.flush();

					System.out.println("Workstation successfully registered with info: " + wsHello.wsInfo().toString());
				} else if (hello instanceof ClientHello clHello) {
					System.out.println("Client connected with username: " + clHello.user());
				} else if (hello instanceof LindaHello lindaHello) {
					System.out.println("JVM Linda client instance connected on job with id: " + lindaHello.jobId());
				}

				out.writeObject(new Reply("Hello acknowledged."));
				out.flush();

				proceedCommunication(out, in);
			}
		} catch (EOFException | SocketException e) {
			// normal behaviour upon receiving sentinel value from other ended communication side;
		} catch (IOException | ClassNotFoundException e) {
			System.err.println("IO Exception on opening connection with message: " + e.getMessage());
			e.printStackTrace(); //FIXME: add the logger instance
		} finally {
			connections.remove(socket);
		}
	}

	//FIXME: strategy candidate
	private void proceedCommunication(ObjectOutputStream out, ObjectInputStream in) {
		// handle the rest of communication between the sides
		System.out.println("Communication realised. U have communication with server");
//		for (; ; ) {
//			// do the job
//		}
	}

	@Override
	public void close() throws IOException {
		System.out.println("Closing the server...");
		running = false;
		serverSocket.close();

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
			var ignored = executor.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public List<String> workstation() {
		return List.copyOf(workstations.keySet());
	}
}