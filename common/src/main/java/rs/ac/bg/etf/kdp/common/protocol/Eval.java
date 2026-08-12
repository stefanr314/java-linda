package rs.ac.bg.etf.kdp.common.protocol;

/**
 * Wire counterpart of {@link rs.ac.bg.etf.kdp.common.Linda#eval}. The
 * server's {@code Scheduler} picks a free workstation and forwards this
 * message, together with the job jar and job id, to that workstation.
 *
 * @param name                a human-readable name for the spawned activity
 * @param serializedRunnable  the {@code Runnable} (which must also
 *                            implement {@link java.io.Serializable}),
 *                            already serialized to bytes
 */
public record Eval(String name, byte[] serializedRunnable) implements Message {
}
