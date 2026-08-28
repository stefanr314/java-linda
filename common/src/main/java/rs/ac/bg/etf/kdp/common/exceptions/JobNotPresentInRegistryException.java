package rs.ac.bg.etf.kdp.common.exceptions;

import rs.ac.bg.etf.kdp.common.JobId;

public class JobNotPresentInRegistryException extends DomainException {
	public JobNotPresentInRegistryException(JobId jobId) {
		super(
				"Job with id %s not present in registry.".formatted(jobId.toString())
		);
		
	}
}