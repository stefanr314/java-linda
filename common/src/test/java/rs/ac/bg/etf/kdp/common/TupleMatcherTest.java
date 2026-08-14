package rs.ac.bg.etf.kdp.common;

import org.junit.jupiter.api.Test;
import rs.ac.bg.etf.kdp.common.exceptions.TemplateMatchLengthsMismatchException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class TupleMatcherTest {

	@Test
	void templateMatchOnArityRequired() {
		String[] template = {"tag", null, null, null};
		String[] tuple = {"tag", "1"};

		assertThat(TupleMatcher.matches(tuple, template)).isFalse();
	}

	@Test
	void templateMatchOnNonNullFieldRequired() {
		String[] template = {"tag", null, "gonna", null};
		String[] tuple = {"tag", "1", "mismatch", "value"};

		assertThat(TupleMatcher.matches(tuple, template)).isFalse();
	}

	@Test
	void templateMatchReturnTrue() {
		String[] template = {"tag", "array", "2", null};
		String[] tuple = {"tag", "array", "2", "321"};

		assertThat(TupleMatcher.matches(tuple, template)).isTrue();
	}

	@Test
	void fillInRequiresTheMatchAndTemplateArraysToBeSameLengths() {
		String[] template = {"tag", null, "val", null};
		String[] match = {"tag", "1", "val"};

		assertThatExceptionOfType(TemplateMatchLengthsMismatchException.class)
				.isThrownBy(() -> TupleMatcher.fillIn(template, match));
	}

	@Test
	void fillInProperlyWorksOnRightData() {
		String[] template = {"tag", null, "val", null};
		String[] match = {"tag", "1", "val", "3"};

		TupleMatcher.fillIn(template, match);

		assertThat(template).containsExactly("tag", "1", "val", "3");
	}
}