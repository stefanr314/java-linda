package rs.ac.bg.etf.kdp.common.protocol;

/**
 * Reply to {@link In}, {@link Rd}, {@link Inp} or {@link Rdp} carrying the
 * matched tuple, already merged with the caller's template.
 *
 * @param tuple the matched tuple, with wildcard fields filled in
 */
public record TupleReply(String[] tuple) implements Message {
}
