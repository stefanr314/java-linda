package rs.ac.bg.etf.kdp.client;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Console entry point: submits a batch of jobs from a config file, then offers a small menu for
 * checking on jobs submitted now or in an earlier run.
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
 *
 */
public final class ClientMain {

	private static final Logger LOGGER = Logger.getLogger(ClientMain.class.getName());
	private static final Path RESULTS_SUMMARY_FILE = Path.of("results.txt");

	private final JobClient client;

	public ClientMain(JobClient client) {
		this.client = client;
	}

	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("Usage: ClientMain <config-file> [host] [port]");
			return;
		}

		Path configFile = Path.of(args[0]);
		String host = args.length > 1 ? args[1] : "localhost";
		int port = args.length > 2 ? Integer.parseInt(args[2]) : 4040;

		try (JobClient jobClient = new JobClient(host, port, "cli-user")) {
			jobClient.connect();

			ClientMain main = new ClientMain(jobClient);
			main.submitJobsFrom(configFile);
			main.runMenu();
		} catch (IOException | ClassNotFoundException e) {

			// Includes the foreign-server case (StreamCorruptedException wrapped by connect()):
			// only the message is shown, never a stack trace, and the process exits rather than hangs.
			System.err.println("Client failed: " + e.getMessage());
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
	 * Loads the job history built up over this and every earlier run, then offers to list it, query
	 * a job's status, or fetch a job's results, until the user quits.
	 */
	private void runMenu() {
		List<JobHistory.Entry> known;
		try {
			known = client.history().loadAll();
		} catch (IOException e) {
			known = List.of();
		}

		Scanner scanner = new Scanner(System.in);
		for (; ; ) {
			System.out.println();
			System.out.println("1) List known jobs   2) Query status   3) Fetch results   4) Quit");
			System.out.print("> ");
			if (!scanner.hasNextLine()) return;

			switch (scanner.nextLine().trim()) {
				case "1" -> listKnownJobs(known);
				case "2" -> queryStatusInteractive(scanner);
				case "3" -> fetchResultsInteractive(scanner);
				case "4" -> {
					return;
				}
				default -> System.out.println("Unknown choice.");
			}
		}
	}

	private void listKnownJobs(List<JobHistory.Entry> known) {
		if (known.isEmpty()) {
			return;
		}
		known.forEach(entry -> System.out.println(
				entry.timestamp() + "  " + entry.jobId() + "  " + entry.jobFilename() + "  " + entry.status()));
	}

	private void queryStatusInteractive(Scanner scanner) {
		System.out.print("Job id: ");
		String jobId = scanner.nextLine().trim();

		try {
			System.out.println(client.queryStatus(new JobId(jobId)));
		} catch (UnsupportedOperationException | IOException notAvailable) {
			System.out.println(notAvailable.getMessage());
		}
	}

	private void fetchResultsInteractive(Scanner scanner) {
		System.out.print("Job id: ");
		String jobId = scanner.nextLine().trim();

		String summary;
		try {
			List<Path> written = client.fetchResults(new JobId(jobId), Path.of("results"));
			summary = jobId + "\tDONE\t" + written;
		} catch (UnsupportedOperationException | IOException failure) {
			summary = jobId + "\tFAILED\t" + failure.getMessage();
			System.out.println(failure.getMessage());
		}

		appendResultsSummary(summary);
	}

	private void appendResultsSummary(String line) {
		try {
			Files.writeString(RESULTS_SUMMARY_FILE, line + System.lineSeparator(),
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Could not write to results.txt", e);
		}
	}
}