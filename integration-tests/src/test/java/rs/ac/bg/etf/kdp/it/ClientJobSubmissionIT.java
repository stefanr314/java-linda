package rs.ac.bg.etf.kdp.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import rs.ac.bg.etf.kdp.client.ClientMain;
import rs.ac.bg.etf.kdp.client.JobClient;
import rs.ac.bg.etf.kdp.client.JobHistory;
import rs.ac.bg.etf.kdp.common.DirCreator;
import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.server.ServerMain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Public test 1 (submit) and public test 6 (skip invalid jobs), exercised together against a real
 * {@link ServerMain}: one valid job round-trips submit -&gt; queued, one invalid job (missing input
 * file) is rejected locally and never reaches the wire, and both outcomes land in the job history.
 */
public class ClientJobSubmissionIT {

	private ServerMain server;

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
		DirCreator.recursivelyDeleteDirOnPath(Path.of(System.getProperty("java.io.tmpdir"), "server_jobs"));
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	void validJobSubmitsWhileInvalidJobIsSkipped(@TempDir Path tempDir) throws Exception {
		Files.writeString(
				tempDir.resolve("job.jar"),
				"not a real jar, just needs to exist",
				StandardCharsets.UTF_8
		);
		Files.writeString(tempDir.resolve("input.txt"), "some input", StandardCharsets.UTF_8);

		Path configFile = tempDir.resolve("jobs.cfg");
		Files.writeString(configFile, String.join(System.lineSeparator(),
				"job.jar|java -jar job.jar|input.txt|output.txt",
				"missing.jar|java -jar missing.jar||"));

		Path historyFile = tempDir.resolve("job-history.log");

		try (JobClient jobClient = new JobClient("localhost", server.port(), "it-user", historyFile)) {
			jobClient.connect();

			List<JobId> submitted = new ClientMain(jobClient).submitJobsFrom(configFile);

			assertThat(submitted).hasSize(1);

			List<JobHistory.Entry> history = jobClient.history().loadAll();
			assertThat(history).anySatisfy(entry -> {
				assertThat(entry.jobFilename()).isEqualTo("job.jar");
				assertThat(entry.status()).isEqualTo("SUBMITTED");
			});
			assertThat(history).anySatisfy(entry -> {
				assertThat(entry.jobFilename()).isEqualTo("missing.jar");
				assertThat(entry.status()).startsWith("NOT_SUBMITTED");
			});
		}
	}
}