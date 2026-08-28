package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobSpec;

/**
 * Initial command sent by client that requests the job to be performed.
 *
 * @param jobSpec specification of job (command and files)
 */
public record JobSubmitCommand(JobSpec jobSpec) implements Message {
}