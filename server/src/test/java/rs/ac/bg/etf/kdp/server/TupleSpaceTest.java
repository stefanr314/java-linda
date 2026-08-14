package rs.ac.bg.etf.kdp.server;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import rs.ac.bg.etf.kdp.common.exceptions.SuspendedTupleSpaceException;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class TupleSpaceTest {
	private TupleSpace tupleSpace;

	@BeforeEach
	void setUpTupleSpace() {
		tupleSpace = new TupleSpace();
	}

	@Test
	void outCommandRejectsNullValuesAsEntryValues() {
		String[] strings = {"tag", null};

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> tupleSpace.out(strings));
	}

	@Test
	void outCommandRejectsNullTuple() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> tupleSpace.out(null));
	}

	@Test
	void outCommandAddsNewTupleInSpace() {
		String[] tuple = {"tag", "5"};

		tupleSpace.out(tuple);

		assertThat(tupleSpace).extracting("tuples", as(InstanceOfAssertFactories.LIST))
				.hasSize(1);
	}

	@Test
	void inCommandBlocksForMatch() throws ExecutionException, InterruptedException, TimeoutException {
		String[] template = {"tag", null};

		CompletableFuture<String[]> matcher = CompletableFuture.supplyAsync(() -> {
			tupleSpace.in(template);

			return template;
		});

		assertThatExceptionOfType(TimeoutException.class)
				.isThrownBy(() -> matcher.get(200, TimeUnit.MILLISECONDS));

		tupleSpace.out(new String[]{"tag", "1"});

		String[] result = matcher.get(2, TimeUnit.SECONDS);
		assertThat(result).containsExactly("tag", "1");
	}

	@Test
	void inCommandFillsTheTemplateAfterMatch() {
		tupleSpace.out(new String[]{"tag", "1"});
		tupleSpace.out(new String[]{"tag", "2", "hello"});

		//wait for match
		String[] template = {"tag", null};
		tupleSpace.in(template);

		assertThat(template).containsExactly("tag", "1");
	}

	@Test
	void inCommandRemovesTupleFromSpace() {
		tupleSpace.out(new String[]{"tag", "1"});

		String[] template = {"tag", null};
		tupleSpace.in(template);

		assertThat(tupleSpace)
				.extracting("tuples", as(InstanceOfAssertFactories.LIST))
				.isEmpty();
	}

	@Test
	void inpCommandReturnFalseWhenNoMatchPresent() {
		String[] template = {"tag", null};

		assertThat(tupleSpace.inp(template)).isFalse();
	}

	@Test
	void inpCommandReturnsTrueWhenMatchPresent() {
		tupleSpace.out(new String[]{
				"tag", "1"
		});
		String[] template = {"tag", null};

		assertThat(tupleSpace.inp(template)).isTrue();
		assertThat(template).containsExactly("tag", "1");
	}

	@Test
	void inpCommandRemovesTupleFromSpace() {
		tupleSpace.out(new String[]{
				"tag", "1"
		});
		String[] template = {"tag", null};

		tupleSpace.inp(template);

		assertThat(tupleSpace)
				.extracting("tuples", as(InstanceOfAssertFactories.LIST))
				.isEmpty();
	}

	@Test
	void rdCommandBlocksForMatch() throws ExecutionException, InterruptedException, TimeoutException {
		String[] template = {"tag", null};

		CompletableFuture<String[]> matcher = CompletableFuture.supplyAsync(() -> {
			tupleSpace.rd(template);
			return template;
		});

		assertThatExceptionOfType(TimeoutException.class)
				.isThrownBy(() -> matcher.get(200, TimeUnit.MILLISECONDS));

		tupleSpace.out(new String[]{"tag", "1"});

		String[] result = matcher.get(2, TimeUnit.SECONDS);
		assertThat(result).containsExactly("tag", "1");
	}

	@Test
	void rdCommandLeavesMatchingTupleInSpace() {
		String[] tuple = {"tag", "1"};
		tupleSpace.out(tuple);

		tupleSpace.rd(new String[]{"tag", null});

		assertThat(tupleSpace).extracting("tuples", as(InstanceOfAssertFactories.LIST))
				.hasSize(1)
				.contains(tuple, atIndex(0));
	}

	@Test
	void rdpCommandReturnsFalseWhenNoMatch() {
		String[] template = {"tag", null};

		assertThat(tupleSpace.rdp(template)).isFalse();
	}

	@Test
	void rdpCommandDoesNotRemoveTupleFromTupleSpace() {
		String[] tuple = {"tag", "1"};
		String[] template = {"tag", null};

		tupleSpace.out(tuple);

		assertThat(tupleSpace.rdp(template)).isTrue();
		assertThat(tupleSpace).extracting("tuples", as(InstanceOfAssertFactories.LIST))
				.hasSize(1)
				.contains(tuple, atIndex(0));
	}

	@Test
	void closeOnTupleSpaceShutdownsAllWaitingThreadsWithExceptionThrown() {
		String[] template = {"tag", null};

		CompletableFuture<String[]> matcher = CompletableFuture.supplyAsync(() -> {

			tupleSpace.in(template);

			return template;
		});
		tupleSpace.close();

		assertThatExceptionOfType(ExecutionException.class)
				.isThrownBy(() -> matcher.get(2, TimeUnit.SECONDS))
				.withCauseInstanceOf(SuspendedTupleSpaceException.class);
	}

	@Test
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	void waitingThreadEventuallyFindsMatchWrittenByWritingThread() throws InterruptedException {
		String[] template = {"tag", null};

		ExecutorService executor = Executors.newFixedThreadPool(2);
		executor.submit(() -> {
			tupleSpace.in(template);

		});
		executor.submit(() -> {
			tupleSpace.out(new String[]{"tag", "1"});
		});

		executor.shutdown();

		boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
		if (!terminated) {
			executor.shutdownNow();
		}

		assertThat(terminated).isTrue();
		assertThat(template).containsExactly("tag", "1");
		assertThat(tupleSpace).extracting("tuples", as(InstanceOfAssertFactories.LIST))
				.isEmpty();
	}
}