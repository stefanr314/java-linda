package rs.ac.bg.etf.kdp.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import rs.ac.bg.etf.kdp.common.WorkstationInfo;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WorkstationRegistryTest {
	private WorkstationRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new WorkstationRegistry();
	}

	private WorkstationContext context(String host, int capacity) throws IOException {
		return new WorkstationContext(
				new WorkstationInfo(host, "TestOS", "17", capacity),
				() -> {
				},
				new ObjectOutputStream(OutputStream.nullOutputStream()));
	}

	@Test
	void freeStationIsFoundWithItsSlotAlreadyClaimed() throws IOException {
		WorkstationContext station = context("ws-1", 1);
		registry.register(station);

		assertThat(registry.tryFindFreeStation()).contains(station);
		assertThat(registry.tryFindFreeStation()).isEmpty();
	}

	@Test
	void slotsAreExhaustedAcrossEveryRegisteredStation() throws IOException {
		registry.register(context("ws-1", 2));
		registry.register(context("ws-2", 3));

		int claimed = 0;
		while (registry.tryFindFreeStation().isPresent()) claimed++;

		assertThat(claimed).isEqualTo(5);
	}

	@Test
	void releasedSlotBecomesAvailableAgain() throws IOException {
		WorkstationContext station = context("ws-1", 1);
		registry.register(station);

		registry.tryFindFreeStation().orElseThrow().releaseSlot();

		assertThat(registry.tryFindFreeStation()).contains(station);
	}

	@Test
	void unregisterDoesNotEvictAReplacementRegisteredUnderTheSameName() throws IOException {
		WorkstationContext stale = context("ws-1", 1);
		WorkstationContext fresh = context("ws-1", 1);
		registry.register(stale);
		registry.register(fresh);

		assertThat(registry.unregister(stale)).isFalse();
		assertThat(registry.find("ws-1")).contains(fresh);
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void concurrentSeekersNeverExceedTotalCapacity() throws Exception {
		registry.register(context("ws-1", 2));
		registry.register(context("ws-2", 3));

		int seekers = 40;
		CountDownLatch startLine = new CountDownLatch(1);
		AtomicInteger claimed = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(seekers);

		for (int i = 0; i < seekers; i++) {
			pool.submit(() -> {
				startLine.await();
				if (registry.tryFindFreeStation().isPresent()) claimed.incrementAndGet();
				return null;
			});
		}
		startLine.countDown();

		pool.shutdown();
		assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		assertThat(claimed.get()).isEqualTo(5);
	}
}