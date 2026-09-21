package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;

/**
 * Message sent by server to workstation to dispatch an {@code eval()} worker, in place of
 * {@link JobDispatch}. Everything about the worker (its input files, transferred through the
 * usual chunked pipeline) is scoped to {@code childJobId}; {@code parentJobId} is what the
 * workstation points the worker's process at, so it shares the parent's tuple space.
 *
 * @param childJobId          id minted for this eval worker
 * @param parentJobId         id of the job whose {@code eval()} call spawned this worker
 * @param spec                specification of the worker job (command and files)
 * @param serializedRunnable  the {@code Runnable} (which must also implement
 *                            {@link java.io.Serializable}), already serialized to bytes; small
 *                            enough to ride in this message rather than the chunked file transfer
 */
public record EvalDispatch(JobId childJobId, JobId parentJobId, JobSpec spec, byte[] serializedRunnable)
		implements Message {
}
