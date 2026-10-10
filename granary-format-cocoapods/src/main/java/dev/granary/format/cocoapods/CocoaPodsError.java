package dev.granary.format.cocoapods;

import org.springframework.http.HttpStatus;

/**
 * A protocol-level failure reported to the client as {@code {"code": …, "message": …}}. {@code pod} prints the
 * body of a failed CDN download verbatim, and {@code curl} users read it, so the message is plain English.
 */
public class CocoaPodsError extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public CocoaPodsError(HttpStatus status, String code, String message) {
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

    public static CocoaPodsError badRequest(String message) {
        return new CocoaPodsError(HttpStatus.BAD_REQUEST, "repository.cocoapods.invalid", message);
    }

    public static CocoaPodsError invalidPodspec(String message) {
        return new CocoaPodsError(HttpStatus.BAD_REQUEST, "repository.cocoapods.invalidPodspec", message);
    }

    public static CocoaPodsError invalidPath() {
        return new CocoaPodsError(HttpStatus.BAD_REQUEST, "path.invalid", "Illegal path");
    }

    public static CocoaPodsError notFound(String message) {
        return new CocoaPodsError(HttpStatus.NOT_FOUND, "content.notFound", message);
    }

    public static CocoaPodsError versionExists(String name, String version) {
        return new CocoaPodsError(HttpStatus.CONFLICT, "repository.cocoapods.versionExists",
                name + " " + version + " already exists and redeployment is disabled for this repository");
    }

    public static CocoaPodsError readOnly(String message) {
        return new CocoaPodsError(HttpStatus.METHOD_NOT_ALLOWED, "repository.readOnly", message);
    }

    public static CocoaPodsError methodNotAllowed(String message) {
        return new CocoaPodsError(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed", message);
    }

    public static CocoaPodsError tooLarge(String message) {
        return new CocoaPodsError(HttpStatus.CONTENT_TOO_LARGE, "repository.cocoapods.tooLarge", message);
    }
}
