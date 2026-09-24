package rs.ac.bg.etf.kdp.lindaclient;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ParentWatchdog {
	private static final String PARENT_PID_PROPERTY = "linda.ppid";
	/*
	Currently two or more threads never concurrently run this code, but the guard is present if that ever changes.
	 */
	private static final AtomicBoolean guard = new AtomicBoolean(false);

	private ParentWatchdog() {
	}

	public static void guardOnce() {
		if (!guard.compareAndSet(false, true)) return;

		String ppid = System.getProperty(PARENT_PID_PROPERTY);
		if (ppid != null) {
			long ppidParsed = Long.parseLong(ppid);

			ProcessHandle.of(ppidParsed).ifPresentOrElse(
					parent -> {
						Thread watchdog = new Thread(() -> {
							try {
								parent.onExit().get();
							} catch (Exception interruptedParentAwait) {
								// halt anyway
							}

							Runtime.getRuntime().halt(1);
						}, "parent-watchdog");

						watchdog.setDaemon(true);
						watchdog.start();
					},
					() -> {
						// parent already gone
						Runtime.getRuntime().halt(1);
					}
			);
		}
	}
}