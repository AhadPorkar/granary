package dev.granary.server.web;

import dev.granary.core.storage.InvalidStorageInputException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import java.sql.SQLException;
import java.util.Map;
import org.hibernate.StaleStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockAcquisitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Storage-layer failures that are not server bugs, for every controller (the repository dispatcher and the REST
 * API alike). It runs before the API's catch-all handler, and Spring also matches these types when they arrive as
 * the cause of another exception. Nothing else is mapped here: every other exception keeps its current handling.
 *
 * <ul>
 *   <li>A path or component coordinate the storage layer refuses (traversal, too long for its column):
 *       {@code 400} with the code of the {@link InvalidStorageInputException}, the same answer the dispatcher
 *       gives for an illegal request path.</li>
 *   <li>A write that lost a race against a concurrent write of the same row: a unique or foreign key violation,
 *       an optimistic locking failure, a lock that could not be acquired (including the storage layer's own
 *       per-key write locks timing out) or a deadlock victim: {@code 409 storage.conflict}. Retrying the request
 *       succeeds. {@code StorageService} prevents these races for paths and components on a single node; this
 *       mapping is the last line of defence so they never surface as a {@code 500}.</li>
 *   <li>A value the database refuses to store or compare (SQLSTATE class 22, "data exception": a string longer
 *       than its column, a NUL character on PostgreSQL): {@code 400 request.invalid}. That is no race, and a retry
 *       would fail the same way; the services refuse such values before they reach the database, so this too is
 *       only a last line of defence.</li>
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StorageErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(StorageErrorHandler.class);

    @ExceptionHandler(InvalidStorageInputException.class)
    public ResponseEntity<Map<String, String>> invalidInput(InvalidStorageInputException e) {
        log.debug("refused storage input: {}", e.getMessage());
        String message = "path.invalid".equals(e.code()) ? "Illegal path" : "Illegal component coordinates";
        return body(HttpStatus.BAD_REQUEST, e.code(), message);
    }

    @ExceptionHandler({
            DataIntegrityViolationException.class,
            // optimistic locking failures, CannotAcquireLockException, deadlock and serialization losers
            ConcurrencyFailureException.class,
            OptimisticLockException.class,
            PessimisticLockException.class,
            LockTimeoutException.class,
            StaleStateException.class,
            ConstraintViolationException.class,
            LockAcquisitionException.class})
    public ResponseEntity<Map<String, String>> conflict(Exception e) {
        if (isDataException(e)) {
            log.warn("value refused by the database, answered 400: {}", e.toString());
            return body(HttpStatus.BAD_REQUEST, "request.invalid",
                    "A value is too long or contains a character that cannot be stored");
        }
        log.warn("concurrent write conflict, answered 409: {}", e.toString());
        return body(HttpStatus.CONFLICT, "storage.conflict",
                "The request collided with a concurrent change of the same content; retry it");
    }

    /** SQLSTATE class 22 (data exception) anywhere in the cause chain: the same on H2 and PostgreSQL. */
    static boolean isDataException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("22")) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code, "message", message));
    }
}
