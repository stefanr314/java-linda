# Invariants

Rules that must hold for as long as this code exists. Not a checklist. When you add or change code, walk this list.

---

## Slots and job status

**A slot is released exactly once per acquisition.** On the server, every `tryAcquireSlot` has a matching `releaseSlot`,
on every exit path:

- `JobFinished` or `JobFailed` arrives from the workstation
- `JobRejected` arrives (the workstation was already full)
- `send` to the workstation throws `IOException` (the station is gone)
- the user aborts a job in `SCHEDULED` or `RUNNING`
- the heartbeat sweep evicts a station that had jobs on it

**On the workstation, the counter is decremented in `finally`.** If it is not, an `IOException`
while launching the process permanently eats one unit of capacity.

**Transition the status before sending, never after.** `scheduled(jobId)` returns a boolean and must be called *before*
`station.send(...)`. The other way round lets an abort slip in between, so a cancelled job still starts on a
workstation.

**Claim the job first, then the station.** In `scheduleReadyJobs`, win the job through `scheduled`
before looking for a free station. The reverse order leaves the loser of the race holding a reserved slot.

**Terminal states absorb late reports.** `DONE`, `FAILED` and `ABORTED` have no successors. A worker orphaned by an
abort may still report completion; that must be *ignored*, not thrown on — an exception would kill the handler thread
over something the caller could not have prevented.

**`requeued` does NOT close the tuple space.** A rescheduled job continues in the space it already populated. `failed`
and `aborted` do close it. This distinction is easy to lose and changes behaviour.

---

## Threads and locks

**Never hold a lock across I/O.** `send` can block on a full TCP buffer; under a lock that stalls all scheduling. Take a
short lock for the state change, do I/O outside it, compensate on failure.

**The workstation's control thread must never do slow work.** Receive a dispatch, hand it to the worker pool, return to
`readObject()`. Launching a process on that thread means no answer to the next `Ping`, and the server declares a healthy
station dead.

**A handler thread stays on its connection for the connection's life.** Do not hand off to another pooled task: with
nobody blocked in `readObject()`, incoming messages sit in the kernel buffer until TCP stalls.

**`ObjectOutputStream` is not thread-safe.** All writes go through one guarded method (`CloseableMessageSink.send`).
Concurrent
`writeObject` calls do not interleave whole messages — they corrupt the stream, and the peer fails later at an unrelated
point.

**`ObjectInputStream` is never shared.** It stays confined to the one handler thread that owns the connection.

**Stream construction order**: `ObjectOutputStream` first, `flush()`, then `ObjectInputStream` — on *both* peers. The
output constructor writes a stream header and the input constructor blocks until it reads one, so the reverse order
deadlocks both sides before a single application byte moves.

**`out.reset()` before every `flush()`.** Without it, a second instance equal to an earlier one goes out as a
back-reference and the peer sees the stale object; the reference table also grows without bound on a long-lived
connection.

**Closing a socket wakes a thread blocked in `readObject()`, but NOT one parked in
`Condition.await()`.** Aborting a job must do both: close the tuple space and close the connections.

**One unregistration path.** The heartbeat sweep only closes the socket; removal from the registry happens exclusively
in the handler's `finally`. Two paths eventually disagree.

**Unregister by context, not by name.** `remove(key, value)` so that a handler shutting down late cannot evict a
replacement registered under the same name in the meantime. A `false` result is an ordinary outcome.

---

## Network

**TCP does not report a peer that vanished silently.** Without FIN or RST the connection stays
`ESTABLISHED` and a read blocks forever. Hence the heartbeat on the server and `setSoTimeout` on the workstation — one
remedy on each side.

**A break followed by a recovery is not a reconnection.** If the network dropped and came back while neither side was
sending, the connection simply continues. No handshake, nothing observable. Reconnection logic is only needed when one
side actually tore the connection down.

**The first `Ping` after a station dies usually succeeds.** TCP just queues it for retransmission. Detection comes from
the timeout, not from an exception on send.

**Child processes do not die with their parent.** A shutdown hook calling `destroyForcibly` covers
`SIGTERM` and Ctrl+C; it does not cover `SIGKILL`. That is why `EvalBootstrap` watches the parent through
`ProcessHandle.of(parentPid).onExit()` and calls `halt`.

**Drain `stdout` and `stderr` on separate threads.** The pipe fills at roughly 64 KB and the child blocks.

---

## Serialization

- Records: do **not** declare `serialVersionUID`, or declare `0L`. Never `1L` — that discards the stable default a
  record otherwise gets.
- Non-records: `@Serial private static final long serialVersionUID = 1L;` is mandatory. The default is computed from
  class structure and can differ between builds, giving an `InvalidClassException`
  with no useful message.
- `Runnable` is **not** `Serializable`. A class handed to `eval` must implement both; lambdas do not work. Framed in the
  report as an addition to the given specification.
- `out` into the tuple space rejects `null` fields — `null` is reserved as the wildcard marker in a template, and
  allowing it in a tuple would make matching ambiguous.
- `ConcurrentHashMap` accepts neither `null` keys nor `null` values.
- Java 17 has no pattern matching for `switch` (preview until 21) — use `if / else if` with
  `instanceof`.

---

## Requirements from the assignment that are easy to lose

- Server log records: arrival time, job number, machine name, completion time, current status.
- Statuses exactly: `Ready`, `Scheduled`, `Running`, `Done`, `Failed`, `Aborted`.
- On registration a workstation reports its OS, Java version and parallel job capacity.
- At most six input and six output files.
- The workstation must run without a GUI (`--headless`).
- All three programs need a GUI (Swing or JavaFX).
- The client may disconnect and return later for status and results.
- The server accepts several jobs in parallel.
- Networking through `java.net` only — no RMI.
- When a station fails: notify the user, who chooses between aborting and rescheduling; abort the whole job if the user
  is unreachable.
- The workstation receives the path to `linda-client.jar` as a separate parameter.