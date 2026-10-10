package dev.granary.format.swift;

import org.springframework.http.HttpStatus;

/**
 * A failure the registry protocol reports as an RFC 7807 "problem details" document. Thrown anywhere below
 * the handler and turned into an {@code application/problem+json} response by {@link SwiftResponses#problem}.
 */
public final class SwiftProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public SwiftProblem(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    /** Granary's stable machine-readable code, carried as an extension member of the problem document. */
    public String code() {
        return code;
    }

    public String detail() {
        return getMessage();
    }

    static SwiftProblem badRequest(String code, String detail) {
        return new SwiftProblem(HttpStatus.BAD_REQUEST, code, detail);
    }

    static SwiftProblem unprocessable(String code, String detail) {
        return new SwiftProblem(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
    }
}
