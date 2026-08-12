package rs.ac.bg.etf.kdp.common;

import java.io.Serializable;
import java.util.List;

/**
 * Describes a user job as submitted by the {@code client} module: the
 * command to run, and up to six input and six output files.
 *
 * @param command     the command line to launch the job with
 * @param inputFiles  paths to files the job reads; at most six
 * @param outputFiles paths to files the job writes; at most six
 */
public record JobSpec(String command, List<String> inputFiles, List<String> outputFiles) implements Serializable {

    private static final int MAX_FILES = 6;

    public JobSpec {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be null or blank");
        }
        if (inputFiles == null || inputFiles.size() > MAX_FILES) {
            throw new IllegalArgumentException("inputFiles must be non-null with at most " + MAX_FILES + " entries");
        }
        if (outputFiles == null || outputFiles.size() > MAX_FILES) {
            throw new IllegalArgumentException("outputFiles must be non-null with at most " + MAX_FILES + " entries");
        }
        inputFiles = List.copyOf(inputFiles);
        outputFiles = List.copyOf(outputFiles);
    }
}
