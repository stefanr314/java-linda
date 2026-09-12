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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the server's result endpoints ({@code JobStatusQuery}/{@code JobResultQuery} and the
 * {@code OutputFilesStart}...{@code OutputFilesEnd}/{@code ResultsReceived} file transfer) end to
 * end against a real {@link ServerMain}, driven through {@link JobClient}'s
 * {@code submit}/{@code awaitCompletion}/{@code fetchResults}.
 *
 * <p>Running a real job would need a runnable job jar built by the {@code examples} module; instead
 * a workstation is faked with a raw socket that plays along with just enough of the workstation
 * protocol (same idea as the "ghost workstation" in {@code HeartbeatMechanismIT}) to carry the job
 * to {@link JobStatus#DONE} and deliver one output file.
 */
public class ClientResultsFetchIT {

	private static final String OUTPUT_FILE_NAME = "result.txt";
	private static final String OUTPUT_CONTENT = "hello from the fake workstation";

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
	void submitAwaitFetchThenSecondFetchReportsJobUnknown(@TempDir Path tempDir) throws Exception {
		Files.writeString(
				tempDir.resolve("job.jar"),
				"not a real jar, just needs to exist",
				StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("input.txt"), "some input", StandardCharsets.UTF_8);

		CountDownLatch workstationRegistered = new CountDownLatch(1);
		Thread fakeWorkstation = new Thread(
				() -> runFakeWorkstation(workstationRegistered, tempDir), "fake-workstation");
		fakeWorkstation.setDaemon(true);
		fakeWorkstation.start();

		assertThat(workstationRegistered.await(10, TimeUnit.SECONDS)).isTrue();

		try (JobClient client = new JobClient(
				"localhost", server.port(), "it-user", tempDir.resolve("job-history.log"))) {

			JobSpec spec = new JobSpec("job.jar", "java -jar job.jar",
					List.of("input.txt"), List.of(OUTPUT_FILE_NAME));

			JobId jobId = client.submit(spec, tempDir);

			JobStatus finalStatus = client.awaitCompletion(jobId, 100, 15_000);
			assertThat(finalStatus).isEqualTo(JobStatus.DONE);

			Path resultsDir = tempDir.resolve("results");
			List<Path> written = client.fetchResults(jobId, resultsDir);

			assertThat(written).containsExactly(
					resultsDir.resolve("job_" + jobId.value()).resolve(OUTPUT_FILE_NAME)
			);
			assertThat(
					Files.readString(resultsDir.resolve("job_" + jobId.value()).resolve(OUTPUT_FILE_NAME))
			).isEqualTo(OUTPUT_CONTENT);

			assertThatThrownBy(() -> client.fetchResults(jobId, resultsDir))
					.isInstanceOf(IOException.class)
					.hasMessageContaining("Job result already known. Check the history log");
		}
	}

	private void runFakeWorkstation(CountDownLatch registered, Path tempDir) {
		try (Socket socket = new Socket("localhost", server.port());
			 ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
			out.flush();

			try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
				send(out, new WorkstationHello(new WorkstationInfo(
						"fake-station",
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
					} else if (message instanceof InputFilesEnd inputFilesEnd) {
						// server has finished sending the job's input files to us; fake running it and
						// deliver one output file straight away.
						deliverFakeResults(out, inputFilesEnd.jobId(), tempDir);
					}
					// InputFilesStart / input FileChunk / ResultsReceived: nothing to do
				}
			}
		} catch (IOException | ClassNotFoundException endOfCommunication) {
			// server or test tore the connection down once the exchange above finished; nothing left to do
		}
	}

	private void deliverFakeResults(ObjectOutputStream out, JobId jobId, Path tempDir) throws IOException {
		send(out, new JobRunning(jobId));
		send(out, new JobFinished(jobId));

		Path localOutputDir = tempDir.resolve("fake-ws-output");
		Files.createDirectories(localOutputDir);
		Files.writeString(localOutputDir.resolve(OUTPUT_FILE_NAME), OUTPUT_CONTENT, StandardCharsets.UTF_8);

		FileChunkSender.SenderReport report = new FileChunkSender(chunk -> send(out, chunk))
				.sendFiles(jobId, List.of(OUTPUT_FILE_NAME), localOutputDir, () -> false);

		send(out, new OutputFilesEnd(jobId, report.delivered()));
	}
}