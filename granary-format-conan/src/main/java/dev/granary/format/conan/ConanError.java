package dev.granary.format.conan;

import org.springframework.http.HttpStatus;

/**
 * A protocol-level failure, translated exactly once (in the handler) into the error document the Conan client
 * understands, so the read and write paths can be straight-line code.
 */
public class ConanError extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ConanError(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ConanError notFound(String message) {
        return new ConanError(HttpStatus.NOT_FOUND, "content.notFound", message);
    }

    public static ConanError badRequest(String code, String message) {
        return new ConanError(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ConanError conflict(String code, String message) {
        return new ConanError(HttpStatus.CONFLICT, code, message);
    }

    public static ConanError unauthorized(String message) {
        return new ConanError(HttpStatus.UNAUTHORIZED, "auth.required", message);
    }

    public static ConanError forbidden(String code, String message) {
        return new ConanError(HttpStatus.FORBIDDEN, code, message);
    }
}
