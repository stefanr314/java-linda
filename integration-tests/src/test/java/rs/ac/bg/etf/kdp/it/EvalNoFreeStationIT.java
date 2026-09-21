package rs.ac.bg.etf.kdp.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import rs.ac.bg.etf.kdp.client.JobClient;
import rs.ac.bg.etf.kdp.common.*;
import rs.ac.bg.etf.kdp.common.protocol.*;
import rs.ac.bg.etf.kdp.server.ServerMain;
import rs.ac.bg.etf.kdp.server.WorkstationContext;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Public test: {@code eval()} returns a failure - not a hang - when every workstation is
 * saturated, and does not leak the slot it never managed to reserve.
 *
 * <p>A single-slot fake workstation (same raw-socket idea as {@code ClientResultsFetchIT}) is
 * saturated by a job it accepts but never finishes, so its one slot stays taken. A second,
 * unrelated job is submitted (it simply stays {@code READY}, since no station is free) purely so
 * there is a live, non-terminal job id to open a Linda connection against and call {@code eval()}
 * on.
 */
public class EvalNoFreeStationIT {

	private static final String STATION_HOSTNAME = "fake-eval-station";

	private ServerMain server;

	private static void send(ObjectOutputStream out, Object message) throws IOException {
		out.writeObject(message);
		out.reset();
		out.flush();
	}

	private static void awaitCondition(ThrowingBooleanSupplier condition,
									   long timeoutMillis,
									   String failureMessage)
			throws Exception {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() >= deadline) throw new AssertionError(failureMessage);
			Thread.sleep(50);
		}
	}

	@BeforeEach
	void startServer() throws IOException {
		server = new ServerMain(0); // OS picks the port
		Thread serverThread = new Thread(server::serve, "server-pool");
		serverThread.setDaemon(true);
		serverThread.start();
	}

	@AfterEach
	void stopServer() throws IOException {
		server.close();
		DirManipulator.recursivelyDeleteDirOnPath(Path.of(System.getProperty("java.io.tmpdir"), "server_jobs"));
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	void evalFailsWithoutHangingAndLeavesNoSlotLeakedWhenSaturated(@TempDir Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("job.jar"), "not a real jar, just needs to exist",
				StandardCharsets.UTF_8);

		CountDownLatch workstationRegistered = new CountDownLatch(1);
		Thread fakeWorkstation = new Thread(
				() -> runStuckSingleSlotWorkstation(workstationRegistered), "fake-eval-station");
		fakeWorkstation.setDaemon(true);
		fakeWorkstation.start();

		assertThat(workstationRegistered.await(10, TimeUnit.SECONDS)).isTrue();

		try (JobClient client = new JobClient(
				"localhost", server.port(), "it-user", tempDir.resolve("job-history.log"))) {

			JobSpec spec = new JobSpec("job.jar", "java -jar job.jar", List.of(), List.of());

			// consumes the station's only slot; the fake workstation accepts it and then never
			// reports it finished, so the slot stays taken for the rest of the test
			JobId stuckJobId = client.submit(spec, tempDir);
			awaitCondition(() -> client.queryStatus(stuckJobId) != JobStatus.READY, 10_000,
					"stuck job never left READY; the fake workstation's slot was never consumed");

			// no free station left, so this one just sits in the ready queue - it exists only so
			// there is a live job id to open a Linda connection against
			JobId waitingJobId = client.submit(spec, tempDir);

			WorkstationContext station = server.workstations().find(STATION_HOSTNAME).orElseThrow();
			int slotsBeforeEval = station.availableSlots();
			assertThat(slotsBeforeEval).isZero();

			Object reply = callEval(waitingJobId);
			assertThat(reply).isInstanceOf(Failure.class);
			assertThat(((Failure) reply).message()).contains("no free workstation for eval");

			assertThat(station.availableSlots())
					.as("the failed eval must not have leaked or altered the station's slot count")
					.isEqualTo(slotsBeforeEval);
		}
	}

	private Object callEval(JobId jobId) throws IOException, ClassNotFoundException {
		try (Socket socket = new Socket("localhost", server.port());
			 ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
			out.flush();

			try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
				send(out, new LindaHello(jobId));

				Object registered = in.readObject();
				assertThat(registered).isInstanceOf(LindaRegistered.class);

				send(out, new Eval("blocked-worker", new byte[]{1, 2, 3}));
				return in.readObject();
			}
		}
	}

	private void runStuckSingleSlotWorkstation(CountDownLatch registered) {
		try (Socket socket = new Socket("localhost", server.port());
			 ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
			out.flush();

			try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
				send(out, new WorkstationHello(new WorkstationInfo(
						STATION_HOSTNAME,
						"test-os",
						"17",
						1))
				);

				Object ack = in.readObject();
				if (!(ack instanceof Registered)) return;
				registered.countDown();

				for (; ; ) {
					Object message = in.readObject();

					if (message instanceof Ping ping) {
						send(out, new Pong(ping.timeNanos()));
					} else if (message instanceof JobDispatch dispatch) {
						send(out, new JobAccepted(dispatch.jobId()));
					}
					// InputFilesStart / input FileChunk / InputFilesEnd: deliberately never
					// answered - the job (and the station's one slot) stays stuck
				}
			}
		} catch (IOException | ClassNotFoundException endOfCommunication) {
			// server or test tore the connection down; nothing left to do
		}
	}

	@FunctionalInterface
	private interface ThrowingBooleanSupplier {
		boolean getAsBoolean() throws Exception;
	}
}