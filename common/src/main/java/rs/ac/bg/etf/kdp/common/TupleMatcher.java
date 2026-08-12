package rs.ac.bg.etf.kdp.common;

/**
 * Matching rules shared by every {@link Linda} implementation: same arity,
 * a {@code null} template field is a wildcard, every non-null field must
 * be string-equal.
 */
public final class TupleMatcher {

    private TupleMatcher() {
    }

    /**
     * Checks whether {@code tuple} satisfies {@code template}.
     *
     * @param tuple    a fully-populated tuple (no {@code null} fields)
     * @param template a template; {@code null} fields are wildcards
     * @return {@code true} if {@code tuple} and {@code template} have the
     *         same arity and every non-null template field equals the
     *         corresponding tuple field
     */
    public static boolean matches(String[] tuple, String[] template) {
        if (tuple.length != template.length) {
            return false;
        }
        for (int i = 0; i < tuple.length; i++) {
            String templateField = template[i];
            if (templateField != null && !templateField.equals(tuple[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fills every {@code null} field of {@code template} in place with the
     * corresponding value from {@code match}.
     *
     * @param template the template to fill in; modified in place
     * @param match    the tuple that matched {@code template}
     */
    public static void fillIn(String[] template, String[] match) {
        for (int i = 0; i < template.length; i++) {
            if (template[i] == null) {
                template[i] = match[i];
            }
        }
    }
}
