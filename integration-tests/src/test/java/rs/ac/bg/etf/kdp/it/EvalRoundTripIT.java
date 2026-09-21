package rs.ac.bg.etf.kdp.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import rs.ac.bg.etf.kdp.client.JobClient;
import rs.ac.bg.etf.kdp.common.DirManipulator;
import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;
import rs.ac.bg.etf.kdp.common.JobStatus;
import rs.ac.bg.etf.kdp.server.ServerMain;
import rs.ac.bg.etf.kdp.workstation.WorkstationMain;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Public test: a job calls {@code eval()} twice with a Runnable that writes one tuple each, the
 * parent reads both tuples back with {@code in()}, and the job completes.
 *
 * <p>Unlike the other integration tests, this one needs a job that actually runs (its main class
 * calls {@code Linda.eval}), so a real {@link WorkstationMain} - not a raw-socket stand-in - is
 * started in process, and the job jar is compiled and packaged on the fly since no example module
 * already exercises {@code eval()}.
 */
public class EvalRoundTripIT {

	private static final String PARENT_MAIN_CLASS = "rs.ac.bg.etf.kdp.it.evaljob.EvalParentMain";

	private static final String TUPLE_WRITER_SOURCE = """
			package rs.ac.bg.etf.kdp.it.evaljob;
			
			import rs.ac.bg.etf.kdp.common.Linda;
			import rs.ac.bg.etf.kdp.lindaclient.LindaFactory;
			
			import java.io.Serializable;
			
			public final class TupleWriter implements Runnable, Serializable {
				private final String value;
			
				public TupleWriter(String value) {
					this.value = value;
				}
			
				@Override
				public void run() {
					Linda linda = LindaFactory.get();
					linda.out(new String[]{"result", value});
				}
			}
			""";

	private static final String EVAL_PARENT_MAIN_SOURCE = """
			package rs.ac.bg.etf.kdp.it.evaljob;
			
			import rs.ac.bg.etf.kdp.common.Linda;
			import rs.ac.bg.etf.kdp.lindaclient.LindaFactory;
			
			import java.nio.charset.StandardCharsets;
			import java.nio.file.Files;
			import java.nio.file.Path;
			import java.nio.file.StandardOpenOption;
			
			public final class EvalParentMain {
				public static void main(String[] args) throws Exception {
					Linda linda = LindaFactory.get();
			
					linda.eval("w1", new TupleWriter("from-w1"));
					linda.eval("w2", new TupleWriter("from-w2"));
			
					String[] t1 = {"result", null};
					linda.in(t1);
					String[] t2 = {"result", null};
					linda.in(t2);
			
					Files.writeString(Path.of("result.txt"),
							t1[1] + System.lineSeparator() + t2[1] + System.lineSeparator(),
							StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				}
			}
			""";

	private ServerMain server;
	private WorkstationMain workstation;
	private Thread workstationThread;

	private static void awaitCondition(BooleanSupplier condition, long timeoutMillis, String failureMessage)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() >= deadline) throw new AssertionError(failureMessage);
			Thread.sleep(50);
		}
	}

	/**
	 * Compiles {@link #EVAL_PARENT_MAIN_SOURCE} and {@link #TUPLE_WRITER_SOURCE} and packages them,
	 * with a manifest naming the parent as {@code Main-Class}, into {@code <tempDir>/job.jar} - the
	 * job jar {@link JobClient#submit} expects to find under {@code tempDir}.
	 */
	private static void buildEvalJobJar(Path tempDir) throws IOException {
		// Firstly CREATE DIRS package pandans and write SOURCE CODE to files
		Path pkgDir = tempDir.resolve("src").resolve("rs/ac/bg/etf/kdp/it/evaljob");
		Files.createDirectories(pkgDir);

		Path tupleWriterSrc = pkgDir.resolve("TupleWriter.java");
		Path parentMainSrc = pkgDir.resolve("EvalParentMain.java");
		Files.writeString(tupleWriterSrc, TUPLE_WRITER_SOURCE, StandardCharsets.UTF_8);
		Files.writeString(parentMainSrc, EVAL_PARENT_MAIN_SOURCE, StandardCharsets.UTF_8);

		// COMPILE SOURCE CODE
		Path classesDir = tempDir.resolve("classes");
		Files.createDirectories(classesDir);

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertThat(compiler).as("this test needs to run on a JDK, not a JRE").isNotNull();

		int result = compiler.run(null, null, null,
				"-d", classesDir.toString(),
				"-cp", System.getProperty("java.class.path"),
				tupleWriterSrc.toString(),
				parentMainSrc.toString());
		assertThat(result).as("dynamic compilation of the eval test job must succeed").isZero();

		// MAKE THE JAR, Manifest first
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, PARENT_MAIN_CLASS);

		Path jarPath = tempDir.resolve("job.jar");
		try (JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(jarPath), manifest);
			 Stream<Path> classFiles = Files.walk(classesDir).filter(Files::isRegularFile)) {

			for (Path classFile : classFiles.toList()) {
				String entryName = classesDir.relativize(classFile).toString().replace('\\', '/');
				jarOut.putNextEntry(new JarEntry(entryName));
				Files.copy(classFile, jarOut);
				jarOut.closeEntry();
			}
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
		if (workstation != null) workstation.close();
		if (workstationThread != null) workstationThread.interrupt();
		server.close();
		DirManipulator.recursivelyDeleteDirOnPath(Path.of(System.getProperty("java.io.tmpdir"), "server_jobs"));
	}

	@Test
	@Timeout(value = 40, unit = TimeUnit.SECONDS)
	void evalTwiceWritesTwoTuplesParentReadsBothBack(@TempDir Path tempDir) throws Exception {
		buildEvalJobJar(tempDir);

		// capacity 3: one slot for the parent job, one each for the two eval workers it spawns (1 + 2 = 3 :))
		workstation = new WorkstationMain("localhost", server.port(), 3);
		workstationThread = new Thread(workstation::run, "test-workstation");
		workstationThread.setDaemon(true);
		workstationThread.start();

		awaitCondition(() -> server.workstationsSize() >= 1, 10_000,
				"workstation never registered with the server");

		try (JobClient client = new JobClient(
				"localhost", server.port(), "it-user", tempDir.resolve("job-history.log"))) {

			JobSpec spec = new JobSpec(
					"job.jar",
					"java -jar job.jar",
					List.of(),
					List.of("result.txt")
			);
			JobId jobId = client.submit(spec, tempDir);

			JobStatus finalStatus = client.awaitCompletion(jobId, 200, 30_000);
			assertThat(finalStatus).isEqualTo(JobStatus.DONE);

			Path resultsDir = tempDir.resolve("results");
			List<Path> written = client.fetchResults(jobId, resultsDir);

			Path resultFile = resultsDir.resolve("job_" + jobId.value()).resolve("result.txt");
			assertThat(written).contains(resultFile);

			List<String> lines = Files.readAllLines(resultFile, StandardCharsets.UTF_8);
			assertThat(lines).containsExactlyInAnyOrder("from-w1", "from-w2");
		}
	}
}