package rs.ac.bg.etf.kdp.server;

import rs.ac.bg.etf.kdp.common.protocol.FileChunk;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public final class FileChunkReceiver {

	private final static Logger LOGGER = Logger.getLogger(FileChunkReceiver.class.getName());

	/*
	Hash map is enough here since no more than one thread will call this objects method for writing to files. IF THIS
	 EVER CHANGES THE SYNCHRONIZATION IS REQUIRED.
	 */
	private final Map<Path, OutputStream> openFileDescriptorsMap = new HashMap<>();

	private static OutputStream apply(Path newFilePath) {
		OutputStream outputStream = null;
		try {
			outputStream = Files.newOutputStream(newFilePath);
		} catch (IOException e) {
			LOGGER.severe("Failed to open the stream towards the file. Check the path");
		}
		return outputStream;
	}

	public Optional<? extends Path> acceptChunkAndWrite(FileChunk chunk, Path writeToPath, String sentFromOs) throws IOException {
		String targetFileSeparator = sentFromOs.toLowerCase(Locale.ROOT).contains("windows") ? "\\" : "/";
		String normalizedPath = chunk.fileName().replace(targetFileSeparator, File.separator);

		// helper that checks whether the file is logs dir
		Path helperPath;
		if ((helperPath = Path.of(normalizedPath).getParent()) != null) {
			if (helperPath.startsWith("logs")) {
				Files.createDirectories(writeToPath.resolve(helperPath));
			}
		}

		// create path to the filename - writeToPath.resolve(chunk.filename())
		Path filePath = writeToPath.resolve(normalizedPath).normalize();
		// open the stream to it - create the new stream if filename has not yet been seen in map
		OutputStream out = openFileDescriptorsMap.computeIfAbsent(filePath, FileChunkReceiver::apply);

		// check whether the chunk is last for filename - return path to the filename if so otherwise return empty
		if (chunk.last()) {
			openFileDescriptorsMap.remove(filePath).close();
			return Optional.of(filePath);
		} else {
			// write the chunk of data (bytes[]) (DO THE RESEARCH ON HOW FREQUENTLY I WRITE TO FILE WITH THIS APPROACH)
			out.write(chunk.data());
			// do not close the file leave it open
			return Optional.empty();
		}
	}
}