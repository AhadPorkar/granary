package dev.granary.format.cargo;

import org.springframework.http.HttpStatus;

/**
 * A failure to report to cargo as {@code {"errors":[{"detail":"…"}]}}, the shape cargo prints verbatim after
 * "the remote server responded with an error".
 *
 * <p>Thrown from anywhere below the handler and translated exactly once, so publish and yank can be written
 * as straight-line code.
 */
public class CargoError extends RuntimeException {

    private final HttpStatus status;

    public CargoError(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static CargoError badRequest(String detail) {
        return new CargoError(HttpStatus.BAD_REQUEST, detail);
    }

    public static CargoError notFound(String detail) {
        return new CargoError(HttpStatus.NOT_FOUND, detail);
    }

    public static CargoError conflict(String detail) {
        return new CargoError(HttpStatus.CONFLICT, detail);
    }

    public static CargoError methodNotAllowed(String detail) {
        return new CargoError(HttpStatus.METHOD_NOT_ALLOWED, detail);
    }

    public static CargoError badGateway(String detail) {
        return new CargoError(HttpStatus.BAD_GATEWAY, detail);
    }
}
