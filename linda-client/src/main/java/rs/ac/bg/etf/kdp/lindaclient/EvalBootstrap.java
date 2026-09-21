package rs.ac.bg.etf.kdp.lindaclient;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;

/**
 * Helper bootstrap class which serves as an entry point to the worker serialized thread meant for running the code
 * provided as argument to EVAL command of Java distributed linda. Since communication between two distinct processes
 * is required a temporal file is created by process creator and serves as input source for bootstrap process.
 *
 * <p>
 * Children do not die with their parent process, so this class also watches the workstation
 * process that launched it (its pid passed via the {@value #PARENT_PID_PROPERTY} system
 * property) and halts immediately if it goes away - otherwise a workstation killed from the task
 * manager would leave workers running forever, writing into a tuple space nobody owns any more.
 * </p>
 *
 * @author stefanr
 */
public class EvalBootstrap {

	private static final String PARENT_PID_PROPERTY = "linda.ppid";

	public static void main(String[] args) throws IOException {
		if (args == null || args.length == 0 || args[0] == null) {
			throw new IllegalArgumentException("Path to the serialized Runnable must be passed as the first argument");
		}

		watchParentProcess();

		try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(args[0]))) {
			((Runnable) in.readObject()).run();
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	/*
	Daemon thread: if it's still running when the Runnable finishes and the JVM exits normally, it
	dies with it - no explicit shutdown needed.
	 */
	private static void watchParentProcess() {
		String pidValue = System.getProperty(PARENT_PID_PROPERTY);
		if (pidValue == null) return;  // not launched as an eval worker (e.g. run directly in tests)

		long pid = Long.parseLong(pidValue);

		ProcessHandle.of(pid).ifPresentOrElse(
				parent -> {
					Thread watchdog = new Thread(() -> {
						try {
							parent.onExit().get();  // blocks until done
						} catch (Exception interruptedOrFailed) {
							// either way we can no longer confirm the parent is alive - halt to be safe
						}

						// halt rather than exit: no shutdown hooks are of any interest once the
						// workstation that owns this worker's tuple space connection is gone
						Runtime.getRuntime().halt(1);
					}, "eval-parent-watchdog");

					watchdog.setDaemon(true);
					watchdog.start();
				},
				() -> Runtime.getRuntime().halt(1)  // parent already gone before we even started watching
		);
	}
}