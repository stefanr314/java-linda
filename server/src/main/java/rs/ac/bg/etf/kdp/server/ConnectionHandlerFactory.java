package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.protocol.ClientHello;
import rs.ac.bg.etf.kdp.common.protocol.Hello;
import rs.ac.bg.etf.kdp.common.protocol.LindaHello;
import rs.ac.bg.etf.kdp.common.protocol.WorkstationHello;

import java.io.ObjectInput;
import java.nio.file.Path;

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

	private static final Path BASE_DIR_PATH = Path.of(System.getProperty("java.io.tmpdir"), "server_jobs");

	private final WorkstationRegistrator workstationRegistrator;
	private final JobRegistry jobRegistry;
	private final Scheduler scheduler;

	public ConnectionHandlerFactory(WorkstationRegistrator workstationRegistrator, JobRegistry jobRegistry,
									Scheduler scheduler) {
		this.workstationRegistrator = workstationRegistrator;
		this.jobRegistry = jobRegistry;
		this.scheduler = scheduler;
	}

	/**
	 * Method for reaching the proper handler read from introductory message. This method is synchronization free
	 * since it enforces the stack confinement approach.
	 *
	 * @param hello       introductory message
	 * @param messageSink writeable and closeable sink
	 * @param in          stream for reading objects
	 * @return handler for the rest of communication
	 */
	public ConnectionHandler getHandler(Hello hello, CloseableMessageSink messageSink, ObjectInput in) {
		ConnectionHandler handler = null;

		if (hello instanceof WorkstationHello wsHello) {
			handler = new WorkstationHandler(messageSink, in, workstationRegistrator, wsHello.wsInfo(), jobRegistry,
					scheduler, BASE_DIR_PATH);
		} else if (hello instanceof ClientHello clHello) {
			handler = new ClientHandler(messageSink, in, jobRegistry, clHello.user(), scheduler, BASE_DIR_PATH);
		} else if (hello instanceof LindaHello lindaHello) {
			System.out.println("Linda client connected " + lindaHello.jobId());
		}

		return handler;
	}
}