package rs.ac.bg.etf.kdp.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Helper class for creating and deleting dirs.
 */
public final class DirCreator {

	private static final Logger LOGGER = Logger.getLogger(DirCreator.class.getName());

	public static void createDir(Path path) throws IOException {
		Objects.requireNonNull(path);

		Files.createDirectories(path);
	}

	public static void createDirs(Path path1, Path path2) throws IOException {
		Files.createDirectories(Objects.requireNonNull(path1));

		Files.createDirectories(Objects.requireNonNull(path2));
	}

	public static void createDirs(Path path1, Path path2, Path... rest) throws IOException {
		Objects.requireNonNull(rest);

		createDirs(path1, path2);

		for (Path path : rest) {
			Files.createDirectories(path);
		}
	}

	/**
	 * Method for recursively deleting the dir by walking it DFS from target path.
	 * <p>
	 * This method does not throw since it's clients can call it in catch blocks (as a result of improper
	 * behaviour e.g. cleanup functions).
	 * </p>
	 *
	 * @param target target path to which stream paths are relative to.
	 */
	public static void recursivelyDeleteDirOnPath(Path target) {
		Objects.requireNonNull(target);

		try (Stream<Path> walk = Files.walk(target);) {
			walk.sorted(Comparator.reverseOrder())
					.forEach(path -> {
						try {
							Files.delete(path);
						} catch (IOException e) {
							LOGGER.log(Level.WARNING, "Exception upon trying to delete the file on path: " + path, e);
						}
					});
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Exception when deleting the abandoned job dir", e);
		}
	}
}