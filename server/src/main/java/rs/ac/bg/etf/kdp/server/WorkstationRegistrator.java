package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.HeartbeatPolicy;
import rs.ac.bg.etf.kdp.common.WorkstationInfo;
import rs.ac.bg.etf.kdp.common.protocol.Registered;

import java.io.IOException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the registration lifecycle of a workstation: build its context, put it in the registry,
 * acknowledge it, and take it back out when the connection ends.
 *
 * <p>
 * Exists so that neither of the two obvious alternatives has to. {@link WorkstationHandler}
 * would otherwise assemble a handshake message and therefore know about heartbeat timings it never
 * uses; {@link HeartbeatDaemon} would otherwise compose protocol messages, which is not what a
 * sweep is for. Both get the policy from configuration instead, and neither calls the other.
 * </p>
 *
 * <p>Stateless apart from its collaborators, so one instance is shared by every handler thread. Relies on stack
 * confinement mostly so no synchronization needed. Rest of synchronization is delegated to the registry.</p>
 */
public final class WorkstationRegistrator {

	private static final Logger LOGGER = Logger.getLogger(WorkstationRegistrator.class.getName());

	private final WorkstationRegistry registry;
	private final HeartbeatPolicy heartbeatPolicy;

	public WorkstationRegistrator(WorkstationRegistry registry, HeartbeatPolicy heartbeatPolicy) {
		this.registry = Objects.requireNonNull(registry);
		this.heartbeatPolicy = Objects.requireNonNull(heartbeatPolicy);
	}

	/**
	 * Registers a workstation and acknowledges it with the parameters it needs.
	 *
	 * <p>If an entry already existed under this name, that connection is stale by definition — a
	 * workstation reachable on it would not be opening a second one — so it is dropped immediately
	 * rather than left for the sweep to time out.
	 * </p>
	 *
	 * <p>
	 * With the current identity scheme this replacement branch is unreachable: a workstation
	 * names itself with a fresh random id on every start, so two registrations cannot collide.
	 * It becomes live the moment names are made stable — a machine name, or one supplied on the
	 * command line — which is what a real deployment wants, since it should recognise a workstation
	 * across restarts.
	 * </p>
	 *
	 * @return the fresh context; the caller must pass it back to {@link #unregister} when done
	 * @throws IOException if the acknowledgement cannot be delivered, meaning the workstation was
	 *                     already gone by the time it finished registering
	 */
	public WorkstationContext register(WorkstationInfo info, CloseableMessageSink messageSink)
			throws IOException {
		WorkstationContext context = new WorkstationContext(info, messageSink);

		registry.register(context).ifPresent(stale -> {
			LOGGER.log(Level.INFO, "Replacing a stale registration for {0}", stale.hostName());
			stale.disconnect();
		});

		context.send(new Registered(context.hostName(), heartbeatPolicy));
		LOGGER.log(Level.INFO, "Registered workstation {0}", context);

		return context;
	}

	/**
	 * Removes a workstation once its connection has ended.
	 *
	 * <p>Must run on every exit path of the handler — a dead workstation left behind would keep
	 * being handed jobs. Removal is by context rather than by name so that a handler shutting down
	 * late cannot evict a replacement that registered under the same name in the meantime; that is
	 * also why a {@code false} result is an ordinary outcome and not an error.
	 * </p>
	 */
	public void unregister(WorkstationContext context) {
		if (registry.unregister(context)) {
			LOGGER.log(Level.INFO, "Unregistered workstation {0}", context.hostName());
		} else {
			LOGGER.log(Level.FINE,
					"Workstation {0} had already been replaced by a newer registration",
					context.hostName());
		}
	}
}