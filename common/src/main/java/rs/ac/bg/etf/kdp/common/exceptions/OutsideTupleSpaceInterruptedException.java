package rs.ac.bg.etf.kdp.common.exceptions;

public class OutsideTupleSpaceInterruptedException extends DomainException {
	public OutsideTupleSpaceInterruptedException() {
		super("Interrupt happened outside tuple space");
	}
}