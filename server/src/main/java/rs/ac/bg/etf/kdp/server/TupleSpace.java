package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.Linda;
import rs.ac.bg.etf.kdp.common.TupleMatcher;
import rs.ac.bg.etf.kdp.common.exceptions.SuspendedTupleSpaceException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server-side, real storage backing a single job's tuple space. Every
 * {@link rs.ac.bg.etf.kdp.common.protocol.Message} handled by a connection
 * belonging to that job is applied directly to the {@code TupleSpace}
 * instance held by that job's {@link JobContext}; nothing here talks to a
 * socket.
 *
 * <p>Blocking operations park the calling thread in
 * {@link Condition#await()} and are woken by {@link Condition#signalAll()}
 * whenever a new tuple is published. The server runs many such threads
 * concurrently on its single cached thread pool; blocked-in-{@code
 * await()} is the expected steady state, not a failure.
 */
public final class TupleSpace implements Linda {

	private final List<String[]> tuples = new ArrayList<>();
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition tupleAvailable = lock.newCondition();
	private boolean closed = false;

	/**
	 * It is required that out command contains no null values since then blocking on null joker entries of in/rd or
	 * returning false on inp/rdp would not make sense since the joker operator will be overloaded.
	 *
	 * @param tuple - a tuple to run a validity check against.
	 */
	private static void validate(String[] tuple) {
		if (tuple == null) {
			throw new IllegalArgumentException("tuple must not be null");
		}

		for (String field : tuple) {
			if (field == null) {
				throw new IllegalArgumentException("tuple fields must not be null");
			}
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * This implementation stores tuples on server side stored tuple space executed concurrently by multiple
	 * threads. Upon releasing the lock notify all waiting threads since no guarantees is provided on which tuple
	 * is inserted at runtime.
	 * </p>
	 *
	 * @param tuple the tuple to store; no field may be {@code null}
	 */
	@Override
	public void out(String[] tuple) {
		validate(tuple);

		lock.lock();
		try {
			tuples.add(tuple.clone());

			tupleAvailable.signalAll();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void in(String[] tuple) {
		try {
			waitMatchAndRemove(tuple, true);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SuspendedTupleSpaceException();
		}
	}

	@Override
	public boolean inp(String[] tuple) {
		return tryMatchAndRemove(tuple, true);
	}


	@Override
	public void rd(String[] tuple) {
		try {
			waitMatchAndRemove(tuple, false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SuspendedTupleSpaceException();
		}
	}

	@Override
	public boolean rdp(String[] tuple) {
		return tryMatchAndRemove(tuple, false);
	}

	@Override
	public void eval(String name, Runnable thread) {
		throw new UnsupportedOperationException("not yet implemented");
	}

	/**
	 * An api method for closing the tuple space as reaction to aborted state. This actions also acquires the lock
	 * and since happens-before is guaranteed no visibility on lock flag i.e. volatile field is required.
	 */
	public void close() {
		lock.lock();

		try {
			closed = true;
			tupleAvailable.signalAll();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Caller must hold {@link #lock}. Is tuple space happened to be closed from outside as a reaction to aborted
	 * state for example threads will not wait no matter the invariant.
	 * <p>
	 * Special suspended-interrupt action is allowed to occur, so waiting thread on condition's wait queue are
	 * required to throw new exception which resembles the suspended action emerge.
	 * </p>
	 */
	private String[] awaitMatch(String[] template) throws InterruptedException {
		String[] match;

		while ((match = findMatch(template)) == null) {
			if (closed) {
				throw new SuspendedTupleSpaceException();
			}

			tupleAvailable.await();
		}

		return match;
	}

	/**
	 * Caller must hold {@link #lock}.
	 */
	private String[] findMatch(String[] template) {
		Objects.requireNonNull(template);
		
		for (String[] candidate : tuples) {
			if (TupleMatcher.matches(candidate, template)) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Helper method which blocks and removes the tuple from tuple space upon match. Waiting is interruptible.
	 * Private method for handling the in and rd commands.
	 *
	 * @param tuple  - template to check the match for
	 * @param remove - flag to sign whether the tuple is to be removed from space.
	 * @throws InterruptedException - if interrupted during the wait on condition object.
	 */
	private void waitMatchAndRemove(String[] tuple, boolean remove) throws InterruptedException {
		String[] match;

		lock.lock();

		try {
			// check if template has a match with tuple
			match = awaitMatch(tuple);

			if (remove) tuples.remove(match);
		} finally {
			lock.unlock();
		}

		// filling is done outside the lock since no data race can occur on local variables; also its better
		// practice is to hold the lock for shorter duration
		TupleMatcher.fillIn(tuple, match);
	}

	/**
	 * Helper method which tries finding a match and returns false when no match found, instead of blocking. Private
	 * method used by inp and rdp.
	 *
	 * @param tuple  - a template to look for match
	 * @param remove - a flag for removing an element
	 * @return match success flag
	 */
	private boolean tryMatchAndRemove(String[] tuple, boolean remove) {
		String[] match;

		lock.lock();
		try {
			if ((match = findMatch(tuple)) == null) return false;

			if (remove) tuples.remove(match);
		} finally {
			lock.unlock();
		}

		TupleMatcher.fillIn(tuple, match);
		return true;
	}
}