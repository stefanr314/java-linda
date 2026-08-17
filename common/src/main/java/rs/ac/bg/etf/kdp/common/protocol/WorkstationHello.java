package rs.ac.bg.etf.kdp.common.protocol;

/**
 * Initial hello message from workstation with all required info about the station itself.
 */
public record WorkstationHello(String host, String osName, String javaVersion, int capacity) implements Hello {
}