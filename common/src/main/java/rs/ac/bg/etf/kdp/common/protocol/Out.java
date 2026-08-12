package rs.ac.bg.etf.kdp.common.protocol;

/**
 * Wire counterpart of {@link rs.ac.bg.etf.kdp.common.Linda#out}.
 *
 * @param tuple the tuple to publish; no field may be {@code null}
 */
public record Out(String[] tuple) implements Message {
}
