package rs.ac.bg.etf.kdp.common.protocol;

/**
 * Sent instead of the expected reply when a request could not be honored
 * (e.g. {@link Out} with a null field, an unknown job id, ...).
 *
 * <p>Note: this type intentionally shares its simple name with
 * {@link java.lang.Error}; callers needing both in the same file should
 * import this one by simple name and refer to the JDK type as
 * {@code java.lang.Error}.
 *
 * @param message a human-readable description of what went wrong
 */
public record Error(String message) implements Message {
}
