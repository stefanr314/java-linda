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

	private static final int CHUNK_SIZE = 32 * 1024;  // note: beware of maxarray=100_000 on ObjectInputFilter

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
	 * <p>Sending just present files since this method will be used to send output results which at the time the
	 * process exited may not be present locally. Upon client sending his input files, a prior check on file
	 * existence is done before calling this sender.</p>
	 *
	 * @param jobId            id of job which files are being sent.
	 * @param filenames        list of filenames to send.
	 * @param sourceDir        source path of filenames.
	 * @param stopTransmission stop flag.
	 * @return {@link SenderReport} containing the flag to check whether all present files have been sent and list of
	 * present files.
	 * @throws IOException whilst working with files
	 */
	public SenderReport sendFiles(JobId jobId, List<String> filenames, Path sourceDir,
								  BooleanSupplier stopTransmission) throws IOException {
		Objects.requireNonNull(jobId);
		Objects.requireNonNull(filenames);
		Objects.requireNonNull(sourceDir);
		Objects.requireNonNull(stopTransmission);

		List<String> presentFiles = List.copyOf(filenames)
				.stream()
				.filter(filename -> Files.exists(sourceDir.resolve(filename)))
				.toList();

		for (String sendFilename : presentFiles) {
			if (!send(jobId, sourceDir.resolve(sendFilename), sendFilename, stopTransmission)) {

				return new SenderReport(false, List.copyOf(presentFiles));
			}
		}
		return new SenderReport(true, List.copyOf(presentFiles));
	}


	/*
	This method is not cost-free. It entails whole process of Java serialization that has to be done prior to writing
	 to the socket underlying buffer. So the price of sending some bytes of file using this way is more costly than
	 sending the raw bytes down the channel.
	 */
	private boolean send(JobId jobId, Path filenamePath, String filename, BooleanSupplier stop) throws IOException {

		try (InputStream fileIS = Files.newInputStream(filenamePath)) {
			byte[] buffer = new byte[CHUNK_SIZE];
			int sequence = 0;
			int bytesRead;

			while ((bytesRead = fileIS.read(buffer)) != -1) {
				if (stop.getAsBoolean()) return false;

				byte[] data = Arrays.copyOf(buffer, bytesRead);  // new array always allocated
				sink.send(new FileChunk(jobId, filename, sequence++, data, false));
			}

			sink.send(new FileChunk(jobId, filename, sequence, new byte[0], true));
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

	public record SenderReport(boolean allPresentFilesSent, List<String> delivered) {
	}
}