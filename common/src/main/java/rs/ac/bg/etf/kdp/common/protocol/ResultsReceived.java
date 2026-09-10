package rs.ac.bg.etf.kdp.common.protocol;

import rs.ac.bg.etf.kdp.common.JobId;

/**
 * Sent from client to server once result files have been saved locally, so the server may
 * delete the job directory. Sent from server to workstation so the workstation can delete the dir for accepted
 * result on server side.
 *
 * @param jobId the job whose results were saved
 */
public record ResultsReceived(JobId jobId) implements Message {
}