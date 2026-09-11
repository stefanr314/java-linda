package rs.ac.bg.etf.kdp.examples.demo_ineff_primes;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Smallest job that exercises the whole pipeline without touching Linda: it computes something,
 * writes two result files, prints to both output streams, and exits 0.
 *
 * <p>Everything is written to the <em>current working directory</em>, which is the per-job directory
 * the workstation sets on the {@code ProcessBuilder}. That is also why the file names here match
 * {@code JobSpec.outputFiles()} exactly — the workstation collects results by name after the
 * process exits, and a name that does not match is simply not sent back.
 *
 * <p>Printing to both {@code stdout} and {@code stderr} is deliberate: the workstation drains them
 * on separate threads, and a job that only wrote to one would leave half of that path untested.
 *
 * <p>Build as an executable jar named {@code demo-job.jar} with {@code Main-Class} set to this
 * class, since the demo command line is {@code java -jar demo-job.jar 20}.
 */
public class DemoPrimes {

	private DemoPrimes() {
	}

	public static void main(String[] args) {
		int limit = args.length > 0 ? Integer.parseInt(args[0]) : 20;

		System.out.println("Demo job starting, limit = " + limit);
		System.err.println("this line goes to stderr on purpose");

		List<Integer> primes = primesUpTo(limit);

		try {
			writeResults(primes, limit);
		} catch (IOException e) {
			// A non-zero exit is how a job tells the workstation it failed; the workstation turns
			// that into JobFailed rather than collecting results that do not exist.
			System.err.println("Could not write result files: " + e.getMessage());
			System.exit(1);
		}

		System.out.println("Demo job finished, wrote " + primes.size() + " primes");
	}

	private static List<Integer> primesUpTo(int limit) {
		List<Integer> primes = new ArrayList<>();

		for (int candidate = 2; candidate <= limit; candidate++) {
			if (isPrime(candidate)) primes.add(candidate);

			// Slow enough that the client actually observes RUNNING rather than seeing the job
			// finish before the first status poll.
			sleepQuietly(100);
		}
		return primes;
	}

	private static boolean isPrime(int candidate) {
		if (candidate < 2) return false;
		for (int divisor = 2; (long) divisor * divisor <= candidate; divisor++) {
			if (candidate % divisor == 0) return false;
		}
		return true;
	}

	private static void writeResults(List<Integer> primes, int limit) throws IOException {
		try (PrintWriter results = new PrintWriter(
				Files.newBufferedWriter(Path.of("result.txt"), StandardCharsets.UTF_8))) {
			primes.forEach(results::println);
		} catch (IOException resultError) {
			System.err.println("Result file err: " + resultError.getMessage());
		}

		try (PrintWriter stats = new PrintWriter(
				Files.newBufferedWriter(Path.of("stats.txt"), StandardCharsets.UTF_8))) {
			stats.println("limit=" + limit);
			stats.println("count=" + primes.size());
			stats.println("largest=" + (primes.isEmpty() ? "none" : primes.get(primes.size() - 1)));
		} catch (IOException statError) {
			System.err.println("Stat file err: " + statError.getMessage());
		}
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}