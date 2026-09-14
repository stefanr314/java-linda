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
 * Exercises {@code AbortJobCommand} end to end against a real {@link ServerMain}: submit a job,
 * let it reach {@code RUNNING} on a faked workstation (same "ghost workstation" idea as
 * {@code ClientResultsFetchIT}/{@code HeartbeatMechanismIT}), abort it through {@link JobClient},
 * then have the still-connected fake station report a late {@code JobFailed} - simulating a
 * supervisor thread that reports failure after its process was already destroyed. The terminal
 * {@code ABORTED} status must not be disturbed by that late report, and the station's slot must be
 * back at full capacity.
 */
public class ClientAbortJobIT {

	private static final String STATION_HOSTNAME = "fake-station";
	private static final int STATION_CAPACITY = 1;

	private ServerMain server;

	private static void send(ObjectOutputStream out, Object message) throws IOException {
		out.writeObject(message);
		out.reset();
		out.flush();
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
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	void abortRunningJobIsTerminalAndReleasesStationSlotOnce(@TempDir Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("job.jar"), "not a real jar, just needs to exist",
				StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("input.txt"), "some input", StandardCharsets.UTF_8);

		CountDownLatch workstationRegistered = new CountDownLatch(1);
		CountDownLatch lateFailureReported = new CountDownLatch(1);
		Thread fakeWorkstation = new Thread(
				() -> runFakeWorkstation(workstationRegistered, lateFailureReported), "fake-workstation");
		fakeWorkstation.setDaemon(true);
		fakeWorkstation.start();

		assertThat(workstationRegistered.await(10, TimeUnit.SECONDS)).isTrue();

		try (JobClient client = new JobClient(
				"localhost", server.port(), "it-user", tempDir.resolve("job-history.log"))) {

			JobSpec spec = new JobSpec("job.jar", "java -jar job.jar",
					List.of("input.txt"), List.of());

			JobId jobId = client.submit(spec, tempDir);

			assertThat(awaitStatus(client, jobId, JobStatus.RUNNING, 15_000)).isEqualTo(JobStatus.RUNNING);

			// station is fully occupied by the one job it was allowed to accept
			assertThat(stationSlots()).isEqualTo(0);

			String outcome = client.abortJob(jobId);
			assertThat(outcome).contains("aborted");
			assertThat(client.queryStatus(jobId)).isEqualTo(JobStatus.ABORTED);

			// the slot is released synchronously as part of handling AbortJobCommand
			assertThat(stationSlots()).isEqualTo(STATION_CAPACITY);

			// let the fake station's late JobFailed (racing in after the process was destroyed) land
			assertThat(lateFailureReported.await(10, TimeUnit.SECONDS)).isTrue();

			// ABORTED is terminal: the late report must not change it, and must not release the
			// already-released slot a second time.
			assertThat(client.queryStatus(jobId)).isEqualTo(JobStatus.ABORTED);
			assertThat(stationSlots()).isEqualTo(STATION_CAPACITY);
		}
	}

	private int stationSlots() {
		return server.workstations().find(STATION_HOSTNAME).orElseThrow().availableSlots();
	}

	/**
	 * Polls {@code queryStatus} until it reports {@code target}, bounded by {@code timeoutMillis}.
	 */
	private JobStatus awaitStatus(JobClient client, JobId jobId, JobStatus target, long timeoutMillis)
			throws IOException, ClassNotFoundException, InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

		for (; ; ) {
			JobStatus status = client.queryStatus(jobId);
			if (status == target) return status;

			if (System.nanoTime() >= deadline) {
				throw new IllegalStateException(
						"Timed out waiting for job " + jobId.value() + " to reach " + target
								+ "; last status was " + status);
			}

			Thread.sleep(50);
		}
	}

	private void runFakeWorkstation(CountDownLatch registered, CountDownLatch lateFailureReported) {
		try (Socket socket = new Socket("localhost", server.port());
			 ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
			out.flush();

			try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
				send(out, new WorkstationHello(new WorkstationInfo(
						STATION_HOSTNAME,
						"test-os",
						"17",
						STATION_CAPACITY))
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
					} else if (message instanceof InputFilesEnd inputFilesEnd) {
						send(out, new JobRunning(inputFilesEnd.jobId()));
					} else if (message instanceof AbortJobOnStation abortJobOnStation) {
						// process was "destroyed"; the supervisor thread still reports failure -
						// exactly the race this test targets.
						send(out, new JobFailed(abortJobOnStation.jobId(), "killed"));
						lateFailureReported.countDown();
					}
					// InputFilesStart / input FileChunk / ResultsReceived: nothing to do
				}
			}
		} catch (IOException | ClassNotFoundException endOfCommunication) {
			// server or test tore the connection down once the exchange above finished; nothing left to do
		}
	}
}
