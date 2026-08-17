package rs.ac.bg.etf.kdp.common.protocol;

/**
 * First message sent on every connection: identifies the sender initializing the communication towards server.
 * <p>
 * Three distinct values/implementations allowed which represent the initial messages sent from either workstation,
 * client or JVM instance that runs Linda code.
 * </p>
 *
 */
public sealed interface Hello extends Message
		permits WorkstationHello, ClientHello, LindaHello {
}