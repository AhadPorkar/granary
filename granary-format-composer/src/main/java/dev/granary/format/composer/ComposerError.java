package dev.granary.format.composer;

import org.springframework.http.HttpStatus;

/**
 * A protocol-level failure reported to the client as {@code {"code": "…", "message": "…"}}. Composer prints the
 * status line of a failed request, and curl (the usual upload tool) prints the body, so the message is a plain
 * English sentence.
 */
public class ComposerError extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ComposerError(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    /** A stable, machine-readable identifier. */
    public String code() {
        return code;
    }

    public static ComposerError badRequest(String message) {
        return new ComposerError(HttpStatus.BAD_REQUEST, "composer.badRequest", message);
    }

    public static ComposerError invalidPackage(String message) {
        return new ComposerError(HttpStatus.BAD_REQUEST, "composer.invalidPackage", message);
    }

    public static ComposerError notFound(String message) {
        return new ComposerError(HttpStatus.NOT_FOUND, "content.notFound", message);
    }

    public static ComposerError conflict(String message) {
        return new ComposerError(HttpStatus.CONFLICT, "composer.versionExists", message);
    }

    public static ComposerError methodNotAllowed(String message) {
        return new ComposerError(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed", message);
    }

    public static ComposerError readOnly(String message) {
        return new ComposerError(HttpStatus.METHOD_NOT_ALLOWED, "repository.readOnly", message);
    }
}
