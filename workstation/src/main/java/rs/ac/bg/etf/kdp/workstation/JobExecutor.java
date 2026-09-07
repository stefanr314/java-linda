package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.DirCreator;
import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class JobExecutor {

	private static final Logger LOGGER = Logger.getLogger(JobExecutor.class.getName());

	/*
	This field serves as the counter of accepted jobs. Incremented by only one thread and decremented by multiple.
	 */
	private final AtomicInteger acceptedJobs = new AtomicInteger();
	private final int parallelismCapacity;

	/*
	Map for remembering the job specification between the initial call for job dispatch and receiving the sentinel
	value depicting the input files transfer end.
	 */
	private final Map<JobId, JobSpec> jobSpecification = new ConcurrentHashMap<>();

	/*
	Map of running jobs so they can be manipulated on different occasions
	 */
	private final Map<JobId, Process> runningJobs = new ConcurrentHashMap<>();
	private final JobReporter reporter;
	private final ExecutorService workers;


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

		// save the jobId and jobSpec somewhere
		jobSpecification.put(jobId, jobSpec);

		// just return true and await for all input chunks to be received
		return true;
	}

	/**
	 * Method for starting the execution of job upon all input files have been received.
	 *
	 * @param jobId      id of job to be executed.
	 * @param jobDirPath path of job dir - on this path the process will start the job.
	 */
	public void execute(JobId jobId, Path jobDirPath) {
		Objects.requireNonNull(jobId);
		Objects.requireNonNull(jobDirPath);

		JobSpec jobSpec = jobSpecification.get(jobId);
		// run the job
		workers.submit(() -> supervise(jobId, jobSpec, jobDirPath));
	}

	/**
	 * Release all occupied resources prior to execution of job i.e. in input file transfer phase.
	 *
	 * @param jobId id of job that must be released/cleaned after.
	 */
	void jobReleaser(JobId jobId) {
		Objects.requireNonNull(jobId);

		jobSpecification.remove(jobId);

		acceptedJobs.decrementAndGet();
	}

	/**
	 * Called from the control thread on AbortJob.Note WIP.
	 *
	 * @param jobId id of aborted job
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
	 * <p>
	 * Obeys the rule of catching all possible exceptions being thrown since the future of executor submission is never
	 * accessed. Otherwise, it just silently disappears once the thread terminates.
	 * </p>
	 *
	 * @param jobId      id of job to supervise.
	 * @param jobSpec    specification of job to supervise.
	 * @param jobDirPath path to job directory.
	 */
	private void supervise(JobId jobId, JobSpec jobSpec, Path jobDirPath) {
		RunningJob runningJob;
		try {
			// delegate the creation of job
			runningJob = start(jobSpec, jobDirPath);

			Process process = runningJob.process();

			// add to the map
			runningJobs.put(jobId, process);

			// report the new status to ws-main
			reporter.running(jobId);

			int exitCode = process.waitFor();
			runningJob.stderr().join(2000);
			runningJob.stdout().join(2000);

			if (exitCode == 0) reporter.finished(new CollectedResults(jobId, jobSpec, runningJob.resultDir()));
			else reporter.failed(jobId, "exit code " + exitCode);
		} catch (IOException failedToStart) {
			reporter.failed(jobId, failedToStart.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			reporter.failed(jobId, "Interrupted");
		} catch (RuntimeException unexpected) {
			LOGGER.log(Level.SEVERE, "Unexpected failure supervising " + jobId, unexpected);
			reporter.failed(jobId, "Internal error: " + unexpected);
		} finally {
			runningJobs.remove(jobId);
			jobSpecification.remove(jobId);
			acceptedJobs.decrementAndGet();
		}
	}

	private RunningJob start(JobSpec jobSpec, Path jobDirPath) throws IOException {
		// create output dir
		Path outputDirPath = jobDirPath.resolve("output");

		// create logs dir
		Path logs = outputDirPath.resolve("logs");
		DirCreator.createDir(logs);

		// create path to files - files do not exist on disk yet
		Path stdoutFile = logs.resolve("stdout.log");
		Path stderrFile = logs.resolve("stderr.log");

		// prepare the command and arguments TODO
		String[] commandAndArgs = jobSpec.command().split(" ");

		// create the process with process builder - and run in separated directory (job specific directory)
		ProcessBuilder processBuilder = new ProcessBuilder(commandAndArgs).directory(jobDirPath.toFile());
		Process job = processBuilder.start();

		// in order to prevent deadlock if processes are too verbose (to output and err channels) it's required to
		// drain them to separate logger files; these files serve for testing purposes since the client has already
		// requested files he wants to be delivered to him (creation of these files is conducted by the client and
		// it's his responsibility)
		Thread stdout = new Thread(() -> {
			try (InputStream processOut = job.getInputStream();
				 OutputStream fileOutput = Files.newOutputStream(stdoutFile)) {
				byte[] buffer = new byte[16 * 1024];
				int bytesRead;

				while ((bytesRead = processOut.read(buffer)) != -1) {
					fileOutput.write(buffer, 0, bytesRead);
				}
			} catch (IOException ignore) {
			}
		});

		Thread stderr = new Thread(() -> {
			try (InputStream processErr = job.getErrorStream();
				 OutputStream fileError = Files.newOutputStream(stderrFile)) {
				byte[] buffer = new byte[16 * 1024];
				int bytesRead;

				while ((bytesRead = processErr.read(buffer)) != -1) {
					fileError.write(buffer, 0, bytesRead);
				}
			} catch (IOException ignored) {
			}
		});

		stdout.start();
		stderr.start();

		return new RunningJob(job, outputDirPath, stdout, stderr);
	}

	private record RunningJob(Process process, Path resultDir, Thread stdout, Thread stderr) {
	}
}