package rs.ac.bg.etf.kdp.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PathUtil {

	public static Path getLindaClientPath() throws IOException {
		return findModuleJar("linda-client");
	}

	/**
	 * {@code linda-client} declares {@code common} as an ordinary Maven dependency, not a shaded
	 * one, so a launched job's classpath needs this jar too - anything reachable through
	 * {@link rs.ac.bg.etf.kdp.common.Linda} (the interface itself, {@code JobId}, ...) lives here,
	 * not in the linda-client jar.
	 */
	public static Path getCommonPath() throws IOException {
		return findModuleJar("common");
	}

	private static Path findModuleJar(String module) throws IOException {
		for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
			if (Files.isDirectory(dir.resolve(module))) {
				Path jar = dir.resolve(module).resolve("target").resolve(module + "-1.0-SNAPSHOT.jar");

				if (!Files.isRegularFile(jar)) {
					throw new IOException(jar + " does not exist - run 'mvn package' at the repository root");
				}
				return jar;
			}
		}
		throw new IOException("Module '" + module + "' not found above " + Path.of("").toAbsolutePath());
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
}