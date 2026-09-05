package rs.ac.bg.etf.kdp.common;

import java.io.IOException;
import java.nio.file.Path;

public final class ClientInputFilesReceiver extends FileChunkReceiver {

	@Override
	public Path calculatePath(String chunkFilename, Path basePath) throws IOException {
		if (Path.of(chunkFilename).getParent() != null) throw new IOException("Only filenames allowed");

		Path filenamePath = basePath.resolve(chunkFilename).normalize();

		if (!filenamePath.startsWith(basePath)) {
			throw new IOException("Filepath contains path to which it has no access.");
		}

		return filenamePath;
	}
}