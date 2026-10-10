package dev.granary.server.web;

import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

/**
 * Errors raised by the framework before any controller runs, for example a {@code multipart/form-data}
 * request without a boundary. They reach no package-scoped advice, so without this handler a malformed
 * upload from a package manager would be reported as a 500.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class RequestErrorHandler {

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> malformedMultipart(MultipartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "request.multipart.malformed",
                        "message", "Malformed multipart request: " + rootMessage(e)));
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
