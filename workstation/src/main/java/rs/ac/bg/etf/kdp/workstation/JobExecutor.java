package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public final class JobExecutor {
	//todo implement job reporter to the main thread
	/*
	This field serves as the counter of accepted jobs. This executor is the only writer so volatile is enough.
	 */
	private final AtomicInteger acceptedJobs = new AtomicInteger();
	private final int parallelismCapacity;

	/*
	Map of running jobs so they can be manipulated on different occasions
	 */
	private final Map<JobId, Process> runningJobs = new ConcurrentHashMap<>();
	private final JobReporter reporter;
	private final ExecutorService workers;

	private final ByteArrayOutputStream tempOut = new ByteArrayOutputStream();
	private final ByteArrayOutputStream tempErr = new ByteArrayOutputStream();

	private Thread stdout;
	private Thread stderr;

	public JobExecutor(int parallelismCapacity, JobReporter reporter, ExecutorService workers) {
		this.parallelismCapacity = parallelismCapacity;
		this.reporter = reporter;
		this.workers = workers;
	}

	/**
	 * Called from the control thread only. Reserving before the process exists is what keeps two
	 * dispatches arriving back to back from both passing the capacity check.
	 *
	 * <p>
	 * Checking whether the station is truly free to run the jobs at first glance does not entail synchronization
	 * since the check-than-act issue. By this code is only run by a single thread. All other writer
	 * threads just do the decrementing so the returned value by {@link AtomicInteger#get} can only be
	 * potentially lesser. This does not break the invariant (acceptedJob <= parallelismCapacity). If this
	 * precondition (i.e. incrementing is called only by a single thread) is broken stricter sync is required.
	 * </p>
	 *
	 * @return false if this workstation is full and the job must be refused
	 */
	public boolean accept(JobId jobId, JobSpec jobSpec) {
		Objects.requireNonNull(jobId);
		Objects.requireNonNull(jobSpec);

		// check whether station is truly free to run the job - TS since this code never gets run by multiple threads
		// concurrently; so the returned atomic integer can only be eventually lesser than what we have read with get
		if (acceptedJobs.get() >= parallelismCapacity) return false;
		acceptedJobs.incrementAndGet();

		// run the job
//		workers.submit(() -> supervise(jobId, jobSpec));
		CompletableFuture.supplyAsync(() -> supervise(jobId, jobSpec), workers).thenAccept(System.out::println);

		return true;
	}

	/**
	 * Called from the control thread on AbortJob.
	 */
	public void abort(JobId jobId) {
		Process process = runningJobs.get(jobId);
		if (process != null) process.destroyForcibly();
	}

	/**
	 * For the shutdown hook: children do not die with their parent (without the process handler).
	 */
	public void destroyAll() {
		runningJobs.values().forEach(Process::destroyForcibly);
	}

	/**
	 * Thread confined code. Working with external structures must be synchronized.
	 *
	 * <p>
	 * This thread represents the way to work with the running process, meaning that when terminated it's
	 * required to clean up after the process i.e. remove it from running jobs and decrement the number of
	 * running jobs.
	 * </p>
	 *
	 * @param jobId   id of job to supervise
	 * @param jobSpec specification of job to supervise
	 */
	private String supervise(JobId jobId, JobSpec jobSpec) {
		Process job;
		try {
			// delegate the creation of job
			job = start(jobId, jobSpec);

			// add to the map
			runningJobs.put(jobId, job);

			// report the new status to ws-main
			reporter.running(jobId);

			int exitCode = job.waitFor();
			stdout.join();
			stderr.join();

			String collected = collectResults(jobSpec);

			if (exitCode == 0) reporter.finished(jobId, collected);
			else reporter.failed(jobId, "exit code " + exitCode);

			return collected;
		} catch (IOException failedToStart) {
			reporter.failed(jobId, failedToStart.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			reporter.failed(jobId, "Interrupted");
		} finally {
			runningJobs.remove(jobId);
			acceptedJobs.decrementAndGet();
		}
		return "FAILED";
	}

	private Process start(JobId jobId, JobSpec jobSpec) throws IOException {
		// todo: create the process

		// todo: when working with actual files is required to write them firsty to some files i.e. use the Named
		//  Pipe for IPC

		// prepare the command and arguments
		String[] split = jobSpec.command().split(" ", 2);
		String[] commandAndArgs = jobSpec.command().split(" ");

		String command = split[0];
		String[] args = split[1].split(" ");

		// create the process with process builder
		ProcessBuilder processBuilder = new ProcessBuilder(commandAndArgs);
		Process job = processBuilder.start();

		//in order to prevent deadlock if processes are too verbose (to output and err channels) it's required to
		// drain them

		//todo: this is just example that work with in memory structure; in production required is to write the
		// output to files
		stdout = new Thread(() -> {
			try (InputStream processOut = job.getInputStream()) {
				byte[] buffer = new byte[8192];
				int bytesRead;

				while ((bytesRead = processOut.read(buffer)) != -1) {
					tempOut.write(buffer, 0, bytesRead
					);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		stderr = new Thread(() -> {
			try (InputStream processErr = job.getErrorStream()) {
				byte[] buffer = new byte[8192];
				int bytesRead;

				while ((bytesRead = processErr.read(buffer)) != -1) {
					tempErr.write(buffer, 0, bytesRead
					);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});

		// todo: use anonymous pipes to work with the stdout and stderr of process
		stdout.start();
		stderr.start();

		return job;
	}

	private String collectResults(JobSpec spec) {
		String err = tempErr.toString(Charset.defaultCharset());
		String out = tempOut.toString(Charset.defaultCharset());

		return err + out;
	}
}