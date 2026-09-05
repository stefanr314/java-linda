package rs.ac.bg.etf.kdp.common.protocol;

import java.io.Serializable;

// todo refactor

/**
 * Wire protocol between {@code linda-client}/{@code workstation} and the
 * {@code server}, exchanged over plain {@code java.net} sockets via
 * {@link java.io.ObjectOutputStream}/{@link java.io.ObjectInputStream}.
 *
 * <p>Tuples never leave the server's storage; only a job id, an operation,
 * and a {@code String[]} payload cross the wire.
 */
public sealed interface Message extends Serializable
		permits Ack, BoolReply, Bye, Eval, Failure, FileChunk, FileChunkAck, Hello, In, Inp, InputFilesEnd, InputFilesStart, JobAccepted, JobDispatch, JobFailed, JobFilesFailure, JobFinished, JobJarEnd, JobJarStart, JobRegistered, JobRejected, JobRunning, JobSubmitCommand, Out, Ping, Pong, Rd, Rdp, Registered, Reply, TupleReply {
}