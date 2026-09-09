package rs.ac.bg.etf.kdp.common;

import org.junit.jupiter.api.Test;
import rs.ac.bg.etf.kdp.common.protocol.*;

import java.io.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SerialFilterTest {

	private static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(
			"maxdepth=15;" +
					"maxarray=100000;" +
					"rs.ac.bg.etf.**;" +
					"java.util.*;java.lang.*;java.time.*;java.io.*;" +
					"!*"
	);

	/**
	 * Round-trips one object through the filter, exactly as a connection would.
	 */
	private static void roundTrip(Object message) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(message);
		}
		try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			in.setObjectInputFilter(FILTER);   // per-stream, so no global state in tests
			in.readObject();
		}
	}

	@Test
	void everyProtocolMessageSurvivesTheFilter() throws Exception {
		List<Message> samples = List.of(
				new ClientHello("ricci"),
				new WorkstationHello(new WorkstationInfo("ws-1", "Linux", "21", 2)),
				new JobSubmitCommand(new JobSpec("job.jar", "java -jar job.jar",
						List.of("a.txt"), List.of("out.txt"))),
				new FileChunk(new JobId("abc"), "job.jar", 0, new byte[32 * 1024], false),
				new Registered("ws-1", new HeartbeatPolicy(300, 1500)),
				new OutputFilesEnd(new JobId("abc"), List.of("out.txt")),
				new Ping(1L), new Pong(1L), new Bye());

		for (Message sample : samples) {
			assertThatCode(() -> roundTrip(sample))
					.as("%s must pass the serial filter", sample.getClass().getSimpleName())
					.doesNotThrowAnyException();
		}
	}

	@Test
	void classesOutsideTheProtocolAreRejected() {
		// Any class outside filter
		assertThatThrownBy(() -> roundTrip(javax.lang.model.SourceVersion.RELEASE_0))
				.isInstanceOf(InvalidClassException.class);
	}
}