package dev.granary.core.format;

import dev.granary.core.model.Repository;
import java.util.List;
import java.util.Optional;

/**
 * A request that arrived at the server root rather than under {@code /repository/…}.
 *
 * @param method       HTTP method
 * @param path         decoded path relative to the server root without a leading slash, e.g. {@code v2/library/nginx/manifests/latest}
 * @param repositories lookup for the repositories the route may target
 */
public record RootRequest(String method, String path, RepositoryLookup repositories) {

    /** Read access to repository definitions, for routing decisions. */
    public interface RepositoryLookup {
        Optional<Repository> find(String name);

        /** Repositories of a format, ordered by name. */
        List<Repository> ofFormat(String format);
    }

    public boolean startsWith(String prefix) {
        return path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
    }
}
