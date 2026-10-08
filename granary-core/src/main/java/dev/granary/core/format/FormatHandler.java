package dev.granary.core.format;

import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.http.ResponseEntity;

/**
 * Service provider interface implemented once per package format (Maven, npm, NuGet, …).
 *
 * <p>The server resolves {@code /repository/{name}/**} to the handler of the repository's format and
 * delegates the whole request to it. Handlers build on {@link dev.granary.core.storage.StorageService},
 * {@link dev.granary.core.proxy.ProxyService} and {@link ContentResolver} so that hosted, proxy and
 * group behaviour is shared and only the format-specific protocol lives in the handler.
 */
public interface FormatHandler {

    /** Stable identifier stored in {@link Repository#getFormat()}, e.g. {@code maven2}. */
    String format();

    /** Human-readable name shown in the UI. */
    String displayName();

    default Set<RepositoryType> supportedTypes() {
        return EnumSet.allOf(RepositoryType.class);
    }

    /** Extra, format-specific validation of a repository definition. Throw {@link dev.granary.core.GranaryException}. */
    default void validate(Repository repository) {
    }

    /** Which permission the request needs. The default treats GET/HEAD as reads and everything else as writes. */
    default AccessLevel requiredAccess(RepositoryRequest request) {
        return request.isGet() ? AccessLevel.READ : AccessLevel.WRITE;
    }

    /** Whether {@code path} is a mutable index document (re-validated upstream) rather than an immutable artifact. */
    default boolean isMetadataPath(String path) {
        return false;
    }

    ResponseEntity<?> handle(RepositoryRequest request) throws IOException;
}
