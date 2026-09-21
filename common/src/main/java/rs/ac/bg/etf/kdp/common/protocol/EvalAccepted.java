package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Eval worker accepted by workstation.
 *
 * @param childJobId id of the eval worker job
 */
public record EvalAccepted(JobId childJobId) implements Message {
}
