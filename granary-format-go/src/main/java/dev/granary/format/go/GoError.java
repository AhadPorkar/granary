package dev.granary.format.go;

import org.springframework.http.HttpStatus;

/**
 * A protocol-level failure the handler turns into a JSON error response ({@code {"code": …, "message": …}}),
 * the same shape the rest of the server uses. The go command itself only reports the status of a failed
 * download (it shows response bodies of type text/plain only), so the message is written for the person
 * publishing with curl or reading the server's answer in a CI log.
 */
public class GoError extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public GoError(HttpStatus status, String code, String message) {
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

    public static GoError badRequest(String message) {
        return new GoError(HttpStatus.BAD_REQUEST, "repository.go.badRequest", message);
    }

    public static GoError invalidPath(String message) {
        return new GoError(HttpStatus.BAD_REQUEST, "repository.go.invalidPath", message);
    }

    public static GoError invalidVersion(String message) {
        return new GoError(HttpStatus.BAD_REQUEST, "repository.go.invalidVersion", message);
    }

    public static GoError invalidZip(String message) {
        return new GoError(HttpStatus.BAD_REQUEST, "repository.go.invalidZip", message);
    }

    public static GoError notFound(String message) {
        return new GoError(HttpStatus.NOT_FOUND, "content.notFound", message);
    }

    public static GoError conflict(String message) {
        return new GoError(HttpStatus.CONFLICT, "repository.go.versionExists", message);
    }

    /** An {@code .info} or {@code .mod} uploaded for a version whose zip has not been published. */
    public static GoError versionMissing(String message) {
        return new GoError(HttpStatus.CONFLICT, "repository.go.versionMissing", message);
    }

    public static GoError readOnly(String message) {
        return new GoError(HttpStatus.METHOD_NOT_ALLOWED, "repository.readOnly", message);
    }

    public static GoError methodNotAllowed(String message) {
        return new GoError(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed", message);
    }

    public static GoError tooLarge(String message) {
        return new GoError(HttpStatus.PAYLOAD_TOO_LARGE, "repository.go.zipTooLarge", message);
    }
}
