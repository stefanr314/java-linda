package rs.ac.bg.etf.kdp.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.ac.bg.etf.kdp.common.HeartbeatPolicy;
import rs.ac.bg.etf.kdp.common.WorkstationInfo;
import rs.ac.bg.etf.kdp.common.protocol.Registered;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class WorkstationRegistratorTest {

	private final WorkstationRegistry registry = new WorkstationRegistry();
	private WorkstationContextTest.FakeMessageSink fakeSink;
	private WorkstationRegistrator registrator;

	@BeforeEach
	void setup() {
		fakeSink = new WorkstationContextTest.FakeMessageSink();
		HeartbeatPolicy heartbeatPolicy = new HeartbeatPolicy(30, 100);
		WorkstationInfo info = new WorkstationInfo("ws-21", "Arch Linux", "21.0.1", 5);
		WorkstationContext context = new WorkstationContext(info, fakeSink);

		registry.register(context);
		registrator = new WorkstationRegistrator(registry, heartbeatPolicy);
	}

	@Test
	void registratorWritesRegisteredMessageAckToTheSink() {
		try {
			registrator.register(new WorkstationInfo("ws-14", "arch Linux",
							"17", 4),
					fakeSink);
		} catch (IOException e) {
			fail("Exception thrown upon fake sink");
		}

		// assert that message is written to the stream i.e. fake sink contains the message written to it
		assertThat(fakeSink.sent.size()).isEqualTo(1);
		assertThat(fakeSink.sent.get(0)).isInstanceOf(Registered.class);
	}

	@Test
	void registratorClosesStaleContext() {
		WorkstationInfo alreadyRegisteredStation = new WorkstationInfo("ws-21", "Arch " +
				"Linux", "21.0.1", 5);

		try {
			registrator.register(alreadyRegisteredStation, fakeSink);
		} catch (IOException e) {
			fail("IO exception on fake sink makes no sense");
		}

		assertThat(fakeSink.closed).isTrue();
	}
}