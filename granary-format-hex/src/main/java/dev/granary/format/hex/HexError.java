package dev.granary.format.hex;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A failure reported to the Hex client in the shape hex.pm uses:
 * {@code {"status": 422, "message": "Validation error(s)", "errors": {"version": "…"}}}.
 */
final class HexError extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, String> errors;

    HexError(HttpStatus status, String message) {
        this(status, message, Map.of());
    }

    HexError(HttpStatus status, String message, Map<String, String> errors) {
        super(message);
        this.status = status;
        this.errors = Map.copyOf(errors);
    }

    HttpStatus status() {
        return status;
    }

    Map<String, String> errors() {
        return errors;
    }

    static HexError notFound(String message) {
        return new HexError(HttpStatus.NOT_FOUND, message);
    }

    static HexError badRequest(String message) {
        return new HexError(HttpStatus.BAD_REQUEST, message);
    }

    static HexError unauthorized(String message) {
        return new HexError(HttpStatus.UNAUTHORIZED, message);
    }

    static HexError forbidden(String message) {
        return new HexError(HttpStatus.FORBIDDEN, message);
    }

    static HexError methodNotAllowed(String message) {
        return new HexError(HttpStatus.METHOD_NOT_ALLOWED, message);
    }

    /** hex.pm answers 422 for every rejected release; {@code field} names what was wrong. */
    static HexError validation(String field, String detail) {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put(field, detail);
        return new HexError(HttpStatus.UNPROCESSABLE_CONTENT, "Validation error(s)", errors);
    }
}
