package rs.ac.bg.etf.kdp.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import rs.ac.bg.etf.kdp.client.JobClient;
import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;
import rs.ac.bg.etf.kdp.common.JobStatus;
import rs.ac.bg.etf.kdp.common.WorkstationInfo;
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

public class LostWorkstationDecisionIT {

	private ServerMain server;

	private static void send(ObjectOutputStream out, Object message) throws IOException {
		out.writeObject(message);
		out.reset();
		out.flush();
	}

	@BeforeEach
	void startServer() throws IOException {
		server = new ServerMain(0);
		Thread serverThread = new Thread(server::serve, "server-pool");
		serverThread.setDaemon(true);
		serverThread.start();
	}

	@AfterEach
	void stopServer() throws IOException {
		server.close();
	}

	@Test
	@Timeout(value = 25, unit = TimeUnit.SECONDS)
	void runningJobOnLostWorkstationOffersDecisionAndReschedules(@TempDir Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("job.jar"), "dummy jar", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("input.txt"), "dummy input", StandardCharsets.UTF_8);

		CountDownLatch station1Registered = new CountDownLatch(1);
		CountDownLatch station1Running = new CountDownLatch(1);
		CountDownLatch stopStation1 = new CountDownLatch(1);

		Thread station1 = new Thread(() -> runFakeStation("fake-station-1", station1Registered,
				station1Running, stopStation1, null), "fake-station-1");
		station1.setDaemon(true);
		station1.start();

		assertThat(station1Registered.await(10, TimeUnit.SECONDS)).isTrue();

		try (JobClient client = new JobClient("localhost", server.port(), "it-user", tempDir.resolve("history.log"))) {
			JobSpec spec = new JobSpec("job.jar", "java -jar job.jar", List.of("input.txt"), List.of());
			JobId jobId = client.submit(spec, tempDir);

			assertThat(station1Running.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(awaitStatus(client, jobId, JobStatus.RUNNING, 10_000)).isEqualTo(JobStatus.RUNNING);

			// Disconnect workstation 1
			stopStation1.countDown();
			station1.join(5000);

			// Poll status until pending decision appears
			JobStatusResponse statusResponse = awaitPendingDecision(client, jobId, 10_000);
			assertThat(statusResponse.pendingDecisionReason()).contains("fake-station-1");
			assertThat(statusResponse.pendingDecisionReason()).contains("s remaining");

			// Start workstation 2 to receive the rescheduled job
			CountDownLatch station2Registered = new CountDownLatch(1);
			CountDownLatch station2Dispatched = new CountDownLatch(1);
			CountDownLatch stopStation2 = new CountDownLatch(1);

			Thread station2 = new Thread(() -> runFakeStation("fake-station-2", station2Registered,
					null, stopStation2, station2Dispatched), "fake-station-2");
			station2.setDaemon(true);
			station2.start();

			assertThat(station2Registered.await(10, TimeUnit.SECONDS)).isTrue();

			// Client decides to reschedule
			String rescheduleOutcome = client.rescheduleJob(jobId);
			assertThat(rescheduleOutcome).contains("Job rescheduled");

			// Workstation 2 receives the rescheduled job dispatch
			assertThat(station2Dispatched.await(10, TimeUnit.SECONDS)).isTrue();

			stopStation2.countDown();
			station2.join(5000);
		}
	}

	@Test
	@Timeout(value = 25, unit = TimeUnit.SECONDS)
	void runningJobOnLostWorkstationAbortsImmediatelyWhenClientDisconnected(@TempDir Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("job.jar"), "dummy jar", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("input.txt"), "dummy input", StandardCharsets.UTF_8);

		CountDownLatch stationRegistered = new CountDownLatch(1);
		CountDownLatch stationRunning = new CountDownLatch(1);
		CountDownLatch stopStation = new CountDownLatch(1);

		Thread station = new Thread(() -> runFakeStation("fake-station-unreachable", stationRegistered,
				stationRunning, stopStation, null), "fake-station-unreachable");
		station.setDaemon(true);
		station.start();

		assertThat(stationRegistered.await(10, TimeUnit.SECONDS)).isTrue();

		JobId jobId;
		try (JobClient client = new JobClient("localhost", server.port(), "it-user-dc", tempDir.resolve("history-dc.log"))) {
			JobSpec spec = new JobSpec("job.jar", "java -jar job.jar", List.of("input.txt"), List.of());
			jobId = client.submit(spec, tempDir);

			assertThat(stationRunning.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(awaitStatus(client, jobId, JobStatus.RUNNING, 10_000)).isEqualTo(JobStatus.RUNNING);

			// Client disconnects while job is running
			client.disconnect();
		}

		// Allow server to process client disconnection
		Thread.sleep(300);

		// Now workstation disconnects
		stopStation.countDown();
		station.join(5000);

		// Reconnect client to inspect outcome
		try (JobClient client = new JobClient("localhost", server.port(), "it-user-dc", tempDir.resolve("history-dc.log"))) {
			JobStatus status = awaitStatus(client, jobId, JobStatus.ABORTED, 10_000);
			assertThat(status).isEqualTo(JobStatus.ABORTED);

			JobStatusResponse response = client.queryStatusResponse(jobId);
			assertThat(response.pendingDecisionReason()).isBlank();
		}
	}

	private JobStatus awaitStatus(JobClient client, JobId jobId, JobStatus target, long timeoutMillis)
			throws IOException, ClassNotFoundException, InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		for (; ; ) {
			JobStatus status = client.queryStatus(jobId);
			if (status == target) return status;
			if (System.nanoTime() >= deadline) {
				throw new IllegalStateException("Timed out waiting for " + target + "; current: " + status);
			}
			Thread.sleep(50);
		}
	}

	private JobStatusResponse awaitPendingDecision(JobClient client, JobId jobId, long timeoutMillis)
			throws IOException, ClassNotFoundException, InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		for (; ; ) {
			JobStatusResponse response = client.queryStatusResponse(jobId);
			if (response.pendingDecisionReason() != null && !response.pendingDecisionReason().isBlank()) {
				return response;
			}
			if (System.nanoTime() >= deadline) {
				throw new IllegalStateException("Timed out waiting for pending decision on job " + jobId.value());
			}
			Thread.sleep(50);
		}
	}

	private void runFakeStation(String hostname, CountDownLatch registered, CountDownLatch running,
								CountDownLatch stopSignal, CountDownLatch dispatched) {
		try (Socket socket = new Socket("localhost", server.port());
			 ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
			out.flush();

			try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
				send(out, new WorkstationHello(new WorkstationInfo(hostname, "test-os",
						"17", 1)));

				Object ack = in.readObject();
				if (!(ack instanceof Registered)) return;
				if (registered != null) registered.countDown();

				Thread stopper = new Thread(() -> {
					try {
						if (stopSignal != null) {
							stopSignal.await();
							socket.close();
						}
					} catch (Exception ignored) {
					}
				});
				stopper.setDaemon(true);
				stopper.start();

				for (; ; ) {
					Object message = in.readObject();
					if (message instanceof Ping ping) {
						send(out, new Pong(ping.timeNanos()));
					} else if (message instanceof JobDispatch dispatch) {
						if (dispatched != null) dispatched.countDown();
						send(out, new JobAccepted(dispatch.jobId()));
					} else if (message instanceof InputFilesEnd inputFilesEnd) {
						send(out, new JobRunning(inputFilesEnd.jobId()));
						if (running != null) running.countDown();
					}
				}
			}
		} catch (Exception ignored) {
		}
	}
}