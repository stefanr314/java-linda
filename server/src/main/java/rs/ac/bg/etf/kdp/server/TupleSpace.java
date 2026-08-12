package rs.ac.bg.etf.kdp.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import rs.ac.bg.etf.kdp.common.Linda;
import rs.ac.bg.etf.kdp.common.TupleMatcher;

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

    @Override
    public void out(String[] tuple) {
        validate(tuple);

    }

    @Override
    public void in(String[] tuple) {

    }

    @Override
    public boolean inp(String[] tuple) {
       return false;
    }

    @Override
    public void rd(String[] tuple) {

    }

    @Override
    public boolean rdp(String[] tuple) {
      return false;
    }

    @Override
    public void eval(String name, Runnable thread) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /** Caller must hold {@link #lock}. */
    private String[] awaitMatch(String[] template) {
        String[] match;
        while ((match = findMatch(template)) == null) {
            try {
                tupleAvailable.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for a matching tuple", e);
            }
        }
        return match;
    }

    /** Caller must hold {@link #lock}. */
    private String[] findMatch(String[] template) {
        for (String[] candidate : tuples) {
            if (TupleMatcher.matches(candidate, template)) {
                return candidate;
            }
        }
        return null;
    }

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
}