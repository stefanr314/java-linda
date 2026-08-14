package rs.ac.bg.etf.kdp.common.exceptions;

import java.util.Arrays;

public class TemplateMatchLengthsMismatchException extends DomainException {
	public TemplateMatchLengthsMismatchException(String[] template, String[] match) {
		super("Mismatch of lengths occurred on template: " + Arrays.toString(template)
				+ " and match: " + Arrays.toString(match));
	}
}