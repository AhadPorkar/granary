package dev.granary.format.docker;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Registry content is addressed by digest, so two clients routinely write the same path at the same moment: two
 * pushes sharing a base layer, two nodes pulling the same uncached image through a proxy. The asset table's unique
 * key lets only one of the two inserts win; the loser sees a {@link DataIntegrityViolationException}. Running the
 * losing operation once more is correct because the row exists by then (the retry updates it, or a proxy finds the
 * freshly cached copy) and both writers carry the same bytes.
 */
final class ConcurrentWrites {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentWrites.class);

    /** A storage operation that may be repeated. */
    @FunctionalInterface
    interface Operation<T> {
        T run() throws IOException;
    }

    private ConcurrentWrites() {
    }

    static <T> T retryOnce(String what, Operation<T> operation) throws IOException {
        try {
            return operation.run();
        } catch (DataIntegrityViolationException e) {
            log.debug("concurrent write of {}; retrying: {}", what, e.getMostSpecificCause().getMessage());
            return operation.run();
        }
    }
}
