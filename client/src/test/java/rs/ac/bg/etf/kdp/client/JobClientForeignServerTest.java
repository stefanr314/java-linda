package rs.ac.bg.etf.kdp.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Public test 5: a client pointed at something that is not our server (e.g. a plain web server
 * answering on that port) must fail fast with a clear message, never hang, never dump a stack
 * trace.
 */
class JobClientForeignServerTest {

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void connectFailsFastAgainstAForeignServer(@TempDir Path tempDir) throws Exception {
		try (ServerSocket serverSocket = new ServerSocket(0)) {
			Thread foreignServer = new Thread(() -> {
				try (Socket accepted = serverSocket.accept();
					 OutputStream out = accepted.getOutputStream()) {
					// Not a Java object-stream header, just like a plain HTTP server would send.
					// Enough for the ObjectInputStream constructor to reject it immediately.
					out.write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.UTF_8));
					out.flush();
				} catch (IOException ignored) {
					// socket closed once the assertion below finishes; nothing left to report
				}
			}, "foreign-server");
			foreignServer.setDaemon(true);
			foreignServer.start();

			try (JobClient client = new JobClient(
					"localhost", serverSocket.getLocalPort(), "tester", tempDir.resolve("history.log"))) {

				assertThatThrownBy(client::connect)
						.isInstanceOf(IOException.class)
						.hasMessageContaining("does not speak the java-linda protocol");
			}
		}
	}
}
