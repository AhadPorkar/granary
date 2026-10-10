package dev.granary.format.pypi;

import org.springframework.http.HttpStatus;

/**
 * A protocol-level failure to report to the client. Upload clients (twine, uv publish, poetry) print the
 * response body verbatim, so the message is plain English and, for an already-uploaded file, exactly the
 * sentence twine's {@code --skip-existing} looks for.
 */
public class PypiError extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public PypiError(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    /** A stable, machine-readable identifier for the JSON error shape. */
    public String code() {
        return code;
    }

    public static PypiError badRequest(String message) {
        return new PypiError(HttpStatus.BAD_REQUEST, "pypi.badRequest", message);
    }

    public static PypiError notFound(String message) {
        return new PypiError(HttpStatus.NOT_FOUND, "content.notFound", message);
    }

    public static PypiError methodNotAllowed(String message) {
        return new PypiError(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed", message);
    }

    public static PypiError readOnly(String message) {
        return new PypiError(HttpStatus.METHOD_NOT_ALLOWED, "repository.readOnly", message);
    }

    /** The response PyPI gives for a file name that was uploaded before; twine recognises the wording. */
    public static PypiError fileExists(String filename, String projectUrl) {
        return new PypiError(HttpStatus.BAD_REQUEST, "pypi.fileExists",
                "File already exists (" + filename + "). See " + projectUrl + " for the files of this project.");
    }
}
