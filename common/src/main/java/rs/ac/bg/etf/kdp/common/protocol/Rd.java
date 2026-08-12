package rs.ac.bg.etf.kdp.common.protocol;

/**
 * Wire counterpart of {@link rs.ac.bg.etf.kdp.common.Linda#rd}. The server
 * blocks the handling thread in {@code Condition.await()} until a match is
 * found, then replies with a {@link TupleReply}.
 *
 * @param template the template; {@code null} fields are wildcards
 */
public record Rd(String[] template) implements Message {
}
