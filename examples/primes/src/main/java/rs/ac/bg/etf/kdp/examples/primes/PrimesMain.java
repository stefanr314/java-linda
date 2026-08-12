package rs.ac.bg.etf.kdp.examples.primes;

import rs.ac.bg.etf.kdp.common.Linda;
import rs.ac.bg.etf.kdp.lindaclient.LindaFactory;

/**
 * Sample user job: seeds candidate-number tuples into the tuple space and
 * spawns {@link PrimeWorker} instances via {@code eval()} to check them.
 * Written only against the {@link Linda} interface, obtained through
 * {@link LindaFactory#get()} — no internal networking or server type is
 * referenced here.
 */
public final class PrimesMain {

    private static final int WORKER_COUNT = 4;
    private static final int CANDIDATE_COUNT = 100;

    private PrimesMain() {
    }

    //TODO: re-implement this in better fashion
    public static void main(String[] args) {
        Linda linda = LindaFactory.get();

        for (int candidate = 2; candidate < 2 + CANDIDATE_COUNT; candidate++) {
            linda.out(new String[] { "candidate", String.valueOf(candidate) });
        }

        for (int i = 0; i < WORKER_COUNT; i++) {
            linda.eval("prime-worker-" + i, new PrimeWorker());
        }
    }
}