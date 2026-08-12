package rs.ac.bg.etf.kdp.examples.primes;

import java.io.Serializable;

import rs.ac.bg.etf.kdp.common.Linda;
import rs.ac.bg.etf.kdp.lindaclient.LindaFactory;

/**
 * Sample {@code eval()} worker: repeatedly takes a candidate number tuple
 * out of the tuple space, tests it for primality, and publishes the
 * verdict back. Implements both {@link Runnable} and {@link Serializable}
 * so it can be shipped to a workstation and deserialized there, as
 * required by {@link Linda#eval}.
 *
 * <p>Uses only {@link LindaFactory#get()} to obtain its {@link Linda}
 * handle, exactly like the job's own main class would.
 */
public final class PrimeWorker implements Runnable, Serializable {

    @Override
    public void run() {
        Linda linda = LindaFactory.get();
        throw new UnsupportedOperationException("not yet implemented");
    }
}
