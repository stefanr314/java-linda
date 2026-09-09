package rs.ac.bg.etf.kdp.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only, plain-text record of every job outcome this client has ever seen, kept in the
 * client's working directory. This is the only memory a killed-and-restarted client has of what it
 * submitted and under which job id.
 *
 * <p>One line per entry: {@code timestamp|jobId|jobFilename|status}, in that fixed order and
 * separated by {@code |}. {@link #append} replaces any {@code |} or newline inside the status text
 * with a space first, so the four-field format never breaks.
 */
public final class JobHistory {

	private static final String SEPARATOR = "|";

	private final Path file;

	private final Object writeLock = new Object();

	public JobHistory(Path file) {
		this.file = file;
	}

	private static String sanitize(String value) {
		return value
				.replace(SEPARATOR, " ")
				.replace("\n", " ")
				.replace("\r", " ");
	}

	/**
	 * Appends one entry, creating the file if this is the first one. Call this the moment an
	 * outcome is known, not after: a crash right after submitting must not lose the record.
	 */
	public void append(String jobId, String jobFilename, String status) throws IOException {
		// note currently there is only one writer; if this ever changes writing to file is synchronized
		synchronized (writeLock) {
			String line = String.join(SEPARATOR,
					Instant.now().toString(),
					sanitize(jobId),
					sanitize(jobFilename),
					sanitize(status)) + System.lineSeparator();

			Files.writeString(file, line, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
	}

	/**
	 * @return every entry recorded so far, oldest first; empty if the file does not exist yet
	 */
	public List<Entry> loadAll() throws IOException {
		if (!Files.isReadable(file)) return List.of();

		List<Entry> entries = new ArrayList<>();

		//note: adequate for small history files, relies on underlying application buffer
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (line.isBlank()) continue;

			String[] fields = line.split("\\|", 4);
			if (fields.length != 4) continue;  // corrupt or foreign line; skip rather than fail the whole read

			entries.add(new Entry(Instant.parse(fields[0]), fields[1], fields[2], fields[3]));
		}
		return entries;
	}

	public record Entry(Instant timestamp, String jobId, String jobFilename, String status) {
	}
}