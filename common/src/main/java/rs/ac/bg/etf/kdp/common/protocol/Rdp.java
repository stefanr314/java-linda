package rs.ac.bg.etf.kdp.common.protocol;

/**
 * Wire counterpart of {@link rs.ac.bg.etf.kdp.common.Linda#rdp}. The server
 * replies immediately with a {@link TupleReply} (present) or a
 * {@link BoolReply} carrying {@code false} (absent).
 *
 * @param template the template; {@code null} fields are wildcards
 */
public record Rdp(String[] template) implements Message {
}
