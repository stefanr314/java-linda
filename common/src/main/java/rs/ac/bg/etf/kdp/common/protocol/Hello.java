package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * First message sent on every connection: identifies which job's tuple
 * space this connection should be attached to.
 *
 * @param jobId the job whose {@code TupleSpace} this connection joins
 */
public record Hello(JobId jobId) implements Message {
}
