package rs.ac.bg.etf.kdp.common.protocol;

/**
 * Reply to {@link Inp} or {@link Rdp} when no matching tuple was found.
 *
 * @param value always {@code false} in that case
 */
public record BoolReply(boolean value) implements Message {
}
