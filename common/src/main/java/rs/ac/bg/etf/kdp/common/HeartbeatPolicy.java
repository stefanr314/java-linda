package rs.ac.bg.etf.kdp.common;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * Liveness timings, decided by the server and published to every workstation when it registers.
 *
 * <p>
 * The server owns this policy — it is the side that sweeps — so the workstation must not carry
 * its own copy in configuration. Two independently configured constants would drift, and a
 * workstation whose socket timeout is shorter than the server's ping interval would hang up on a
 * perfectly healthy server.
 * </p>
 *
 * <p>
 * Both sides need "how long may the peer stay silent", so the rule lives here once rather than
 * being re-derived at each end.
 * </p>
 *
 * @param intervalMillis how often the server pings each registered workstation
 * @param timeoutMillis  how long a workstation may stay silent before the sweep drops it; keep it a
 *                       small multiple of {@code intervalMillis} so a single lost packet does not
 *                       evict a healthy node
 */
public record HeartbeatPolicy(long intervalMillis, long timeoutMillis) implements Serializable {
	public HeartbeatPolicy {
		if (intervalMillis <= 0) throw new IllegalArgumentException("interval must be positive");
		if (timeoutMillis <= intervalMillis) {
			throw new IllegalArgumentException("timeout must exceed the ping interval");
		}
	}

	/**
	 * How long a workstation should wait on a silent socket before giving up on the server.
	 *
	 * <p>
	 * TCP does not report a peer that vanished without sending FIN or RST — a cut cable on the
	 * far side leaves the connection ESTABLISHED and the read blocked forever. A read timeout is
	 * what turns that silence into an exception.
	 * </p>
	 *
	 * @return a value comfortably larger than {@link #intervalMillis()}, so that one delayed ping
	 * does not tear down a working connection
	 */
	public int socketTimeoutMillis() {
		return Math.toIntExact(3 * intervalMillis);
	}

	public long timeoutNanos() {
		return TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
	}
}