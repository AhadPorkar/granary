package dev.granary.core.format;

import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
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

    /**
     * The conventional repositories of this format (proxy of the public registry, a hosted repository, a group),
     * created on demand by {@code POST /api/v1/repositories/standard}. Members before groups.
     */
    default List<RepositoryTemplate> templates() {
        return List.of();
    }

    /** Ready-to-paste client configuration for one repository, shown on its "Connect" page. */
    default List<ConnectSnippet> connectSnippets(ConnectContext context) {
        return List.of();
    }

    /** Format-specific repository settings the UI offers and {@link #validate(Repository)} may check. */
    default List<SettingDescriptor> settings() {
        return List.of();
    }

    /**
     * Claims a request that arrived at the server root (outside {@code /repository/…}) for one of this format's
     * repositories. Only protocols with fixed root URLs need this; everything else answers {@link Optional#empty()}.
     */
    default Optional<RootRoute> routeRoot(RootRequest request) {
        return Optional.empty();
    }
}
