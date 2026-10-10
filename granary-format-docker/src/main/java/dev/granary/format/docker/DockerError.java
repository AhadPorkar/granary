package dev.granary.format.docker;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A protocol failure to report in the OCI error shape, {@code {"errors":[{"code","message","detail"}]}}.
 * Thrown from the registry logic and translated exactly once, in the handler, so the read and write paths
 * stay straight-line code.
 */
public class DockerError extends RuntimeException {

    private final HttpStatus status;
    private final OciErrorCode code;
    private final transient Map<String, Object> detail;

    public DockerError(HttpStatus status, OciErrorCode code, String message) {
        this(status, code, message, null);
    }

    public DockerError(HttpStatus status, OciErrorCode code, String message, Map<String, Object> detail) {
        super(message == null ? code.defaultMessage() : message);
        this.status = status;
        this.code = code;
        this.detail = detail == null ? null : Map.copyOf(detail);
    }

    public HttpStatus status() {
        return status;
    }

    public OciErrorCode code() {
        return code;
    }

    public Map<String, Object> detail() {
        return detail;
    }

    public static DockerError notFound(OciErrorCode code, String message) {
        return new DockerError(HttpStatus.NOT_FOUND, code, message);
    }

    public static DockerError notFound(OciErrorCode code, String message, Map<String, Object> detail) {
        return new DockerError(HttpStatus.NOT_FOUND, code, message, detail);
    }

    public static DockerError badRequest(OciErrorCode code, String message) {
        return new DockerError(HttpStatus.BAD_REQUEST, code, message);
    }

    public static DockerError badRequest(OciErrorCode code, String message, Map<String, Object> detail) {
        return new DockerError(HttpStatus.BAD_REQUEST, code, message, detail);
    }

    public static DockerError manifestInvalid(String message) {
        return badRequest(OciErrorCode.MANIFEST_INVALID, message);
    }

    public static DockerError nameInvalid(String name) {
        return badRequest(OciErrorCode.NAME_INVALID, "'" + name + "' is not a valid repository name");
    }

    public static DockerError unsupported(String message) {
        return new DockerError(HttpStatus.METHOD_NOT_ALLOWED, OciErrorCode.UNSUPPORTED, message);
    }

    public static DockerError unauthorized(String message) {
        return new DockerError(HttpStatus.UNAUTHORIZED, OciErrorCode.UNAUTHORIZED, message);
    }

    public static DockerError denied(HttpStatus status, String message) {
        return new DockerError(status, OciErrorCode.DENIED, message);
    }
}
