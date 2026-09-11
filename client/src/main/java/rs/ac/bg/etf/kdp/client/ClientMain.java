package rs.ac.bg.etf.kdp.client;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Console entry point: a single interactive REPL over one {@link JobClient}, backed by the client's
 * history file so a fresh process (started after the previous one was killed) picks up exactly
 * where the old one left off.
 *
 * <p>{@link JobClient} connects lazily - a command only opens a socket when it actually needs the
 * server ({@code submit}, {@code status}, {@code fetch}); {@code list} and {@code quit} work with no
 * server reachable at all.
 *
 * <h2>Commands</h2>
 * <pre>{@code
 * submit <config-file>   submit every valid job in the config file sequentialy
 * list                     list every job known from the history file, numbered
 * status <n>      query the current status of job number n from the last 'list'
 * fetch <n> 	fetch and save the results of job number n from the last 'list'
 * disconnect 											disconnect from server
 * quit                   												  exit
 * }</pre>
 *
 * <h2>Config file format</h2>
 * Plain text, one job per non-blank line, four {@code |}-separated fields in this fixed order:
 * <pre>{@code
 * jobJarName|command|input1,input2,...|output1,output2,...
 * }</pre>
 * The input and output lists are comma-separated file names and may be empty (nothing between two
 * {@code |}s means no files). The jar and every input file are resolved against the directory that
 * holds the config file itself. Example — one job with two inputs, one job with none:
 * <pre>{@code
 * count.jar|java -jar count.jar|words.txt,stopwords.txt|counts.csv
 * hello.jar|java -jar hello.jar||greeting.txt
 * }</pre>
 */
public final class ClientMain {

	private static final Logger LOGGER = Logger.getLogger(ClientMain.class.getName());

	private final JobClient client;

	public ClientMain(JobClient client) {
		this.client = client;
	}

	public static void main(String[] args) {
		String host = args.length > 0 ? args[0] : "localhost";
		int port = args.length > 1 ? Integer.parseInt(args[1]) : 4040;

		try (JobClient jobClient = new JobClient(host, port, "cli-user")) {
			new ClientMain(jobClient).repl();
		}
	}

	private static List<String> splitFileList(String field) {
		if (field.isBlank()) return List.of();
		return Arrays.stream(field.split(","))
				.map(String::trim)
				.filter(name -> !name.isEmpty())
				.toList();
	}

	/**
	 * Parses {@code configFile}, validates each job locally, and submits the valid ones in order.
	 * An invalid job is skipped: the reason is printed and recorded as {@code NOT_SUBMITTED} in the
	 * job history, and the rest of the file is still processed.
	 *
	 * @return the ids of jobs that were successfully submitted, in file order
	 */
	public List<JobId> submitJobsFrom(Path configFile) throws IOException {
		Path sourceDir = configFile.toAbsolutePath().getParent();
		List<JobId> submitted = new ArrayList<>();

		for (String line : Files.readAllLines(configFile)) {
			if (line.isBlank()) continue;

			String[] fields = line.split("\\|", -1);
			String jobFilenameForReporting = fields.length > 0 ? fields[0] : line;

			JobSpec spec;
			try {
				if (fields.length != 4) {
					throw new IllegalArgumentException(
							"expected 4 fields separated by '|', got " + fields.length);
				}
				spec = new JobSpec(fields[0], fields[1], splitFileList(fields[2]), splitFileList(fields[3]));
			} catch (IllegalArgumentException badLine) {
				reportInvalid(jobFilenameForReporting, badLine.getMessage());
				continue;
			}

			// validate input files + job file
			List<String> problems = client.validate(spec, sourceDir);
			if (!problems.isEmpty()) {
				reportInvalid(spec.jobFilename(), String.join("; ", problems));
				continue;
			}

			try {
				JobId jobId = client.submit(spec, sourceDir);
				submitted.add(jobId);
				System.out.println("Submitted " + spec.jobFilename() + " as job " + jobId.value());
			} catch (IOException | ClassNotFoundException submitFailed) {
				// client.submit() already recorded this outcome to history before throwing.
				System.out.println("Failed to submit " + spec.jobFilename() + ": " + submitFailed.getMessage());
			}
		}
		return submitted;
	}

