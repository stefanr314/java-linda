package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Client command to decide whether a job whose workstation was lost should be rescheduled or aborted.
 *
 * @param jobId      the job to decide about
 * @param reschedule true to put the job back to READY, false to abort it
 */
public record JobDecisionCommand(JobId jobId, boolean reschedule) implements Message {
}
