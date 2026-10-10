package dev.granary.format.pub;

import org.springframework.http.HttpStatus;

/**
 * A failure reported to the pub client as {@code {"error":{"code":"…","message":"…"}}}, the shape it prints
 * as "Message from server: …". The codes follow pub.dev's vocabulary.
 */
public class PubError extends RuntimeException {

    public static final String NOT_FOUND = "NotFound";
    public static final String INVALID_INPUT = "InvalidInput";
    public static final String PACKAGE_REJECTED = "PackageRejected";
    public static final String METHOD_NOT_ALLOWED = "MethodNotAllowed";
    public static final String UPSTREAM = "UpstreamError";

    private final HttpStatus status;
    private final String code;

    public PubError(HttpStatus status, String code, String message) {
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

    public static PubError notFound(String message) {
        return new PubError(HttpStatus.NOT_FOUND, NOT_FOUND, message);
    }

    public static PubError invalid(String message) {
        return new PubError(HttpStatus.BAD_REQUEST, INVALID_INPUT, message);
    }

    /** The archive is understood but not acceptable (bad pubspec, unsafe paths, too large). */
    public static PubError rejected(String message) {
        return new PubError(HttpStatus.BAD_REQUEST, PACKAGE_REJECTED, message);
    }

    /** The version exists and the repository does not allow redeploying. */
    public static PubError exists(String message) {
        return new PubError(HttpStatus.CONFLICT, PACKAGE_REJECTED, message);
    }

    public static PubError methodNotAllowed(String message) {
        return new PubError(HttpStatus.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED, message);
    }
}
