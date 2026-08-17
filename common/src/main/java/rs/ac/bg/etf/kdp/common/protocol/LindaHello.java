package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Introductory message sent by Linda code run on JVM workstation instance.
 */
public record LindaHello(JobId jobId) implements Hello {
}