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

	private static boolean dirContainsLindaDir(Path dir) {
		return Files.exists(dir.resolve("linda-client"));
	}
}