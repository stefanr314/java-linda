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
 * <p>
 * This class will be approached by multiple threads; but this doesn't yield synchronization use since the class
 * is effectively immutable so the only requirement is to properly publish this object. Since the instance fields
 * are declared as final JMM guarantees the proper publication of the instance and it's fields. All the methods
 * that live on this object are not mutating any of the invariants so their use is thread safe.
 * </p>
 *
 * @author stefanr
 */
public class ConnectionHandlerFactory {
	private final WorkstationRegistry workstationRegistry;
	private final JobRegistry jobRegistry;

	public ConnectionHandlerFactory(WorkstationRegistry workstationRegistry, JobRegistry jobRegistry) {
		this.workstationRegistry = workstationRegistry;
		this.jobRegistry = jobRegistry;
	}

	/**
	 * Method for reaching the proper handler read from introductory message. This method is synchronization free
	 * since it enforces the stack confinement approach.
	 *
	 * @param hello  introductory message
	 * @param socket socket connection to sender
	 * @param out    stream for writing objects
	 * @param in     stream for reading objects
	 * @return handler for the rest of communication
	 */
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