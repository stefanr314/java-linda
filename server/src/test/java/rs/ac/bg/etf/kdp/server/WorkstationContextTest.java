package rs.ac.bg.etf.kdp.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.ac.bg.etf.kdp.common.WorkstationInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class WorkstationContextTest {

	private WorkstationContext workstation;
	private int parallelism;

	@BeforeEach
	void setupContextWithoutWritingMechanism() {
		WorkstationInfo info = new WorkstationInfo("test-workstation", "my OS", "future Java 30.1", 2);
		workstation = new WorkstationContext(
				info,
				new FakeMessageSink()
		);

		parallelism = workstation.workstationInfo().parallelJobCapacity();
	}

	@Test
	void tryAcquireSlotReturnsWithRespectToTheParallelismCapacity() {
		CountDownLatch returnedTrue = new CountDownLatch(parallelism);
		CountDownLatch returnedFalse = new CountDownLatch(1);
		CountDownLatch start = new CountDownLatch(1);  // wait for simultaneous try attempts

		Runnable acquireSlot = () -> {
			try {
				start.await();
				if (workstation.tryAcquireSlot()) {
					returnedTrue.countDown();
				} else {
					returnedFalse.countDown();
				}
			} catch (InterruptedException ignored) {
			}
		};

		for (int i = 0; i < parallelism + 1; i++) {
			Thread thread = new Thread(acquireSlot);
			thread.setDaemon(true);

			thread.start();
		}
		start.countDown();

		try {
			if (!returnedFalse.await(4, TimeUnit.SECONDS) || !returnedTrue.await(4, TimeUnit.SECONDS))
				fail("Timeout elapsed, test failed.");
		} catch (InterruptedException e) {
			fail("Interrupt happened test failed.");
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void releaseSlotNeverOutreachesTheParallelismCapacity() {
		CountDownLatch passed = new CountDownLatch(2);
		Runnable slotReleaser = () -> {
			workstation.releaseSlot();
			passed.countDown();
		};

		for (int i = 0; i < 2; i++) {
			Thread thread = new Thread(slotReleaser);
			thread.setDaemon(true);

			thread.start();
		}


		try {
			if (!passed.await(4, TimeUnit.SECONDS))
				fail("Timeout elapsed, test failed.");

			assertThat(workstation.availableSlots()).isEqualTo(parallelism);
		} catch (InterruptedException e) {
			fail("Interrupt happened test failed.");
			Thread.currentThread().interrupt();
		}
	}

	static final class FakeMessageSink implements CloseableMessageSink {
		final List<Object> sent = new ArrayList<>();
		boolean closed = false;

		@Override
		public void send(Object message) {
			sent.add(message);
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}