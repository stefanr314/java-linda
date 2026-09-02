package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.protocol.FileChunk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public final class FileChunkSender {

	/*
	 Sink for sending the objects down the stream - OBJECT ONLY SINK; MUST BE THREAD SAFE
	 */
	private final MessageSink sink;

	public FileChunkSender(MessageSink sink) {
		this.sink = sink;
	}

	/**
	 * Method for accepting the results and sending them over the net.
	 * <p>
	 * This method must be thread safe. Current implementation delegates the thread safety to stack confinement
	 * and already synchronization sinker. If these conditions are not met in future proper behaviour of function
	 * is not guaranteed.
	 * </p>
	 *
	 * @param collected collected results
	 * @throws IOException upon writing to sink or reading from files
	 */
	public void acceptResultsAndSend(CollectedResults collected) throws IOException {
		JobId jobId = collected.jobId();
		List<String> outputFilenames = new ArrayList<>(collected.spec().outputFiles());
		Path jobWorkDir = collected.workDir();

		// send the loggers too
		Path logs = Path.of("logs");
		String stdoutLog = logs.resolve("stdout.log").toString();
		String stderrLog = logs.resolve("stderr.log").toString();

		outputFilenames.add(stdoutLog);
		outputFilenames.add(stderrLog);

		for (String outputFilename : outputFilenames) {
			Path outputFilePath = jobWorkDir.resolve(outputFilename);
			if (Files.exists(outputFilePath)) {
				try (InputStream fileIS = Files.newInputStream(outputFilePath)) {
					byte[] buffer = new byte[16 * 1024];
					int sequence = 0;
					int bytesRead;

					while ((bytesRead = fileIS.read(buffer)) != -1) {
						byte[] data = Arrays.copyOf(buffer, bytesRead);
						sink.send(new FileChunk(jobId, outputFilename, sequence++, data, false));
					}
					sink.send(new FileChunk(jobId, outputFilename, sequence, null, true));
				}
			}
		}
	}
}