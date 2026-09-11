package rs.ac.bg.etf.kdp.workstation;

import rs.ac.bg.etf.kdp.common.FileChunkSender;
import rs.ac.bg.etf.kdp.common.JobId;
import rs.ac.bg.etf.kdp.common.JobSpec;
import rs.ac.bg.etf.kdp.common.exceptions.JarMisconfiguredException;
import rs.ac.bg.etf.kdp.common.exceptions.JobCommandMismatch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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
	value depicting the input files transfer end. In this phase job is not yet started.
	 */
	private final Map<JobId, JobSpec> jobSpecification = new ConcurrentHashMap<>();

	/*
	Map of running jobs so they can be manipulated on different occasions
	 */
	private final Map<JobId, Process> runningJobs = new ConcurrentHashMap<>();

	/**
	 * Jobs whose result transfer the server told us to stop. A set rather than a flag on a shared
	 * object, because the control thread learns about this while the supervising thread is midway
	 * through streaming, and the two only need to agree on membership.
	 */
	private final Set<JobId> abortedTransfers = ConcurrentHashMap.newKeySet();

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
	public void execute(JobId jobId, Path jobDirPath, String serverHostname, int serverPort) {
		Objects.requireNonNull(jobId);
		Objects.requireNonNull(jobDirPath);

		JobSpec jobSpec = jobSpecification.get(jobId);
		// run the job
		workers.submit(() -> supervise(jobId, jobSpec, jobDirPath, serverHostname, serverPort));
	}

	/**
	 * Release all occupied resources upon initial breakage of files (prior to execution of job i.e. in input file
	 * transfer suffered malfunction).
	 * <p>This method must be called <em>on every failure path possible upon receipt of input files.</p>
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
	 * Called from the control thread when the server can no longer store this job's results.
	 */
	public void stopResultTransfer(JobId jobId) {
		abortedTransfers.add(jobId);
	}

	private boolean transferStopped(JobId jobId) {
		return abortedTransfers.contains(jobId);
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
	private void supervise(JobId jobId, JobSpec jobSpec, Path jobDirPath,
						   String serverHostname, int serverPort) {
		RunningJob runningJob;
		try {
			// delegate the creation of job
			runningJob = start(jobId, jobSpec, jobDirPath, serverHostname, serverPort);

			Process process = runningJob.process();

			// add to the map
			runningJobs.put(jobId, process);

			// report the new status to ws-main
			reporter.running(jobId);

			int exitCode = process.waitFor();
			runningJob.stderr().join(2000);
			runningJob.stdout().join(2000);

			if (exitCode == 0) deliverResults(jobId, jobSpec.outputFiles(), jobDirPath);
			else reporter.failed(jobId, "exit code " + exitCode);  // note: add the stderr to message
		} catch (IOException failedToStart) {
			reporter.failed(jobId, failedToStart.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			reporter.failed(jobId, "Interrupted");
		} catch (JarMisconfiguredException manifestMissing) {
			LOGGER.log(Level.FINE, "Manifest missing for job: " + jobId.value());
			reporter.failed(jobId, "Internal error: " + manifestMissing.getMessage());
		} catch (JobCommandMismatch commandMismatch) {
			LOGGER.log(Level.FINE, "Manifest missing for job: " + jobId.value());
			reporter.failed(jobId, commandMismatch.getMessage());
		} catch (RuntimeException unexpected) {
			LOGGER.log(Level.WARNING, "Unexpected failure supervising " + jobId, unexpected);
			reporter.failed(jobId, "Internal error: " + unexpected);
		} finally {
			runningJobs.remove(jobId);
			jobSpecification.remove(jobId);
			abortedTransfers.remove(jobId);

			// station can receive new jobs now
			acceptedJobs.decrementAndGet();
		}
	}

	private void deliverResults(JobId jobId, List<String> resultFilenames, Path workDir) {
		reporter.finished(jobId);

		List<String> produced = new ArrayList<>(resultFilenames);

		produced.add("logs/stdout.log");
		produced.add("logs/stderr.log");

		try {
			FileChunkSender.SenderReport senderReport = new FileChunkSender(reporter::sendChunk)
					.sendFiles(jobId, produced, workDir, () -> transferStopped(jobId));
			if (senderReport.allPresentFilesSent()) {
				reporter.outputFilesEnd(jobId, senderReport.delivered());
			} else {
				LOGGER.log(Level.INFO, "Server aborted the result transfer for {0}", jobId);
			}
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Result transfer for " + jobId + " failed", e);
		}
	}

	private RunningJob start(JobId jobId, JobSpec jobSpec, Path jobDirPath,
							 String serverHostname, int serverPort) throws IOException {

		// prepare job - may throw if job not properly constructed by client
		JobPreparator.Prepared prepared = JobPreparator.prepareJob(jobId, jobSpec, jobDirPath, serverHostname,
				serverPort);

		// create the process with process builder - and run in separated directory (job specific directory)
		ProcessBuilder processBuilder =
				new ProcessBuilder(prepared.commands())
						.directory(jobDirPath.toFile());
		Process job = processBuilder.start();

		// in order to prevent deadlock if processes are too verbose (to output and err channels) it's required to
		// drain them to separate logger files; these files serve for testing purposes since the client has already
		// requested files he wants to be delivered to him (creation of these files is conducted by the client and
		// it's his responsibility)
		Thread stdout = new Thread(() -> {
			try (InputStream processOut = job.getInputStream();
				 OutputStream fileOutput = Files.newOutputStream(prepared.stdoutFile())) {
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
				 OutputStream fileError = Files.newOutputStream(prepared.stderrFile())) {
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

		return new RunningJob(job, stdout, stderr);
	}

	private record RunningJob(Process process, Thread stdout, Thread stderr) {
	}
}