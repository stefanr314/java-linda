# Distributed Java-Linda

A distributed system for remote execution, scheduling, and synchronization of Java jobs based on the **C-Linda** paradigm and the concept of a virtual shared **Tuple Space**.

## 1. Introduction & Problem Statement

This project was developed as part of the **Concurrent and Distributed Programming (KDP)** course at the **School of Electrical Engineering, University of Belgrade (ETF)** (package `rs.ac.bg.etf.kdp`).

The goal of the project is to solve the problem of distributed execution of compute-intensive jobs and their remote synchronization across a Local Area Network (LAN) or Wide Area Network (WAN). Instead of relying on direct point-to-point message passing between processes, synchronization and data exchange among distributed workers are modeled after the **C-Linda** coordination language via a centralized **Tuple Space**:

- Processes generate tuples (string arrays `String[]`) and insert them into the shared space using the `out` operation.
- Other processes query, read (`rd`, `rdp`), or atomically consume and remove (`in`, `inp`) tuples from the space using pattern matching, where a `null` element at any array index acts as a wildcard matching any string value at that position.
- Jobs can dynamically spawn new concurrent sub-jobs (worker processes) by invoking `eval(String name, Runnable thread)`, which the system transparently schedules onto available workstations across the network.

### Project Specification & Documentation

