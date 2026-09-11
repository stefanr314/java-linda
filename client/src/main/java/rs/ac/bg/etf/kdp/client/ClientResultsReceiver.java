package rs.ac.bg.etf.kdp.client;

import rs.ac.bg.etf.kdp.common.DirCreator;
import rs.ac.bg.etf.kdp.common.FileChunkReceiver;

import java.io.IOException;
import java.nio.file.Path;

public final class ClientResultsReceiver extends FileChunkReceiver {
	@Override
	public Path calculatePath(String chunkFilename, Path basePath) throws IOException {
		Path base = basePath.normalize().toAbsolutePath();
		Path target = base.resolve(chunkFilename).normalize();

		if (!target.startsWith(base)) {
			throw new IOException("File name escapes the target directory: " + chunkFilename);
		}

		// chunk filename can be logs/stderr.log so just handle that too
		Path parent = target.getParent();
		if (parent != null) DirCreator.createDir(parent);

		return target;

	}
}