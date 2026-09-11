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

	public static Prepared prepareJob(JobId jobId, JobSpec jobSpec,
									  Path jobDirPath, String serverHostname,
									  int serverPort) throws IOException {
		Objects.requireNonNull(jobId);
		Objects.requireNonNull(jobSpec);
		Objects.requireNonNull(jobDirPath);

		// create logs dir
		Path logs = jobDirPath.resolve("logs");
		DirCreator.createDir(logs);

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
				mainClassBinaryName};

		if (args == null || args.length == 0) {
			return new Prepared(stdoutFile, stderrFile, baseCommand);
		}

		String[] command = new String[baseCommand.length + args.length];

		System.arraycopy(baseCommand, 0, command, 0, baseCommand.length);
		System.arraycopy(args, 0, command, baseCommand.length, args.length);

		return new Prepared(stdoutFile, stderrFile, command);
	}

	private static ClasspathWithArgs getClasspathWithArgs(JobSpec jobSpec, Path jobJarPath) throws IOException {
		Path lindaClientPath = PathUtil.getLindaClientPath();

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
		String classpath = jobJarPath.toAbsolutePath() + File.pathSeparator + lindaClientPath.toAbsolutePath();

		return new ClasspathWithArgs(classpath, args);
	}

	private record ClasspathWithArgs(String classpath, String[] args) {
	}


	public record Prepared(Path stdoutFile, Path stderrFile, String[] commands) {
	}
}