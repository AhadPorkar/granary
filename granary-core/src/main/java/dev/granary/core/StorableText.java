package dev.granary.core;

import java.util.Map;

/**
 * Text that is written to or looked up in the database. PostgreSQL refuses the NUL character in every text value
 * (H2 accepts it), and every column has a maximum length. A value with a NUL or longer than its column must therefore
 * be refused before it reaches the database: as a {@code 400} when it is input to store, as "not found" when it is
 * the key of a lookup. Otherwise the database error surfaces as a misleading {@code 409 storage.conflict} or a
 * {@code 500}, and only on PostgreSQL.
 */
public final class StorableText {

    private StorableText() {
    }

    /** True when {@code value} is null, or has no NUL character and at most {@code maxLength} characters. */
    public static boolean fits(String value, int maxLength) {
        return value == null || value.length() <= maxLength && value.indexOf('\0') < 0;
    }

    /** True for a non-null lookup key that can match a stored value of at most {@code maxLength} characters. */
    public static boolean usableKey(String value, int maxLength) {
        return value != null && fits(value, maxLength);
    }

    /**
     * Refuses a value that does not {@link #fits fit} its column with the API's bean-validation error
     * ({@code 400 validation}, {@code details: {field: reason}}), so the UI shows the reason next to the field.
     */
    public static void check(String field, String value, int maxLength) {
        if (!fits(value, maxLength)) {
            throw GranaryException.badRequest("validation", "Request validation failed",
                    Map.of(field, reason(value, maxLength)));
        }
    }

    /** Why {@code value} does not fit, for error messages. */
    public static String reason(String value, int maxLength) {
        return value != null && value.indexOf('\0') >= 0
                ? "must not contain NUL characters"
                : "size must be between 0 and " + maxLength;
    }
}
