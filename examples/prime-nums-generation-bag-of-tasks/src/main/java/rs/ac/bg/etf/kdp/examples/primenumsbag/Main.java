package rs.ac.bg.etf.kdp.examples.primenumsbag;

import rs.ac.bg.etf.kdp.common.Linda;
import rs.ac.bg.etf.kdp.lindaclient.LindaFactory;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
	private static final int LIMIT = 100;
	private static final int NUM_OF_WORKERS = 4;

	public static void main(String[] args) {
		Linda linda = LindaFactory.get();

		int[] primeNumbers = new int[LIMIT];

		primeNumbers[0] = 2;
		primeNumbers[1] = 3;
		int candidate = 5;
		int numOfPrimes = 2;

		for (int i = 0; i < NUM_OF_WORKERS; i++) {
			linda.eval("worker" + i, new PrimeWorker());
		}

		// init the bag of task
		linda.out(new String[]{"candidate", String.valueOf(candidate)});

		while (numOfPrimes < LIMIT) {
			String[] resultTemplate = {"result", String.valueOf(candidate), null};  // null field is '?isPrime'

			linda.in(resultTemplate);
			if (resultTemplate[2].equals("true")) {
				// then it's prime number
				primeNumbers[numOfPrimes] = candidate;
				// write primes so worker can refresh it's state
				linda.out(new String[]{"freshPrime", String.valueOf(numOfPrimes), String.valueOf(candidate)});

				numOfPrimes++;
			}
			candidate = candidate + 2;
		}

		// poison pill for workers
		linda.out(new String[]{"stop"});

		try {
			Files.writeString(Path.of("result.txt"),
					Arrays.toString(primeNumbers),
					StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException e) {
			System.err.println("IO exception occurred.");
		}
	}

	public static final class PrimeWorker implements Runnable, Serializable {
		@Override
		public void run() {
			Linda linda = LindaFactory.get();

			int[] primes = new int[Main.LIMIT];
			primes[0] = 2;
			primes[1] = 3;
			int known = 2;

			while (true) {
				if (linda.rdp(new String[]{"stop"})) {
					return;
				}
				// take the task
				String[] candidateTemplate = {"candidate", null};
				linda.in(candidateTemplate);

				int candidate = Integer.parseInt(candidateTemplate[1]);

				// put the new task in
				linda.out(new String[]{"candidate", String.valueOf(candidate + 2)});

				boolean isPrime = true;
				for (int i = 0; primes[i] * primes[i] <= candidate; ) {
					if (candidate % primes[i] == 0) {
						isPrime = false;
						break;
					}
					i++;
					if (i >= known) {
						String[] fresh = {"freshPrime", String.valueOf(known), null};
						linda.rd(fresh);
						primes[known++] = Integer.parseInt(fresh[2]);
					}
				}
				linda.out(new String[]{"result", candidateTemplate[1], String.valueOf(isPrime)});
			}
		}
	}
}