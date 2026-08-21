package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.WorkstationInfo;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler dedicated for working with workstations. Handle all the communication, registering the stations. All the
 * exceptions are just propagated to the server as this is fine and expected behaviour of workstations. Workstations
 * can not disconnect like clients.
 *
 * <p>
 * Thread confined so no thread safety required.
 * </p>
 */
public class WorkstationHandler implements ConnectionHandler {

	private static final Logger LOGGER = Logger.getLogger(WorkstationHandler.class.getName());

	private final Socket socket;
	private final ObjectInput in;
	private final ObjectOutputStream out;
	private final WorkstationRegistry registry;
	private final WorkstationInfo info;

	public WorkstationHandler(Socket socket, ObjectInput in, ObjectOutputStream out, WorkstationRegistry registry, WorkstationInfo info) {
		this.socket = socket;
		this.in = in;
		this.out = out;
		this.registry = registry;
		this.info = info;
	}

	@Override
	public void run() throws IOException, ClassNotFoundException {
		// create context
		WorkstationContext context = new WorkstationContext(info, socket, out);

		// register the workstation
		registry.register(context);

		LOGGER.info(() -> "Registered workstation " + context);
		// send out confirmation message
		context.send(new Reply("Successful registration for workstation: " + context.hostName()));

		// run the loop which serves the communication with the workstation
		try {
			loop(context);
		} finally {
			// once the socket is closed (no matter the reason) this is the only place to deregister the workstation
			// from registry; otherwise dead workstation can be picked as candidate for processing jobs
			if (!registry.unregister(context)) {
				LOGGER.log(Level.SEVERE, "Workstation was not removed from registry space. Dead instance lurking " +
						"around.");
			} else {
				LOGGER.info(() -> "Workstation unregistered " + context);
			}
		}
	}

	private void loop(WorkstationContext context) throws IOException, ClassNotFoundException {
		for (; ; ) {
			Object message = in.readObject();

			if (message instanceof Pong pong) {
				context.reportAt(System.nanoTime());
				context.recordRTT(System.nanoTime() - pong.returnNanoTime());
			} else if (message instanceof Ping ping) {
				// workstation should not ping server but that type of communication is not harmful tbh...
			} else if (message instanceof Bye ignored) {
				return;
			} else {
				context.send(new Failure("Unknown message type provided: " + message.getClass().getSimpleName()));
			}
		}
	}
}