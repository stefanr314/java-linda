package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.WorkstationInfo;
import rs.ac.bg.etf.kdp.common.protocol.Bye;
import rs.ac.bg.etf.kdp.common.protocol.Failure;
import rs.ac.bg.etf.kdp.common.protocol.Ping;
import rs.ac.bg.etf.kdp.common.protocol.Pong;

import java.io.IOException;
import java.io.ObjectInput;

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

	private final CloseableMessageSink messageSink;
	private final ObjectInput in;
	private final WorkstationRegistrator registrator;
	private final WorkstationInfo info;

	public WorkstationHandler(CloseableMessageSink messageSink, ObjectInput in,
							  WorkstationRegistrator registrator,
							  WorkstationInfo info) {
		this.messageSink = messageSink;
		this.in = in;
		this.registrator = registrator;
		this.info = info;
	}

	@Override
	public void run() throws IOException, ClassNotFoundException {
		// create context - create context prior to check whether the station has already registered context
		WorkstationContext context = registrator.register(info, messageSink);

		// run the loop which serves the communication with the workstation
		try {
			loop(context);
		} finally {
			// once the socket is closed (no matter the reason) this is the only place to deregister the workstation
			// from registrator; otherwise dead workstation can be picked as candidate for processing jobs

			registrator.unregister(context);
		}
	}

	private void loop(WorkstationContext context) throws IOException, ClassNotFoundException {
		for (; ; ) {
			Object message = in.readObject();

			if (message instanceof Pong pong) {
				long now = System.nanoTime();
				context.reportAt(now);
				context.recordRTT(now - pong.returnNanoTime());
			} else if (message instanceof Ping ping) {
				// workstation should not ping server but that type of communication is not harmful tbh...
				context.reportAt(System.nanoTime());
				context.send(new Pong(ping.timeNanos()));
//			} else if (message instanceof JobStarted jobStarted) {
//				// workstation has accepted and started the job
//
//				// CHANGE THE STATUS TO RUNNING - this is the end of chain of job submition
			} else if (message instanceof Bye ignored) {
				return;
			} else {
				context.send(new Failure("Unknown message type provided: " + message.getClass().getSimpleName()));
			}
		}
	}
}