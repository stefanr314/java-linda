package rs.ac.bg.etf.kdp.common.protocol;

/**
 * Generic acknowledgement, used as the reply to {@link Out} and
 * {@link Eval}, which have no other result to return.
 */
public record Ack() implements Message {
}
