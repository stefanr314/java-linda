package rs.ac.bg.etf.kdp.common.protocol;

/**
 * Successful reply message.
 *
 * @param payload
 */
public record Reply(String payload) implements Message {
}