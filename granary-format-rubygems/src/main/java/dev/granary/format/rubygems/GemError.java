package dev.granary.format.rubygems;

import org.springframework.http.HttpStatus;

/**
 * A failure answered in the shape the RubyGems API uses: a status code and a plain-text message, which
 * {@code gem push}, {@code gem yank} and {@code gem signin} print verbatim.
 */
public class GemError extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public GemError(HttpStatus status, String code, String message) {
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

    public static GemError badRequest(String message) {
        return new GemError(HttpStatus.BAD_REQUEST, "rubygems.badRequest", message);
    }

    public static GemError invalidGem(String message) {
        return new GemError(HttpStatus.UNPROCESSABLE_CONTENT, "rubygems.invalidGem", message);
    }

    public static GemError notFound(String message) {
        return new GemError(HttpStatus.NOT_FOUND, "rubygems.notFound", message);
    }

    public static GemError conflict(String message) {
        return new GemError(HttpStatus.CONFLICT, "rubygems.conflict", message);
    }

    public static GemError unprocessable(String message) {
        return new GemError(HttpStatus.UNPROCESSABLE_CONTENT, "rubygems.unprocessable", message);
    }

    public static GemError readOnly(String message) {
        return new GemError(HttpStatus.METHOD_NOT_ALLOWED, "repository.readOnly", message);
    }

    public static GemError unauthorized(String message) {
        return new GemError(HttpStatus.UNAUTHORIZED, "rubygems.unauthorized", message);
    }

    public static GemError forbidden(String message) {
        return new GemError(HttpStatus.FORBIDDEN, "rubygems.forbidden", message);
    }

    public static GemError tooLarge(String message) {
        return new GemError(HttpStatus.CONTENT_TOO_LARGE, "rubygems.tooLarge", message);
    }
}
