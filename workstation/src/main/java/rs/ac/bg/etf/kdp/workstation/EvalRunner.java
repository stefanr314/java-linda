package rs.ac.bg.etf.kdp.workstation;

import java.io.*;
import java.net.URLClassLoader;

/**
 * Deserializes and runs the {@link Runnable} shipped by
 * {@link rs.ac.bg.etf.kdp.common.protocol.Eval}.
 *
 * <p>Because the class implementing the job's {@code Runnable} lives in the
 * job's own jar (not on the workstation's classpath), plain {@link
 * JobClassLoadingObjectInputStream#resolveClass} would fail with a {@code
 * ClassNotFoundException}. {@link JobClassLoadingObjectInputStream}
 * overrides it to resolve classes through a {@link URLClassLoader} opened
 * over the job jar instead.
 *
 * <p><B>THIS CLASS IS DEPRECATED AND NEVER USED IN ANY ACTUAL CODE. To be deleted in next iterations.</B>
 */
@Deprecated
public final class EvalRunner {

	// Deprecated
	public void run(byte[] payload, URLClassLoader jobClassLoader) {
		throw new UnsupportedOperationException("not yet implemented");
	}

	// not used
	public interface SerializableRunnable extends Runnable, Serializable {
	}

	/**
	 * An {@link ObjectInputStream} that resolves classes through a
	 * {@link URLClassLoader} over the job jar, rather than the
	 * workstation's own classpath.
	 */
	static final class JobClassLoadingObjectInputStream extends ObjectInputStream {

		private final URLClassLoader jobClassLoader;

		JobClassLoadingObjectInputStream(InputStream in, URLClassLoader jobClassLoader) throws IOException {
			super(in);
			this.jobClassLoader = jobClassLoader;
		}

		@Override
		protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
			return Class.forName(desc.getName(), false, jobClassLoader);
		}
	}
}