package rs.ac.bg.etf.kdp.common;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Helper class for creating and deleting dirs.
 */
public final class DirManipulator {

	private static final Logger LOGGER = Logger.getLogger(DirManipulator.class.getName());

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

		if (Files.isDirectory(target)) {
			try (Stream<Path> walk = Files.walk(target);) {
				walk.sorted(Comparator.reverseOrder())
						.forEach(path -> {
							try {
								Files.delete(path);
							} catch (IOException e) {
								LOGGER.log(Level.WARNING, "Exception upon trying to delete the file on path: "
										+ path, e);
							}
						});
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Exception when deleting the abandoned job dir", e);
			}
		} else {
			LOGGER.log(Level.INFO,
					"Target not directory or not found, dir was not present to be deleted. Target path: "
							+ target);
		}
	}

	/*
	Plain per-file copy (not a whole-directory read into memory) of the parent job's input dir
	into the child's - the eval worker class lives in the job jar, so the jar (and any other input
	files the parent was submitted with) must be present for the worker's process too.
	 */
	public static void copyDirContents(Path sourceDir, Path targetDir) throws IOException {
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(sourceDir)) {
			for (Path entry : entries) {
				if (Files.isRegularFile(entry)) {
					Files.copy(entry, targetDir.resolve(entry.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}
}