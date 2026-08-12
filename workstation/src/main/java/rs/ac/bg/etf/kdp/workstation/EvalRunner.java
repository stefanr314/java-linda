package rs.ac.bg.etf.kdp.workstation;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
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
 */
public final class EvalRunner {

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

    /**
     * Deserializes {@code payload} (a {@code Runnable & Serializable}
     * produced by the job author) through {@code jobClassLoader} and runs
     * it on the calling thread.
     *
     * @param payload        the serialized {@code Runnable}
     * @param jobClassLoader a class loader opened over the job jar
     */
    public void run(byte[] payload, URLClassLoader jobClassLoader) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /**
     * Marker for the Runnable job authors must supply: it needs to be both
     * runnable and serializable. Lambdas do not satisfy this in general,
     * per {@link rs.ac.bg.etf.kdp.common.Linda#eval}.
     */
    public interface SerializableRunnable extends Runnable, Serializable {
    }
}