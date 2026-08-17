package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.WorkstationInfo;

/**
 * Initial hello message from workstation with all required info about the station itself.
 */
public record WorkstationHello(String host, WorkstationInfo wsInfo) implements Hello {
}