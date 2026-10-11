package dev.granary.core.storage;

/**
 * A path or component coordinate the storage layer refuses: a traversal attempt, a backslash, or a value longer
 * than its database column. It is a client error; the dispatcher answers {@code 400} with {@link #code()}.
 *
 * <p>Extends {@link IllegalArgumentException} so existing callers that catch that type keep working.
 */
public class InvalidStorageInputException extends IllegalArgumentException {

    private final String code;

    public InvalidStorageInputException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Stable error code: {@code path.invalid} or {@code component.invalid}. */
    public String code() {
        return code;
    }

    static InvalidStorageInputException path(String message) {
        return new InvalidStorageInputException("path.invalid", message);
    }

    static InvalidStorageInputException component(String message) {
        return new InvalidStorageInputException("component.invalid", message);
    }
}
