package dev.granary.server.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.storage.InvalidStorageInputException;
import jakarta.persistence.OptimisticLockException;
import java.sql.SQLException;
import java.util.function.Supplier;
import org.hibernate.StaleObjectStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The exception-to-status mapping of {@link StorageErrorHandler} on its own: exactly the concurrent-write family
 * becomes {@code 409 storage.conflict} (also when wrapped), refused storage input becomes {@code 400}, and every
 * other exception is left alone.
 */
class StorageErrorHandlerTest {

    @RestController
    static class Failing {
        @GetMapping("/fail/{kind}")
        String fail(@PathVariable String kind) {
            Supplier<RuntimeException> failure = switch (kind) {
                case "duplicate" -> () -> new DataIntegrityViolationException("duplicate key uk_asset_path");
                case "duplicate-key" -> () -> new DuplicateKeyException("duplicate key");
                case "optimistic" -> () -> new ObjectOptimisticLockingFailureException("Asset", "id");
                case "lock" -> () -> new CannotAcquireLockException("timed out");
                case "deadlock" -> () -> new PessimisticLockingFailureException("deadlock detected");
                case "jpa-optimistic" -> () -> new OptimisticLockException("stale");
                case "hibernate-stale" -> () -> new StaleObjectStateException("Asset", "id");
                case "hibernate-constraint" -> () -> new ConstraintViolationException("dup", new SQLException("dup", "23505"), "uk_asset_path");
                case "wrapped" -> () -> new IllegalStateException("store failed", new DataIntegrityViolationException("duplicate"));
                // SQLSTATE class 22: H2 "value too long" and PostgreSQL's refusal of a NUL character
                case "too-long" -> () -> new DataIntegrityViolationException("could not execute statement",
                        new ConstraintViolationException("value too long", new SQLException("Value too long", "22001"), null));
                case "nul" -> () -> new DataIntegrityViolationException("could not execute statement",
                        new SQLException("ERROR: invalid byte sequence for encoding \"UTF8\": 0x00", "22021"));
                case "path" -> () -> new InvalidStorageInputException("path.invalid", "path too long");
                case "component" -> () -> new InvalidStorageInputException("component.invalid", "version too long");
                default -> () -> new IllegalStateException("a genuine bug");
            };
            throw failure.get();
        }
    }

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Failing()).setControllerAdvice(new StorageErrorHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "duplicate-key", "optimistic", "lock", "deadlock", "jpa-optimistic",
            "hibernate-stale", "hibernate-constraint", "wrapped"})
    void concurrentWriteFailuresBecome409(String kind) throws Exception {
        mvc.perform(get("/fail/" + kind))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("storage.conflict"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    /** A value the database refuses is no race: retrying cannot help, so it is a 400, not a 409. */
    @ParameterizedTest
    @ValueSource(strings = {"too-long", "nul"})
    void valuesTheDatabaseRefusesBecome400(String kind) throws Exception {
        mvc.perform(get("/fail/" + kind))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.invalid"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void refusedStorageInputBecomes400WithItsCode() throws Exception {
        mvc.perform(get("/fail/path"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("path.invalid"));
        mvc.perform(get("/fail/component"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("component.invalid"));
    }

    @Test
    void otherExceptionsAreNotMapped() {
        assertThatThrownBy(() -> mvc.perform(get("/fail/bug")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessage("a genuine bug");
    }
}
