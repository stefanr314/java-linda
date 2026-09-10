package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.DirCreator;
import rs.ac.bg.etf.kdp.common.JarUtil;
import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;
import rs.ac.bg.etf.kdp.common.exceptions.JobCommandMismatch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Class that knows how to prepare a job to be run on JVM. Record only jobs.
 */
public class JobPreparator {

	public static Prepared prepareJob(JobId jobId, JobSpec jobSpec,
									  Path jobDirPath, String serverHostname,
									  int serverPort) throws IOException {
		// create logs dir
		Path logs = jobDirPath.resolve("logs");
		DirCreator.createDir(logs);

		// create path to files - files do not exist on disk yet
		Path stdoutFile = logs.resolve("stdout.log");
		Path stderrFile = logs.resolve("stderr.log");

		// prepare command
		Path jobJarPath = jobDirPath.resolve(jobSpec.jobFilename());

		String mainClassBinaryName = JarUtil.getMainClassBinaryName(jobJarPath.toFile());

		String classpath = getClasspath(jobSpec, jobJarPath);

		String[] commands = {"java",
				"-cp", classpath,
				mainClassBinaryName,
				"-Dlinda.host=" + serverHostname,
				"-Dlinda.port=" + serverPort,
				"-Dlinda.job=" + jobId.value()};

		return new Prepared(stdoutFile, stderrFile, commands);
	}

	private static String getClasspath(JobSpec jobSpec, Path jobJarPath) {
		Path lindaClientPath = Path.of(
				"linda-client",
				"target",
				"linda-client-1.0-SNAPSHOT.jar");

		// prepare the command and arguments TODO
		String[] commandSplit = jobSpec.command().split(" ");
		if (!commandSplit[0].contains("java") || !commandSplit[1].contains("-jar")) {
			throw new JobCommandMismatch(jobSpec.command());
		}

		String classpath = jobJarPath.toAbsolutePath() + File.pathSeparator + lindaClientPath.toAbsolutePath();

		return classpath;
	}

	public record Prepared(Path stdoutFile, Path stderrFile, String[] commands) {
	}
}