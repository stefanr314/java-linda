package rs.ac.bg.etf.kdp.common.protocol;

/**
 * Sent instead of the expected reply when a request could not be honored
 * (e.g. {@link Out} with a null field, an unknown job id, ...).
 *
 * @param message a human-readable description of what went wrong
 */
public record Failure(String message) implements Message {
}