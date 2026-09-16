package rs.ac.bg.etf.kdp.common.exceptions;

public class LindaException extends DomainException {
	public LindaException(String message) {
		super(message);
	}

	public LindaException(String message, Throwable cause) {
		super(message, cause);
	}
}