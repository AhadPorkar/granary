package dev.granary.format.helm;

import org.springframework.http.HttpStatus;

/**
 * A failure reported to a Helm client. ChartMuseum API clients ({@code helm cm-push}, CI scripts) read the
 * message from a JSON body of the form {@code {"error": "..."}}, so every error of this format has that shape.
 */
public class HelmError extends RuntimeException {

    private final HttpStatus status;

    public HelmError(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HelmError(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static HelmError badRequest(String message) {
        return new HelmError(HttpStatus.BAD_REQUEST, message);
    }

    public static HelmError notFound(String message) {
        return new HelmError(HttpStatus.NOT_FOUND, message);
    }

    public static HelmError conflict(String message) {
        return new HelmError(HttpStatus.CONFLICT, message);
    }

    public static HelmError methodNotAllowed(String message) {
        return new HelmError(HttpStatus.METHOD_NOT_ALLOWED, message);
    }

    public static HelmError tooLarge(String message) {
        return new HelmError(HttpStatus.CONTENT_TOO_LARGE, message);
    }
}
