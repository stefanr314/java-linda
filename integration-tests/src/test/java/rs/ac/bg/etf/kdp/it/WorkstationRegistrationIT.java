package rs.ac.bg.etf.kdp.it;

import org.junit.jupiter.api.*;
import rs.ac.bg.etf.kdp.common.protocol.Failure;
import rs.ac.bg.etf.kdp.common.protocol.Reply;
import rs.ac.bg.etf.kdp.server.ServerMain;
import rs.ac.bg.etf.kdp.workstation.WorkstationMain;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

public class WorkstationRegistrationIT {
	private ServerMain server;

	private static void awaitUntil(BooleanSupplier supplier) throws InterruptedException {
		// async wait with polling mechanism (constant wait time)
		long threshold = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
		while (System.nanoTime() < threshold) {
			if (supplier.getAsBoolean()) return;
			Thread.sleep(20);
		}

		Assertions.fail("Condition not met in-time.");
	}

	@BeforeEach
	void startServer() throws IOException {
		server = new ServerMain(0); // OS to choose port
		Thread t = new Thread(() -> {
			server.serve();
		}, "server-station");

		t.setDaemon(true);  // background task, so no explicit thread kill required

		t.start();
	}

	@AfterEach
	void shutdownServer() throws IOException {
		server.close();
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void workstationStaysInRegistryAfterHandshake() throws Exception {
		// don't really care for exceptions

		try (WorkstationMain workstation = new WorkstationMain("localhost", server.port(), 2)) {
			Thread worker = new Thread(workstation::run, "workstation-hello");
			worker.setDaemon(true);
			worker.start();

			//  NOTE: since size() is based on estimated value no guarantees that in real-time operating mode the
			//  result will be exactly one so at least one is better approximation. This test will pass even with the
			//  exact value since no other threads are writing to the underlying concurrent hash map of workstation
			//  registry.
			awaitUntil(() -> server.workstationsSize() >= 1);
		}
	}

	@Test
	@Timeout(value = 8, unit = TimeUnit.SECONDS)
	void communicationFailsOnWrongHelloMessage() throws Exception {
		try (Socket socket = new Socket("localhost", server.port());
			 ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
			out.flush();
			try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
				out.writeObject(new Reply("wrong hello message"));
				out.flush();

				Object response = in.readObject();

				assertThat(response).isInstanceOf(Failure.class);
			}
		}
	}
}