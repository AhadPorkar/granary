package dev.granary.format.npm;

import org.springframework.http.HttpStatus;

/**
 * A protocol-level failure to report to the npm client as {@code {"error": "…"}}.
 *
 * <p>Thrown from the depths of publish/unpublish logic and translated exactly once, in the handler, so
 * the write paths can be written as straight-line code instead of threading response objects through
 * every helper.
 */
public class NpmError extends RuntimeException {

    private final HttpStatus status;

    public NpmError(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static NpmError badRequest(String message) {
        return new NpmError(HttpStatus.BAD_REQUEST, message);
    }

    public static NpmError notFound(String message) {
        return new NpmError(HttpStatus.NOT_FOUND, message);
    }

    public static NpmError conflict(String message) {
        return new NpmError(HttpStatus.CONFLICT, message);
    }

    public static NpmError methodNotAllowed(String message) {
        return new NpmError(HttpStatus.METHOD_NOT_ALLOWED, message);
    }
}
