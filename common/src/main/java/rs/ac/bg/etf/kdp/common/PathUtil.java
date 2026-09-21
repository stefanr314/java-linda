package rs.ac.bg.etf.kdp.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PathUtil {

	public static Path getLindaClientPath() throws IOException {
		Path cwd = Path.of("").toAbsolutePath();

		while (cwd != null) {
			if (dirContainsLindaDir(cwd)) {
				return cwd.resolve("linda-client")
						.resolve("target")
						.resolve("linda-client-1.0-SNAPSHOT.jar");
			}
			cwd = cwd.getParent();
		}

		throw new IOException("Linda client not found on project path");
	}

	/**
	 * {@code linda-client} declares {@code common} as an ordinary Maven dependency, not a shaded
	 * one, so a launched job's classpath needs this jar too - anything reachable through
	 * {@link rs.ac.bg.etf.kdp.common.Linda} (the interface itself, {@code JobId}, ...) lives here,
	 * not in the linda-client jar.
	 */
	public static Path getCommonPath() throws IOException {
		Path cwd = Path.of("").toAbsolutePath();

		while (cwd != null) {
			if (dirContainsCommonModule(cwd)) {
				return cwd.resolve("common")
						.resolve("target")
						.resolve("common-1.0-SNAPSHOT.jar");
			}
			cwd = cwd.getParent();
		}

		throw new IOException("Common module not found on project path");
	}

	public static Path getServerBasePath() {
		Path cwd = Path.of("").toAbsolutePath();

		while (cwd != null) {
			if (Files.exists(cwd.resolve("server"))) {
				return cwd.resolve("server");
			}
			cwd = cwd.getParent();
		}

		return Path.of("").toAbsolutePath();
	}

	private static boolean dirContainsLindaDir(Path dir) {
		return Files.exists(dir.resolve("linda-client"));
	}

	private static boolean dirContainsCommonModule(Path dir) {
		return Files.exists(dir.resolve("common"));
	}
}