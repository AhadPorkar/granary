package dev.granary.core;

import java.util.Map;

/**
 * Base class for every error the server reports to a client. {@link #code()} is a stable, machine-readable
 * identifier (for example {@code repository.name.taken}) that the web UI translates; {@link #getMessage()}
 * is the English fallback.
 */
public class GranaryException extends RuntimeException {

    private final String code;
    private final int status;
    private final Map<String, Object> details;

    public GranaryException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public GranaryException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static GranaryException notFound(String code, String message) {
        return new GranaryException(404, code, message);
    }

    public static GranaryException badRequest(String code, String message) {
        return new GranaryException(400, code, message);
    }

    public static GranaryException badRequest(String code, String message, Map<String, Object> details) {
        return new GranaryException(400, code, message, details);
    }

    public static GranaryException conflict(String code, String message) {
        return new GranaryException(409, code, message);
    }

    public static GranaryException forbidden(String code, String message) {
        return new GranaryException(403, code, message);
    }
}
