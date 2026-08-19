package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.protocol.ClientHello;
import rs.ac.bg.etf.kdp.common.protocol.Hello;
import rs.ac.bg.etf.kdp.common.protocol.LindaHello;
import rs.ac.bg.etf.kdp.common.protocol.WorkstationHello;

import java.io.ObjectInput;
import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * Simple Factory for generation of connection handlers. This pattern does not resemble Factory Pattern but rather a
 * good programming idiom for delegating the creational process to encapsulated instance.
 */
public class ConnectionHandlerFactory {
	private final WorkstationRegistry workstationRegistry;
	private final JobRegistry jobRegistry;

	public ConnectionHandlerFactory(WorkstationRegistry workstationRegistry, JobRegistry jobRegistry) {
		this.workstationRegistry = workstationRegistry;
		this.jobRegistry = jobRegistry;
	}

	public ConnectionHandler getHandler(Hello hello, Socket socket, ObjectOutputStream out, ObjectInput in) {
		ConnectionHandler handler = null;

		if (hello instanceof WorkstationHello wsHello) {
			handler = new WorkstationHandler(socket, in, out, workstationRegistry, wsHello.wsInfo());
		} else if (hello instanceof ClientHello clHello) {
			System.out.println("User connected " + clHello.user());
		} else if (hello instanceof LindaHello lindaHello) {
			System.out.println("Linda client connected " + lindaHello.jobId());
		}

		return handler;
	}
}