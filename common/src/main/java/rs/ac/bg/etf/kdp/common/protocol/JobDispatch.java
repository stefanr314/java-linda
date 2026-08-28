package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;

/**
 * Message sent by server to workstation to try running the job demanded by client.
 *
 * @param jobId   id of job
 * @param jobSpec specification of job (command and files)
 */
public record JobDispatch(JobId jobId, JobSpec jobSpec) implements Message {
}