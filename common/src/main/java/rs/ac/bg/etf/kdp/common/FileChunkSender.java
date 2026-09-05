package rs.ac.bg.etf.kdp.common;

import rs.ac.bg.etf.kdp.common.protocol.FileChunk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;


public final class FileChunkSender {

	private static final int CHUNK_SIZE = 32 * 1024;

	/*
	 Sink for sending the objects down the stream - OBJECT ONLY SINK; MUST BE THREAD SAFE
	 */
	private final FileChunkSink sink;

	public FileChunkSender(FileChunkSink sink) {
		this.sink = sink;
	}

	/**
	 * Method for reading filenames on path and streaming them in chunks over the net with adequate sink, passed by
	 * {@link FileChunkSink} and sent with {@link FileChunkSink#send}. The transport of file chunks can be stopped at
	 * any time if stop transmission flag is set and {@link BooleanSupplier#getAsBoolean()} returns true.
	 *
	 * <p>Who may transfer the transmission differs per direction: a job being rejected mid-flight, a client
	 * disconnecting, workstation dying. This sender just checks the flag</p>
	 *
	 * @param jobId            id of job which files are being sent.
	 * @param filenames        list of filenames to send.
	 * @param sourceDir        source path of filenames.
	 * @param stopTransmission stop flag.
	 * @return true if all files have been sent; false otherwise.
	 * @throws IOException whilst working with files
	 */
	public boolean sendFiles(JobId jobId, List<String> filenames, Path sourceDir,
							 BooleanSupplier stopTransmission) throws IOException {
		Objects.requireNonNull(jobId);
		Objects.requireNonNull(filenames);
		Objects.requireNonNull(sourceDir);
		Objects.requireNonNull(stopTransmission);

		List<String> sendFilenames = List.copyOf(filenames);

		for (String sendFilename : sendFilenames) {
			if (!send(jobId, sourceDir.resolve(sendFilename), sendFilename, stopTransmission)) {
				return false;
			}
		}
		return true;
	}

	private boolean send(JobId jobId, Path filenamePath, String filename, BooleanSupplier stop) throws IOException {
		if (Files.exists(filenamePath)) {
			try (InputStream fileIS = Files.newInputStream(filenamePath)) {
				byte[] buffer = new byte[CHUNK_SIZE];
				int sequence = 0;
				int bytesRead;

				while ((bytesRead = fileIS.read(buffer)) != -1) {
					if (stop.getAsBoolean()) return false;

					byte[] data = Arrays.copyOf(buffer, bytesRead);
					sink.send(new FileChunk(jobId, filename, sequence++, data, false));
				}

				sink.send(new FileChunk(jobId, filename, sequence, new byte[0], true));
			}
		}

		return true;
	}

	/**
	 * Functional interface representing sink objects that know how to send object (FileChunk here) over the net.
	 */
	@FunctionalInterface
	public interface FileChunkSink {
		void send(FileChunk chunk) throws IOException;
	}
}