	private void reportInvalid(String jobFilename, String reason) {
		try {
			client.history().append("-", jobFilename, "NOT_SUBMITTED: " + reason);
		} catch (IOException historyWriteFailed) {
			LOGGER.log(Level.WARNING, "Could not write to job history file", historyWriteFailed);
		}
	}

	/**
	 * Runs the interactive loop until {@code quit} or end of input. No connection is required to
	 * start it - {@code list} works purely off the local history file, and every other command
	 * connects on demand through {@link JobClient}.
	 */
	public void repl() {
		System.out.println("Commands: submit <config-file> | list | status <n> | fetch <n> | disconnect | quit");

		Scanner scanner = new Scanner(System.in);
		for (; ; ) {
			System.out.print("> ");
			if (!scanner.hasNextLine()) return;

			String line = scanner.nextLine().trim();
			if (line.isEmpty()) continue;

			String[] parts = line.split("\\s+", 3);
			switch (parts[0]) {
				case "submit" -> handleSubmit(parts);
				case "list" -> listKnownJobs(loadHistory());
				case "status" -> handleStatus(parts);
				case "fetch" -> handleFetch(parts);
				case "disconnect" -> {
					client.disconnect();
				}
				case "quit" -> {
					return;
				}
				default -> System.out.println("Unknown command: " + parts[0]);
			}
		}
	}

	private void handleSubmit(String[] parts) {
		if (parts.length < 2 || parts[1].isBlank()) {
			System.out.println("Usage: submit <config-file>");
			return;
		}

		Path configFile = Path.of(parts[1].trim());
		try {
			List<JobId> submitted = submitJobsFrom(configFile);
			System.out.println("Submitted " + submitted.size() + " job(s).");
		} catch (IOException configUnreadable) {
			System.out.println("Could not read config file: " + configUnreadable.getMessage());
		}
	}

	private List<JobHistory.Entry> loadHistory() {
		try {
			return client.history().loadAll();
		} catch (IOException historyUnreadable) {
			System.out.println("Could not read job history: " + historyUnreadable.getMessage());
			return List.of();
		}
	}

	private void listKnownJobs(List<JobHistory.Entry> known) {
		for (int i = 0; i < known.size(); i++) {
			JobHistory.Entry entry = known.get(i);
			System.out.println((i + 1) + ") " + entry.timestamp() + "  " + entry.jobId() + "  "
					+ entry.jobFilename() + "  " + entry.status());
		}
	}

	private void handleStatus(String[] parts) {
		JobId jobId = resolveIndex(parts, "status");
		if (jobId == null) return;

		try {
			System.out.println(client.queryStatus(jobId));
		} catch (IOException | ClassNotFoundException failure) {
			System.out.println("Could not query status: " + failure.getMessage());
		}
	}

	private void handleFetch(String[] parts) {
		JobId jobId = resolveIndex(parts, "fetch");
		if (jobId == null) return;

		try {
			List<Path> written = client.fetchResults(jobId, Path.of("results"));
			System.out.println("Saved: " + written);
		} catch (IOException | ClassNotFoundException failure) {
			System.out.println("Could not fetch results. " + failure.getMessage());
		}
	}

	/**
	 * Resolves {@code <n>} from a {@code "<command> <n>"} line against the history entries loaded
	 * fresh right now - not a listing cached from an earlier {@code list} call, so a job submitted or
	 * fetched moments ago is always addressable.
	 */
	private JobId resolveIndex(String[] parts, String command) {
		if (parts.length < 2 || parts[1].isBlank()) {
			System.out.println("Usage: " + command + " <n> (see 'list' for indices)");
			return null;
		}

		int index;
		try {
			index = Integer.parseInt(parts[1].trim());
		} catch (NumberFormatException notANumber) {
			System.out.println("Not a number: " + parts[1]);
			return null;
		}

		List<JobHistory.Entry> known = loadHistory();
		if (index < 1 || index > known.size()) {
			System.out.println("No such entry. Run 'list' first.");
			return null;
		}

		return new JobId(known.get(index - 1).jobId());
	}
}