package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.*;
import rs.ac.bg.etf.kdp.common.exceptions.JobCommandMismatch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Class that knows how to prepare a job to be run on JVM. Record only jobs.
 */
public class JobPreparator {

	/**
	 * Fully qualified name of the linda-client bootstrap that deserializes and runs an eval()
	 * worker's Runnable. Not referenced as a compile-time dependency - the workstation module
	 * does not depend on linda-client, only puts its jar on the launched process's classpath.
	 */
	private static final String EVAL_BOOTSTRAP_MAIN_CLASS = "rs.ac.bg.etf.kdp.lindaclient.EvalBootstrap";

	/**
	 * System property the eval bootstrap reads to find the workstation process it must watch:
	 * children do not die with their parent, so the bootstrap halts itself once this pid is gone.
	 */
	private static final String PARENT_PID_PROPERTY = "linda.ppid";

	/**
	 * Prepare regular user job. This method works only with java commands strictly written as: <em>java -jar
	 * jarName</em>. Arguments may vary for a particular job, so that's taken into the consideration too. Besides,
	 * the job in the jar file it's required to have the properly defined manifest file.
	 *
	 * @param jobId          id of job to get the setup prepared
	 * @param jobSpec        specification of job
	 * @param jobDirPath     main dir path of job
	 * @param serverHostname hostname of server (only used for linda jobs)
	 * @param serverPort     port on which the server work (only for linda jobs)
	 * @return prepared record that contains the command to be run and paths to both stdout and stderr files.
	 * @throws IOException when working with files
	 */
	public static Prepared prepareJob(JobId jobId, JobSpec jobSpec,
									  Path jobDirPath, String serverHostname,
									  int serverPort) throws IOException {
		Objects.requireNonNull(jobId);
		Objects.requireNonNull(jobSpec);
		Objects.requireNonNull(jobDirPath);

		// create logs dir
		Path logs = jobDirPath.resolve("logs");
		DirManipulator.createDir(logs);

		// create path to files - files do not exist on disk yet
		Path stdoutFile = logs.resolve("stdout.log");
		Path stderrFile = logs.resolve("stderr.log");

		// prepare command
		Path jobJarPath = jobDirPath.resolve(jobSpec.jobFilename());

		String mainClassBinaryName = JarUtil.getMainClassBinaryName(jobJarPath.toFile());

		ClasspathWithArgs classpathWithArgs = getClasspathWithArgs(jobSpec, jobJarPath);

		String classpath = classpathWithArgs.classpath();
		String[] args = classpathWithArgs.args;

		String[] baseCommand = {"java",
				"-cp", classpath,
				"-Dlinda.host=" + serverHostname,
				"-Dlinda.port=" + serverPort,
				"-Dlinda.job=" + jobId.value(),
				"-D" + PARENT_PID_PROPERTY + "=" + ProcessHandle.current().pid(),
				mainClassBinaryName};

		if (args == null || args.length == 0) {
			return new Prepared(stdoutFile, stderrFile, baseCommand);
		}

		String[] command = new String[baseCommand.length + args.length];

		System.arraycopy(baseCommand, 0, command, 0, baseCommand.length);
		System.arraycopy(args, 0, command, baseCommand.length, args.length);

		return new Prepared(stdoutFile, stderrFile, command);
	}

	/**
	 * Prepares an eval() worker instead of a job's own main class: the main class is the
	 * {@link #EVAL_BOOTSTRAP_MAIN_CLASS bootstrap}, not looked up from the jar's manifest, and
	 * {@code jobSpec.command()} is never parsed as a {@code java -jar ...} command line, since it
	 * isn't one. The jar still goes on the classpath, so the worker's own class resolves when the
	 * bootstrap deserializes it. The process is pointed at {@code parentJobId} (via
	 * {@code -Dlinda.job=}) so it shares the calling job's tuple space, and told the workstation's
	 * own pid so it can watch it and halt if the workstation dies.
	 *
	 * @param jobId            id of the eval worker itself (used only for logs/dirs, not the
	 *                         Linda connection)
	 * @param jobSpec          specification of the worker job (command is unused)
	 * @param jobDirPath       path to the worker's job dir
	 * @param parentJobId      id of the job whose {@code eval()} call spawned this worker
	 * @param runnableFileName name of the file (inside {@code jobDirPath}) the serialized
	 *                         Runnable was written to
	 */
	public static Prepared prepareEvalJob(JobId jobId, JobSpec jobSpec, Path jobDirPath, String serverHostname,
										  int serverPort, JobId parentJobId, String runnableFileName)
			throws IOException {
		Objects.requireNonNull(jobId);
		Objects.requireNonNull(jobSpec);
		Objects.requireNonNull(jobDirPath);
		Objects.requireNonNull(parentJobId);
		Objects.requireNonNull(runnableFileName);

		Path logs = jobDirPath.resolve("logs");
		DirManipulator.createDir(logs);

		Path stdoutFile = logs.resolve("stdout.log");
		Path stderrFile = logs.resolve("stderr.log");

		Path jobJarPath = jobDirPath.resolve(jobSpec.jobFilename());
		String classpath = lindaAwareClasspath(jobJarPath);

		String[] command = {"java",
				"-cp", classpath,
				"-Dlinda.host=" + serverHostname,
				"-Dlinda.port=" + serverPort,
				"-Dlinda.job=" + parentJobId.value(),
				"-D" + PARENT_PID_PROPERTY + "=" + ProcessHandle.current().pid(),
				EVAL_BOOTSTRAP_MAIN_CLASS,
				runnableFileName};

		return new Prepared(stdoutFile, stderrFile, command);
	}

	private static ClasspathWithArgs getClasspathWithArgs(JobSpec jobSpec, Path jobJarPath) throws IOException {
		String[] commandSplit = jobSpec.command().trim().split("\\s+", 4);
		if (commandSplit.length < 3 ||
				!commandSplit[0].contains("java") ||
				!commandSplit[1].contains("-jar") ||
				!commandSplit[2].equals(jobSpec.jobFilename())
		) {
			throw new JobCommandMismatch(jobSpec.command());
		}

		String[] args = null;
		if (commandSplit.length == 4) {
			args = commandSplit[3].split(" ");
		}

		return new ClasspathWithArgs(lindaAwareClasspath(jobJarPath), args);
	}

	/**
	 * {@code linda-client} declares {@code common} as an ordinary (non-shaded) Maven dependency,
	 * so a job that uses {@code Linda}/{@code LindaFactory} - every eval worker, and any ordinary
	 * job that calls {@code eval()} itself - needs both jars on its classpath, not just
	 * linda-client's own.
	 */
	private static String lindaAwareClasspath(Path jobJarPath) throws IOException {
		Path lindaClientPath = PathUtil.getLindaClientPath();
		Path commonPath = PathUtil.getCommonPath();

		return jobJarPath.toAbsolutePath()
				+ File.pathSeparator + lindaClientPath.toAbsolutePath()
				+ File.pathSeparator + commonPath.toAbsolutePath();
	}

	private record ClasspathWithArgs(String classpath, String[] args) {
	}


	public record Prepared(Path stdoutFile, Path stderrFile, String[] commands) {
	}
}