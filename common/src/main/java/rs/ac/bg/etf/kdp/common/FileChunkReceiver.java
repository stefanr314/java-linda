package rs.ac.bg.etf.kdp.common;

import rs.ac.bg.etf.kdp.common.protocol.FileChunk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

public abstract class FileChunkReceiver {

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


	/**
	 * Method for accepting the file chunks and transferring them to the actual files on server.
	 * <p>
	 * Path provided must contain the actual dir created on path. Creation of this dir is the responsibility of
	 * adequate handler that can detect the signal from client/station to retriver input/result files. These
	 * signals must be provided prior to the receiving the file chunk object -> TCP guarantees this.
	 * </p>
	 *
	 * @param chunk       an object representing the actual file chunk data being sent with metainformation.
	 * @param writeToPath path to dir in which the files will be saved.
	 * @return optional value wrapper - upon receiving sentinel value the path to stored file on disk; otherwise null.
	 * @throws IOException upon working with files.
	 */
	public Optional<? extends Path> acceptChunkAndWrite(FileChunk chunk, Path writeToPath) throws IOException {
		Objects.requireNonNull(chunk);
		Objects.requireNonNull(writeToPath);

		// calculate filename path
		Path filePath = calculatePath(chunk.fileName(), writeToPath);

		// open the stream to it - create the new stream if filename has not yet been seen in map
		OutputStream out = openFileDescriptorsMap.computeIfAbsent(filePath, FileChunkReceiver::apply);

		// check whether the chunk is last for filename - return path to the filename if so otherwise return empty
		if (chunk.last()) { // last chunk holds no value
			openFileDescriptorsMap.remove(filePath).close();
			return Optional.of(filePath);
		} else {
			out.write(chunk.data());

			// do not close the file leave it open
			return Optional.empty();
		}
	}

	/**
	 * Method for closing all open file descriptors (output streams) and clearing the map of open streams of some paths.
	 *
	 */
	public void abandon() {
		for (OutputStream out : openFileDescriptorsMap.values()) {
			try {
				out.close();
			} catch (IOException e) {
				// ignored
			}
		}

		openFileDescriptorsMap.clear();
	}

	/**
	 * Method for calculating the path to files (filenames) to which the writing output stream is opened.
	 *
	 * @param chunkFilename filename consisted in chunk.
	 * @param basePath      base path of dire where the filename is to be stored. Must be created prior to writing.
	 * @return path on which the file stream is to be opened.
	 * @throws IOException upon working with files.
	 */
	public abstract Path calculatePath(String chunkFilename, Path basePath) throws IOException;
}