The full project assignment specification and architectural invariants are available in the [`docs/`](https://github.com/stefanr314/java-linda/tree/main/docs) directory:

- **Project Assignment Specification:** [`docs/specification.md`](https://github.com/stefanr314/java-linda/blob/main/docs/specification.md)
- **System Invariants & Design Rules:** [`docs/INVARIANTS.md`](https://github.com/stefanr314/java-linda/blob/main/docs/INVARIANTS.md)

## 2. Job Lifecycle & the Custom `RECEIVING` State

According to the original project specification ([`docs/specification.md`](https://github.com/stefanr314/java-linda/blob/main/docs/specification.md)), the job lifecycle consists of six states: `READY`, `SCHEDULED`, `RUNNING`, `DONE`, `FAILED`, and `ABORTED`.

In this implementation (enum [`rs.ac.bg.etf.kdp.common.JobStatus`](https://github.com/stefanr314/java-linda/blob/main/common/src/main/java/rs/ac/bg/etf/kdp/common/JobStatus.java)), a **seventh, initial state — `RECEIVING` — was explicitly introduced**.

| State (`JobStatus`) | In Specification | Description |
| :--- | :---: | :--- |
| **`RECEIVING`** | No (Added) | The server has registered the job metadata and is currently receiving the `.jar` archive and input files over the network from the client. |
| **`READY`** | Yes | All job input files have been completely transferred to the server; the job is queued and waiting to be assigned to an available workstation. |
| **`SCHEDULED`** | Yes | The `Scheduler` has assigned the job to a specific workstation and file transfer to that workstation is in progress. |
| **`RUNNING`** | Yes | The job has been launched and is actively executing as a separate OS process on the workstation. |
| **`DONE`** | Yes | The job finished execution with exit code `0` and its output files have been collected on the server. |
| **`FAILED`** | Yes | Job execution terminated abnormally due to a runtime error, non-zero exit code, or process launch failure. |
| **`ABORTED`** | Yes | The job was cancelled by the client or automatically aborted after a workstation crash when the client was disconnected. |

### Why Was the `RECEIVING` State Added?

When a client submits a job to the server, the submission protocol proceeds in two distinct phases:
1. The client sends the job metadata (`JobSpec`), upon which the server immediately creates an entry in the central `JobRegistry` and assigns a unique `JobId`.
2. The client then sequentially streams the binary payload of the `.jar` archive and all additional input files in chunks over the TCP socket (`FileChunkSender` -> `ClientInputFilesReceiver`).

If a newly registered job were immediately placed in the `READY` state upon creation in `JobRegistry`, the background `Scheduler` thread could pick it up and attempt to dispatch it to an idle workstation **before its `.jar` archive and input files have finished uploading to the server**.

Introducing the **`RECEIVING`** state acts as a synchronization barrier: while the network file transfer from the client is still in progress, the job remains in `RECEIVING` and is ignored by the `Scheduler` (which only selects jobs in the `READY` state). Only after `ClientInputFilesReceiver` confirms that all input files have been completely received and flushed to the server's disk does the job transition from `RECEIVING` to `READY`, making it eligible for workstation scheduling.

## 3. Supported Job Types, Process Tracking, Submission Format & Limitations

### 3.1. Supported Job Types (Linda Jobs vs. Plain Java Programs)

The execution engine is **job-agnostic** — a client can submit **any Java program** packaged as an executable `.jar` archive. Jobs executing on workstations fall into two categories, with a critical distinction in how child process termination is handled:

1. **Linda Jobs (jobs using the `linda-client` library):**
   - Use `LindaFactory` to connect to the central Tuple Space and perform `out`, `in`, `inp`, `rd`, `rdp`, and `eval` operations.
   - **Include built-in parent process termination tracking (`ParentWatchdog`):** When a `Workstation` spawns a job as a child OS process, it passes its own OS process ID via the system property `-Dlinda.ppid=<PID>`. When `LindaFactory.createFromSystemProperties()` is initialized (or when `EvalBootstrap.main()` starts an `eval` worker), `ParentWatchdog.guardOnce()` is invoked. This class spawns a background daemon thread that monitors the workstation process via Java's `ProcessHandle` API:
     ```java
     ProcessHandle.of(ppidParsed).ifPresentOrElse(parent -> {
         Thread watchdog = new Thread(() -> {
             try {
                 parent.onExit().get();
             } catch (Exception e) {}
             Runtime.getRuntime().halt(1);
         }, "parent-watchdog");
         watchdog.setDaemon(true);
         watchdog.start();
     }, () -> Runtime.getRuntime().halt(1));
     ```
   - Consequently, even if the workstation process crashes or is killed forcefully (e.g., `SIGKILL`, where the parent JVM's standard shutdown hooks cannot run), child Linda processes immediately detect the death of their parent workstation and terminate their own JVM via `Runtime.getRuntime().halt(1)`.

2. **Plain (Standalone) Java Jobs:**
   - Can be submitted, scheduled, and executed on a workstation, and their output files are collected and returned to the server and client just like Linda jobs.
   - **Limitation (Orphan / Zombie Processes):** Because plain Java programs do not call `LindaFactory` or load `ParentWatchdog`, no watchdog thread is started inside the child JVM to monitor `-Dlinda.ppid`. If a job is aborted while the workstation is healthy, the workstation explicitly kills the child process via `Process.destroyForcibly()`. However, **if the workstation process itself is forcefully terminated (`SIGKILL` or sudden crash)**, the operating system does not automatically terminate its child processes, leaving plain Java jobs **running in the background as orphan/zombie processes** on the workstation host.

### 3.2. Job Submission Format

Jobs are submitted using a plain-text configuration file (via the CLI `submit <path>` command or through the GUI file selector). Each non-empty, non-comment line (lines not starting with `#`) defines a single job using four pipe-delimited (`|`) fields:

```text
jobJarName|command|input1,input2,...|output1,output2,...
```

Example configuration file:
```text
# Format: jar|command|input_files|output_files
primes.jar|java -jar primes.jar 1 50000|bounds.txt|primes-out.txt
mandelbrot.jar|java -jar mandelbrot.jar||fractal.png
```

If a job requires no additional input files or produces no output files, the corresponding field between the `|` delimiters is left empty.

### 3.3. Operational Constraints & Validation Rules

Based on the implementation in [`JobSpec`](https://github.com/stefanr314/java-linda/blob/main/common/src/main/java/rs/ac/bg/etf/kdp/common/JobSpec.java), [`JobPreparator`](https://github.com/stefanr314/java-linda/blob/main/workstation/src/main/java/rs/ac/bg/etf/kdp/workstation/JobPreparator.java), and [`TupleSpace`](https://github.com/stefanr314/java-linda/blob/main/server/src/main/java/rs/ac/bg/etf/kdp/server/TupleSpace.java), the following constraints are enforced:

- **Maximum Input Files (up to 6 total):** The job's primary `.jar` file (`jobFilename`) counts as one input file during transfer. Up to **5 additional input files** may be specified (`inputFiles.size() + 1 <= 6`).
- **Maximum Output Files (up to 6):** A job may declare at most **6 output files** (`outputFiles.size() <= 6`) to be collected by the workstation upon process completion and returned to the server.
- **Flat Filenames Only (No Directory Paths):** All filenames in `jobFilename`, `inputFiles`, and `outputFiles` must be simple file names (e.g., `data.txt`) without directory separators (`/`, `\`) or parent path segments (`..`). All files are placed into a flat temporary working directory created per job on the workstation.
- **Disjoint Inputs/Outputs & No Duplicates:**
  - `jobFilename` must not be repeated inside `inputFiles`.
  - `inputFiles` must not contain duplicate entries.
  - `inputFiles` and `outputFiles` must be **strictly disjoint** — a file cannot be listed as both an input and an output.
- **Command Format & Manifest `Main-Class` Requirement:**
  - The command string must follow the format `java -jar <jobJarName> [args...]`, where `<jobJarName>` matches the first field of the configuration line.
  - On the workstation, `JobPreparator` does not run `java -jar` directly; instead, it inspects `META-INF/MANIFEST.MF` inside the `.jar` to extract the `Main-Class` attribute and constructs a command with an expanded classpath (`<jobJar>:<lindaClientJar>:<commonJar>`) and system properties (`-Dlinda.host`, `-Dlinda.port`, `-Dlinda.job`, `-Dlinda.ppid`). Every submitted `.jar` **must therefore declare a valid `Main-Class` in its manifest**.
  - Command-line arguments are tokenized using `.split(" ")`, meaning arguments containing spaces inside quotes are not supported.
- **Constraints on `eval(String name, Runnable thread)`:**
  - The `Runnable` instance must implement `java.io.Serializable` (along with all captured fields), as it is serialized to a file and sent over the network to be deserialized and executed by `EvalBootstrap` on a target workstation.
  - The class of the `Runnable` object must be packaged within the parent job's `.jar` archive.
- **Tuple Space (`Linda`) Constraints:**
  - Tuples are strictly string arrays (`String[]`).
  - When outputting a tuple via `out(String[] tuple)`, neither the array nor any of its elements may be `null`.
  - When reading or taking tuples (`in`, `inp`, `rd`, `rdp`), `null` elements act as wildcards matching any string at that index, and matching requires exact array length equality.

## 4. System Architecture & Main Components

The repository is structured as a **multi-module Apache Maven project** targeting **Java 17**, built purely on standard `java.net` TCP sockets and Java object/stream serialization:

- **`common`** — Shared domain models (`JobSpec`, `JobStatus`, `JobId`, `JobResult`, `WorkstationInfo`), wire protocol definitions, chunked file transfer utilities (`FileChunkSender`, `FileChunkReceiver`), and shared Swing UI helpers.
- **`linda-client`** — Client-side library (`LindaClient`, `LindaProxy`, `LindaFactory`, `ParentWatchdog`, `EvalBootstrap`) linked by distributed jobs to interact with the Tuple Space and track parent workstation liveness.
- **`server`** — Central coordination server (`ServerMain`, `ServerGui`, `Scheduler`, `TupleSpace`, `JobRegistry`, `WorkstationRegistry`, `HeartbeatDaemon`, `DecisionBroker`).
- **`workstation`** — Worker node daemon (`WorkstationMain`, `WorkstationGui`, `JobExecutor`, `JobPreparator`, `JobReporter`).
- **`client`** — End-user job submission and management application (`ClientMain`, `ClientGui`, `JobClient`, `JobHistory`).
- **`examples`** — Six sample sequential and distributed Linda jobs (`primes`, `hello-wrld`, `demo-ineff-primes`, `prime-nums-generation-bag-of-tasks`, `mandelbrot`, `odd-person-wins`).
- **`integration-tests`** — Automated end-to-end integration test suite.

### The Three Core Runtime Components

Running the system requires starting three main components:

1. **Central Server (`server`)**
   - Acts as the coordinator: accepts connections from workstations and clients, hosts the central `TupleSpace`, tracks all jobs in `JobRegistry`, and dispatches `READY` jobs to workstations with available execution slots via the `Scheduler`.
   - Runs `HeartbeatDaemon` to periodically probe registered workstations. If a workstation crashes while executing a job, `DecisionBroker` notifies the submitting client, allowing the user to either **reschedule** the job (resetting its state to `READY`) or **abort** it (`ABORTED`). If the client is currently disconnected, the job automatically transitions to `ABORTED`.

2. **Workstation (`workstation`)**
   - Registers with the central server on startup, advertising its host environment (OS name, Java version) and maximum concurrent job slot capacity (`capacity`).
   - For each assigned job, creates an isolated temporary directory, receives the input files, rewrites the launch command via `JobPreparator`, spawns the child JVM process, monitors its exit status, and uploads all declared output files back to the server upon completion.

3. **Client (`client`)**
   - Allows users to submit batches of jobs defined in a configuration file, monitor their execution state, abort active jobs, or download output files once a job reaches `DONE`.
   - Supports **disconnected operation**: submitted job IDs are persisted locally in `.kdp-client-history.txt`, enabling the client to disconnect or shut down after job submission and reconnect later to query statuses or fetch results.

## 5. Building & Running the System (Without Docker)

The project does not use Docker containers; instead, all three runtime components are packaged as standalone executable **fat-JARs** (*uber-JARs*) via `maven-shade-plugin` and run directly on the JVM.

### Prerequisites & Build Instructions

- **Java Development Kit (JDK):** version **17** or newer
- **Apache Maven:** version 3.8+

To compile all modules and package the executable fat-JAR archives (skipping long-running integration tests), run from the repository root:

```bash
mvn clean package -DskipTests
```

To run the full build including unit and end-to-end integration tests:

```bash
mvn -B verify
```

After building, the executable fat-JARs are located at:
- `server/target/server-1.0-SNAPSHOT.jar`
- `workstation/target/workstation-1.0-SNAPSHOT.jar`
- `client/target/client-1.0-SNAPSHOT.jar`

### GUI vs. Headless (Console) Execution Modes

All three components support both **Headless (console)** and **GUI (Java Swing)** execution modes.

Entry-point classes are strictly separated so that headless classes (`ServerMain`, `ClientMain`, and `WorkstationMain` with `--headless`) **never load Swing/AWT classes**, allowing them to run cleanly on remote servers without an X11/Wayland display:

- For **Server** and **Client**, the default `Main-Class` in the JAR manifest launches the headless console mode (`ServerMain` and `ClientMain`), while the Swing GUI is started by invoking `ServerGui` or `ClientGui` on the classpath.
- For **Workstation**, `WorkstationMain` acts as a launcher that starts the Swing GUI (`WorkstationGui`) by default, or runs in pure headless mode when passed the `--headless` flag.

*(Note: To include screenshots of the initial GUI screens in this README, launch the GUI commands below, save the captures into `docs/`, and reference them here.)*

### Component Launch Commands

#### 1. Starting the Central Server (`server`)

**Headless (console) mode:**
```bash
# Default port is 4040
java -jar server/target/server-1.0-SNAPSHOT.jar [port]

# Example on port 4040:
java -jar server/target/server-1.0-SNAPSHOT.jar 4040
```

**GUI mode (`ServerGui`):**
```bash
java -cp server/target/server-1.0-SNAPSHOT.jar rs.ac.bg.etf.kdp.server.ServerGui [port]
```
*What `ServerGui` displays:* A live table of connected workstations (OS, Java version, used/total job slots), a table of all registered jobs and their current `JobStatus` (`RECEIVING`, `READY`, `SCHEDULED`, `RUNNING`, `DONE`, `FAILED`, `ABORTED`), a live view of tuples currently stored in the `TupleSpace`, and a real-time server event log.

#### 2. Starting a Workstation (`workstation`)

`WorkstationMain` accepts `-h` / `--host` (default `localhost`), `-p` / `--port` (default `4040`), `-c` / `--capacity` (maximum concurrent jobs, default `2`), and `--headless`.

**GUI mode (default when `--headless` is omitted):**
```bash
java -jar workstation/target/workstation-1.0-SNAPSHOT.jar -h localhost -p 4040 -c 4
```
*(Alternatively, invoking `WorkstationGui` directly: `java -cp workstation/target/workstation-1.0-SNAPSHOT.jar rs.ac.bg.etf.kdp.workstation.WorkstationGui localhost 4040`)*

**Headless (console) mode:**
```bash
java -jar workstation/target/workstation-1.0-SNAPSHOT.jar --headless -h localhost -p 4040 -c 4
```
*What `WorkstationGui` displays:* Server connection status, workstation slot utilization indicator, a list of currently executing jobs, and a live process output log.

#### 3. Starting the Client (`client`)

**Headless / Interactive REPL mode (`ClientMain`):**
```bash
# Arguments: [serverHost] [serverPort] (default: localhost 4040)
java -jar client/target/client-1.0-SNAPSHOT.jar localhost 4040
```

In interactive console (REPL) mode, the client supports the following commands:

| REPL Command | Description |
| :--- | :--- |
| `submit <config-file>` | Parses the configuration file and sequentially uploads all listed jobs and their input files to the server. |
| `list` | Displays a numbered list of all jobs recorded in the client's local history (`.kdp-client-history.txt`). |
| `status <n>` | Queries the server for the current status of job number `<n>` from the most recent `list` output. |
| `fetch <n>` | Downloads the output files of completed job number `<n>` from the server. |
| `reschedule <n>` | Reschedules job `<n>` after a workstation failure (transitions the job back to `READY`). |
| `abort <n>` | Cancels execution of job `<n>` (transitions the job to `ABORTED`). |
| `disconnect` | Closes the active connection to the server while preserving local job history. |
| `quit` | Exits the client application. |

**GUI mode (`ClientGui`):**
```bash
java -cp client/target/client-1.0-SNAPSHOT.jar rs.ac.bg.etf.kdp.client.ClientGui localhost 4040
```
*What `ClientGui` displays:* A graphical form for building and submitting jobs (selecting the `.jar` archive, input/output files, or loading a batch config file), a job history table with live status updates, interactive prompt dialogs for `reschedule` / `abort` decisions when a workstation crashes, and action buttons for status checks, cancellation, and result retrieval.

## 6. Conclusion & Critical Evaluation of Limitations

### Conclusion

This project demonstrates a complete distributed job execution and coordination system in pure Java without relying on high-level distributed frameworks (such as Java RMI, gRPC, or Akka), using only standard TCP sockets. Through its centralized `TupleSpace` and remote `eval` mechanism, it enables classic **C-Linda** parallel programming patterns (such as the *Bag of Tasks* prime number generator and distributed Mandelbrot fractal renderer included in `examples/`). Furthermore, introducing the `RECEIVING` state eliminates the race condition between chunked file uploads and job scheduling, while the `ParentWatchdog` utility guarantees automatic cleanup of child Linda worker processes even when a workstation suffers a hard crash (`SIGKILL`).

### Potential Limitations & Future Improvements

1. **Orphan / Zombie Processes for Plain Java Jobs:**
   - Because `ParentWatchdog` is initialized inside the `linda-client` library (`LindaFactory` and `EvalBootstrap`), plain Java jobs that do not interact with the Tuple Space never start a watchdog thread to monitor `-Dlinda.ppid`. If the workstation process is killed via `SIGKILL`, such plain Java child processes remain running in the background as orphan processes.
   - *Potential Fix:* Launch all jobs (including plain Java programs) through a lightweight universal bootstrap wrapper on the workstation, or attach a `-javaagent` at process startup that registers `ParentWatchdog` inside its `premain` method before invoking the user's `main` class.

2. **Central Server as a Single Point of Failure (In-Memory State):**
   - All system state — including the `JobRegistry` and `TupleSpace` — is stored exclusively in the central server's volatile memory (RAM). A server crash results in the loss of all active tuples and job states.
   - *Potential Fix:* Add disk persistence (e.g., a Write-Ahead Log or embedded database) and primary-backup replication for the Tuple Space.

3. **Tuple Space Inconsistency When Rescheduling (`reschedule`) a Interrupted Linda Job:**
   - If a workstation crashes mid-execution after a Linda job has already emitted (`out`) or consumed (`in`) tuples from the `TupleSpace`, choosing `reschedule` restarts the job from the beginning. This can result in duplicate tuples or deadlocks if tuples consumed during the failed run are no longer present.
   - *Potential Fix:* Introduce transactional tuple operations scoped to a job execution attempt (rolling back uncommitted `out`/`in` operations upon workstation failure) or tag tuples by `JobId` and purge them before restarting a rescheduled job.

4. **Leftover Unfinished / Deprecated Classes in the `workstation` Module:**
   - The `rs.ac.bg.etf.kdp.workstation` package still contains `JobProcessLauncher` (whose `launch` method throws `UnsupportedOperationException("not yet implemented")`) and the `@Deprecated` `EvalRunner` class, whose responsibilities were superseded by `JobPreparator` and `JobExecutor`. Removing these dead classes would make the codebase cleaner.

5. **Simple Argument Splitting & Lack of Process Sandboxing:**
   - Job command strings are tokenized via `.split(" ")`, which prevents passing command-line arguments containing spaces inside quotation marks.
   - Child jobs run as native OS processes under the same user account and permissions as the `workstation` daemon, without filesystem chroot/container isolation or per-process CPU/memory quotas.

6. **Absence of Docker Configuration:**
   - Testing a multi-workstation cluster locally requires manually starting multiple JVM processes across terminals. Providing `Dockerfile` definitions and a `docker-compose.yml` file would streamline spinning up the server and multiple workstation nodes with a single command.
