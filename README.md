# JAVA-LINDA

A distributed system that runs user-supplied Java jobs and synchronizes
them through a C-Linda-style tuple space. This repository currently
contains **structure only**: interfaces, class skeletons, records, enums,
package layout, and a compiling build — no networking, scheduling, GUI, or
failure-recovery logic yet.

## Module map

| Module            | Package                            | Responsibility                                                                                                                                                     |
|-------------------|------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `common`          | `rs.ac.bg.etf.kdp.common`          | Shared protocol types, the `Linda` interface, and tuple-matching rules used by every other module.                                                                 |
| `linda-client`    | `rs.ac.bg.etf.kdp.lindaclient`     | Library shipped with user jobs; implements `Linda` (`LindaProxy`) over a socket, exposed via `LindaFactory.get()`.                                                 |
| `server`          | `rs.ac.bg.etf.kdp.server`          | Central server: real tuple storage (`TupleSpace`), per-job state (`JobContext`, `JobRegistry`), workstation scheduling (`Scheduler`), heartbeats, and the job log. |
| `workstation`     | `rs.ac.bg.etf.kdp.workstation`     | Worker node: launches user jobs as separate OS processes (`JobProcessLauncher`) and runs `eval()` work forwarded by the server (`EvalRunner`).                     |
| `client`          | `rs.ac.bg.etf.kdp.client`          | End-user program: submit jobs, disconnect/reconnect, query status, fetch results (`JobClient`).                                                                    |
| `examples/primes` | `rs.ac.bg.etf.kdp.examples.primes` | Sample user job written only against the `Linda` interface.                                                                                                        |

## Architecture summary

The server owns one `TupleSpace` per job, keyed by `JobId` in a
`JobRegistry`; that per-job space is the only isolation between
concurrently running jobs, since tags themselves are not namespaced.
User code never talks to a `TupleSpace` directly — it calls `Linda`
methods on a `LindaProxy` (in a job's own JVM) or a `PrimeWorker`-style
`eval()` payload (running on a workstation), and every call becomes a
request/response pair carrying only a job id, an operation, and a
`String[]` over a plain `java.net` socket; blocking `in`/`rd` calls park
the requesting thread on the server inside a `ReentrantLock`/`Condition`
pair rather than polling. All connections, across every job, are served
from a single shared cached thread pool on the server, so large numbers of
threads parked in `await()` are the expected steady state. Workstations
run user jobs as separate OS processes via `ProcessBuilder`, and run
`eval()`-submitted `Runnable`s in-process after deserializing them through
a `URLClassLoader` opened over the job's jar, so job authors must supply a
class implementing both `Runnable` and `Serializable` (lambdas do not
work).

## Building

```
mvn -B verify
```