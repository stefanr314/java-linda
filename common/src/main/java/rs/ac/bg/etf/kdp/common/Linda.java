package rs.ac.bg.etf.kdp.common;

import java.io.Serializable;

/**
 * The C-Linda-style tuple space API exposed to user jobs.
 *
 * <p>A tuple is a {@code String[]}; every field is significant and typed
 * only as a string. A template used by {@link #in}, {@link #inp},
 * {@link #rd} and {@link #rdp} may contain {@code null} fields, which act
 * as wildcards during matching. Two tuples/templates match when they have
 * the same arity and every non-null template field is string-equal to the
 * corresponding tuple field.
 *
 * <p>This interface is fixed by the assignment and must be reproduced
 * verbatim. Implementations live in two places:
 * <ul>
 *   <li>{@code server.TupleSpace} — the real, in-memory storage, guarded by
 *       a {@link java.util.concurrent.locks.ReentrantLock} and a
 *       {@link java.util.concurrent.locks.Condition}.</li>
 *   <li>{@code linda-client.LindaProxy} — a stub that turns every call into
 *       a request/response pair sent over a socket to the server. Tuples
 *       never leave the server; only a job id, an operation, and a
 *       {@code String[]} cross the wire.</li>
 * </ul>
 */
public interface Linda extends Serializable {

	/**
	 * Publishes a tuple into the space.
	 *
	 * @param tuple the tuple to store; no field may be {@code null}
	 * @throws IllegalArgumentException if {@code tuple} or any of its
	 *                                  fields is {@code null}
	 */
	void out(String[] tuple);

	/**
	 * Blocks until a tuple matching {@code tuple} is available, removes it
	 * from the space, and fills every {@code null} field of {@code tuple}
	 * with the corresponding value from the matched tuple.
	 *
	 * @param tuple the template; {@code null} fields act as wildcards and
	 *              are filled in place
	 */
	void in(String[] tuple);

	/**
	 * Non-blocking variant of {@link #in}.
	 *
	 * @param tuple the template; {@code null} fields act as wildcards and
	 *              are filled in place if a match is found
	 * @return {@code true} if a matching tuple was found and removed,
	 * {@code false} otherwise
	 */
	boolean inp(String[] tuple);

	/**
	 * Blocks until a tuple matching {@code tuple} is available and fills
	 * every {@code null} field of {@code tuple} with the corresponding
	 * value from the matched tuple, without removing it from the space.
	 *
	 * @param tuple the template; {@code null} fields act as wildcards and
	 *              are filled in place
	 */
	void rd(String[] tuple);

	/**
	 * Non-blocking variant of {@link #rd}.
	 *
	 * @param tuple the template; {@code null} fields act as wildcards and
	 *              are filled in place if a match is found
	 * @return {@code true} if a matching tuple was found, {@code false}
	 * otherwise
	 */
	boolean rdp(String[] tuple);

	/**
	 * Runs {@code thread} asynchronously on a free workstation, sharing
	 * this job's tuple space.
	 *
	 * <p>{@code Runnable} is not itself {@link Serializable}: job authors
	 * must supply a class that implements both {@link Runnable} and
	 * {@link Serializable}. Lambdas will not work, since a lambda's
	 * synthetic class is not guaranteed serializable and captures its
	 * enclosing instance implicitly.
	 *
	 * @param name   a human-readable name for the spawned activity
	 * @param thread the task to run; must also implement {@link Serializable}
	 */
	void eval(String name, Runnable thread);
}