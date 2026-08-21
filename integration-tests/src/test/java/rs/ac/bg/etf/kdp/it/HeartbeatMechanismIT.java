package rs.ac.bg.etf.kdp.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import rs.ac.bg.etf.kdp.common.WorkstationInfo;
import rs.ac.bg.etf.kdp.common.protocol.Ping;
import rs.ac.bg.etf.kdp.common.protocol.WorkstationHello;
import rs.ac.bg.etf.kdp.server.ServerMain;
import rs.ac.bg.etf.kdp.server.WorkstationContext;
import rs.ac.bg.etf.kdp.workstation.WorkstationMain;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.fail;

public class HeartbeatMechanismIT {

	// provide enough time so that the sweeper does not disconnect live workstation (consider network traffic price
	// whilst evaluating)
	private final static long INTERVAL_MILLIS = 300L;
	private final static long TIMEOUT_MILLIS = 1500L;
	private ServerMain server;

	private static void awaitEvaluation(Supplier<Boolean> rttCalculated) throws InterruptedException {
		long threshold = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < threshold) {
			if (rttCalculated.get()) return;

			Thread.sleep(50);
		}

		fail("Timeout elapsed whilst waiting.");
	}

	@BeforeEach
	void initServer() throws IOException {
		server = new ServerMain(0, INTERVAL_MILLIS, TIMEOUT_MILLIS);
		Thread serverThread = new Thread(() -> server.serve(), "server-pool");

		serverThread.setDaemon(true);
		serverThread.start();
	}

	@AfterEach
	void closeServer() throws IOException {
		server.close();
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void heartbeatMechanismTriggersTheUpdateOfRoundTripTime() throws IOException {
		try (WorkstationMain workstationMain = new WorkstationMain("localhost", server.port(), 2)) {
			Thread workstationThread = new Thread(workstationMain::run, "workstation-unit");

			workstationThread.setDaemon(true);
			workstationThread.start();

			// only true value has logical meaning that something happened
			Supplier<Boolean> rttCalculated = () -> {
				Optional<WorkstationContext> context = server.workstations().find(workstationMain.workstationInfo().hostName());

				return context
						.filter(workstationContext -> workstationContext.roundTripTime() > 0)
						.isPresent();
			};


			// since there is no blocking behaviour (suppliers yields some value anytime) only a delayed check of
			// some field (rtt for instance) is required. Options: 1. Thread.sleep() 2. Polling 3. CountdownLatch
			// 1. Thread.sleep() is just flaky tests start to be either really long or do not pass on slow machines
			// 2. polling methods has it's costs but for this type of test checks it's fine to use.

			awaitEvaluation(rttCalculated);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();

			fail("Interrupted while waiting");
		}
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	void heartbeatDisconnectsUnavailableStation() throws InterruptedException {
		// mock station and do not respond to the ping message
		CountDownLatch workstationUnconnected = new CountDownLatch(1);

		Runnable ghostWorkstation = () -> {
			try (Socket localhost = new Socket("localhost", server.port());
				 ObjectOutputStream out = new ObjectOutputStream(localhost.getOutputStream());) {
				out.flush();
				try (ObjectInputStream in = new ObjectInputStream(localhost.getInputStream())) {
					out.writeObject(new WorkstationHello(
							new WorkstationInfo("ping-ignorant station", "ghost OS", "17.0", 2
							)));
					out.flush();

					Object ignoredAck = in.readObject();
					for (; ; ) {
						Object received = in.readObject();

						Ping ignored = (Ping) received; // ignore
					}
				}
			} catch (ClassNotFoundException | UnknownHostException ignored) {
			} catch (IOException e) {
				workstationUnconnected.countDown();
			}
		};

		Thread ghosty = new Thread(ghostWorkstation, "ghosty-workstation");
		ghosty.setDaemon(true);
		ghosty.start();


		boolean done = workstationUnconnected.await(10, TimeUnit.SECONDS);
		if (!done) fail("Timeout elapsed");
	}

}