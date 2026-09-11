package rs.ac.bg.etf.kdp.common;

import java.util.List;

/**
 * Describes a user job as submitted by the {@code client} module: the job filename,
 * command to run, and up to six input and six output files.
 *
 * @param jobFilename filename of actual job to be run.
 * @param command     the command line to launch the job with.
 * @param inputFiles  paths to files the job reads; at most six.
 * @param outputFiles paths to files the job writes; at most six.
 */
public record JobSpec(String jobFilename, String command, List<String> inputFiles,
					  List<String> outputFiles) implements java.io.Serializable {

	private static final int MAX_TRANSFERRED_FILES = 6;

	public JobSpec {
		if (command == null || command.isBlank()) {
			throw new IllegalArgumentException("Command must not be null or blank");
		}
		requirePlainFileName(jobFilename, "jobFilename");

		inputFiles = List.copyOf(requireNonNullList(inputFiles, "inputFiles"));
		outputFiles = List.copyOf(requireNonNullList(outputFiles, "outputFiles"));

		inputFiles.forEach(name -> requirePlainFileName(name, "inputFiles entry"));
		outputFiles.forEach(name -> requirePlainFileName(name, "outputFiles entry"));

		// The jar is one of the files transferred to the workstation, so it counts against the
		// limit the assignment puts on them. Counting it separately would allow seven.
		if (inputFiles.size() + 1 > MAX_TRANSFERRED_FILES) {
			throw new IllegalArgumentException(
					"At most " + MAX_TRANSFERRED_FILES + " files may be transferred, jar included");
		}
		if (outputFiles.size() > MAX_TRANSFERRED_FILES) {
			throw new IllegalArgumentException(
					"At most " + MAX_TRANSFERRED_FILES + " output files");
		}
		if (inputFiles.contains(jobFilename)) {
			throw new IllegalArgumentException("jobFilename must not be repeated in inputFiles");
		}
		if (java.util.Set.copyOf(inputFiles).size() != inputFiles.size()) {
			throw new IllegalArgumentException("Duplicate input file names");
		}
		if (!java.util.Collections.disjoint(inputFiles, outputFiles)) {
			throw new IllegalArgumentException("inputFiles and outputFiles must not share a name");
		}
	}

	/**
	 * File names cross the wire and are resolved against a directory on the receiving side, so they
	 * are untrusted input. A name containing a separator or {@code ..} would let a client write
	 * outside the job directory — the receivers guard against this too, but rejecting it at the
	 * source means the bad job never reaches them.
	 */
	private static void requirePlainFileName(String name, String what) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException(what + " must not be null or blank");
		}
		if (name.contains("/") || name.contains("\\") || name.contains("..")) {
			throw new IllegalArgumentException(what + " must be a plain file name: " + name);
		}
	}

	private static List<String> requireNonNullList(List<String> files, String what) {
		if (files == null) throw new IllegalArgumentException(what + " must not be null");
		return files;
	}
}