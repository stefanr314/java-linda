package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.HeartbeatPolicy;

/**
 * Acknowledges a workstation's registration and hands it the connection parameters it cannot know
 * on its own.
 *
 * <p>
 * Anything the workstation must learn from the server belongs in this record.
 * </p>
 *
 * @param assignedHostName the name the workstation is registered under, echoed back so it can log
 *                         and display the identity the server actually knows it by
 * @param heartbeatPolicy  the server's liveness timings
 */
public record Registered(String assignedHostName, HeartbeatPolicy heartbeatPolicy)
		implements Message {
}