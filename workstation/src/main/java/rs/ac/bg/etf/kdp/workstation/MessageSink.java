package rs.ac.bg.etf.kdp.workstation;

import java.io.IOException;

public interface MessageSink {

	void send(Object message) throws IOException;
}