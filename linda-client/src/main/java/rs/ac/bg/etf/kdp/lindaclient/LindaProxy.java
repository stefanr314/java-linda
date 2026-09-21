package rs.ac.bg.etf.kdp.lindaclient;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.Linda;
import rs.ac.bg.etf.kdp.common.exceptions.LindaException;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client-side {@link Linda} implementation. Every call is turned into a
 * request sent over a plain {@code java.net} socket to the server and
 * blocks on {@code ObjectInputStream.readObject()} for the reply; the
 * server performs the actual matching and any blocking wait. Tuples never
 * live on the client side.
 *
 * <p>Instances are obtained through {@link LindaFactory#get()}, not
 * constructed directly by user job code.
 *
 * <p>By project specification this class implements {@link java.io.Serializable} but in current implementation
 * never crosses the network. If this ever changes the machine internal state fields must be transient. Also, that
 * deserialized instance must construct new socket and stream objects. This is currently nowhere done.</p>
 */
public final class LindaProxy implements Linda, AutoCloseable {

	private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
	private static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;
	/*
	Never actually serialized.
	 */
	@Serial
	private static final long serialVersionUID = 7811556913896239501L;

	private static final Logger LOGGER = Logger.getLogger(LindaProxy.class.getName());

	private final JobId jobId;

	private final transient Object callLock = new Object();

	private final transient Socket socket;
	private final transient ObjectOutputStream out;
	private final transient ObjectInputStream in;

	/**
	 * Creates a proxy bound to a job's tuple space. The socket connection
	 * itself is established lazily on first use.
	 *
	 * @param host  the server's host name
	 * @param port  the server's port
	 * @param jobId the job whose tuple space this proxy talks to
	 */
	public LindaProxy(String host, int port, JobId jobId) {
		this.jobId = jobId;

		try {
			this.socket = new Socket();
			this.socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
			this.socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);

			this.out = new ObjectOutputStream(this.socket.getOutputStream());
			out.flush();

			this.in = new ObjectInputStream(this.socket.getInputStream());

			// send hello messages
			out.writeObject(new LindaHello(jobId));

			// read
			Object received = in.readObject();
			if (received instanceof Failure failure) throw new LindaException(failure.message());
			if (received instanceof JobNotPresent notPresent) throw new LindaException("Job not present at server " +
					"side; check the id again. Id returned from server: " + notPresent.jobId());

			// ACK message is read at this line - just holds string "welcome linda client..." BORING

			// ready and set to use the distributed linda in java :)
			this.socket.setSoTimeout(0);  // reset the socket, waiting INF is allowed
		} catch (IOException e) {
			throw new LindaException("IO exception upon setting the communication with proxy", e);
		} catch (ClassNotFoundException e) {
			throw new LindaException("Class not found exception upon communicating with server", e);
		}

	}

	/*
	Helper method for reading the boolean result on methods such as inp, rdp.
	 */
	private static boolean booleanAftermath(String[] tuple, Object received) {
		if (received instanceof Failure failure) throw new LindaException(failure.message());
		if (received instanceof TupleReply reply) {
			System.arraycopy(reply.tuple(), 0, tuple, 0, tuple.length);
			return true;
		} else if (received instanceof BoolReply bool) {
			return bool.value();
		} else {
			throw new LindaException("Result never arrived in right form");
		}
	}

	/*
	Helper method for reacting on the received in, rd results.
	 */
	private static void receivedAftermath(String[] tuple, Object received) {
		if (received instanceof Failure failure) throw new LindaException(failure.message());
		if (received instanceof TupleReply reply) {
			// reply.tuple(); this is the RESULT

			// just copy the result into destination tuple
			System.arraycopy(reply.tuple(), 0, tuple, 0, tuple.length);
		} else {
			throw new LindaException("Result never arrived in right form");
		}
	}

	private static void ackCheck(Object received) {
		if (received instanceof Failure failure) throw new LindaException(failure.message());
		if (!(received instanceof Ack)) throw new LindaException("error on communication to server");
	}

	@Override
	public void out(String[] tuple) {
		try {
			Objects.requireNonNull(tuple);

			Object received = call(new Out(tuple));
			ackCheck(received);
		} catch (IOException | ClassNotFoundException e) {
			// mask all the exception to unchecked ones, since interface does not declare throwing from methods
			LOGGER.log(Level.SEVERE, "IO exception occurred on out command.", e);
			throw new LindaException("IO exception on out command.", e);
		}
	}

	@Override
	public void in(String[] tuple) {
		try {
			Objects.requireNonNull(tuple);

			Object received = call(new In(tuple));

			receivedAftermath(tuple, received);
		} catch (IOException | ClassNotFoundException e) {
			// mask all the exception to unchecked ones, since interface does not declare throwing from methods
			LOGGER.log(Level.SEVERE, "IO exception occurred on in command.", e);
			throw new LindaException("IO exception on in command.", e);
		}
	}

	@Override
	public boolean inp(String[] tuple) {
		try {
			Objects.requireNonNull(tuple);

			Object received = call(new Inp(tuple));
			// return the boolean flag from received

			return booleanAftermath(tuple, received);
		} catch (IOException | ClassNotFoundException e) {
			// mask all the exception to unchecked ones, since interface does not declare throwing from methods
			LOGGER.log(Level.SEVERE, "IO exception occurred on inp command.", e);
			throw new LindaException("IO exception on inp command.", e);
		}
	}

	@Override
	public void rd(String[] tuple) {
		try {
			Objects.requireNonNull(tuple);

			Object received = call(new Rd(tuple));

			receivedAftermath(tuple, received);
		} catch (IOException | ClassNotFoundException e) {
			// mask all the exception to unchecked ones, since interface does not declare throwing from methods
			LOGGER.log(Level.SEVERE, "IO exception occurred on rd command.", e);
			throw new LindaException("IO exception on rd command.", e);
		}
	}

	@Override
	public boolean rdp(String[] tuple) {
		try {
			Objects.requireNonNull(tuple);

			Object received = call(new Rdp(tuple));
			// return the boolean flag from received

			return booleanAftermath(tuple, received);
		} catch (IOException | ClassNotFoundException e) {
			// mask all the exception to unchecked ones, since interface does not declare throwing from methods
			LOGGER.log(Level.SEVERE, "IO exception occurred on rdp command.", e);
			throw new LindaException("IO exception on rdp command.", e);
		}
	}

	@Override
	public void eval(String name, Runnable thread) {
		Objects.requireNonNull(name);

		if (!(thread instanceof Serializable)) {
			throw new IllegalArgumentException("Provided runners must implement serializable in order to be run on " +
					"properly.");
		}

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(thread);
		} catch (IOException e) {
			throw new LindaException("could not serialize the eval worker", e);
		}

		try {
			Object received = call(new Eval("worker-" + jobId.value(), bytes.toByteArray()));
			ackCheck(received);
		} catch (IOException | ClassNotFoundException e) {
			LOGGER.log(Level.SEVERE, "Exception occurred on eval command.", e);
			throw new LindaException("Exception on eval command.", e);
		}
	}

	@Override
	public void close() {
		try {
			out.close();
		} catch (IOException ignored) {
		}
		try {
			in.close();
		} catch (IOException ignored) {
		}

		try {
			socket.close();
		} catch (IOException ignored) {
		}
	}

	/*
	Private method for sending the linda commands over the net and receiving their responses. Both send and read must
	 be done under the lock or otherwise messages will be read in wrong order. Also, this reduces the concurrency if
	 code is ever run by multiple threads (the one thread blocked on in stalls all the others).
	 */
	private Object call(Object lindaCommand) throws IOException, ClassNotFoundException {
		synchronized (callLock) {
			out.writeObject(lindaCommand);
			out.reset();
			out.flush();

			return in.readObject();  // it's required to wait for ACK even if the action is non-blocking.
		}
	}
}