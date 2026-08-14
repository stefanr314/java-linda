package rs.ac.bg.etf.kdp.common.exceptions;

public class SuspendedTupleSpaceException extends DomainException {
	public SuspendedTupleSpaceException() {
		super("Tuple space has been closed from outside. Runtime abortion occurred.");
	}
}