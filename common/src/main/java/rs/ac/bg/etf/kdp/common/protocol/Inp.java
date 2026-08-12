package rs.ac.bg.etf.kdp.common.protocol;

/**
 * Wire counterpart of {@link rs.ac.bg.etf.kdp.common.Linda#inp}. The server
 * replies immediately with a {@link TupleReply} (present) or a
 * {@link BoolReply} carrying {@code false} (absent).
 *
 * @param template the template; {@code null} fields are wildcards
 */
public record Inp(String[] template) implements Message {
}
