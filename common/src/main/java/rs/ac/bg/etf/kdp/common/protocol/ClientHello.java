package rs.ac.bg.etf.kdp.common.protocol;

/**
 * Initial message from client to server with the client username.
 */
public record ClientHello(String user) implements Hello {
}