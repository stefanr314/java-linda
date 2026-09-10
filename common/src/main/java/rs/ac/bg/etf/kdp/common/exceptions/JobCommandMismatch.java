package rs.ac.bg.etf.kdp.common.exceptions;

public class JobCommandMismatch extends DomainException {
	public JobCommandMismatch(String command) {
		super("Command for running java jar was not proper. Command provided: " + command);
	}
}