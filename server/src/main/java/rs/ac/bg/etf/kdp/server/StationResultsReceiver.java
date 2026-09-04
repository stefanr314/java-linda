package rs.ac.bg.etf.kdp.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Implementation of {@link FileChunkReceiver} that is used by server to receive output results and put them to
 * output dir. The results will sit there until received by user.
 */
public final class StationResultsReceiver extends FileChunkReceiver {

	private final String stationOs;

	public StationResultsReceiver(String stationOs) {
		this.stationOs = stationOs;
	}

	private static String normalizeFilename(String chunkFilename, String sentFromOs) {
		String targetFileSeparator = sentFromOs.toLowerCase(Locale.ROOT).contains("windows") ? "\\" : "/";
		return chunkFilename.replace(targetFileSeparator, File.separator);
	}

	@Override
	public Path calculatePath(String chunkFilename, Path basePath) throws IOException {
		String normalizedFilename = normalizeFilename(chunkFilename, stationOs);

		// helper that checks whether the file is logs dir
		Path helperPath;
		if ((helperPath = Path.of(normalizedFilename).getParent()) != null) {
			if (helperPath.startsWith("logs")) {
				Files.createDirectories(basePath.resolve(helperPath));
			}
		}

		return basePath.resolve(normalizedFilename).normalize();
	}
}