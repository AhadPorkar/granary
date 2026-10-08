package dev.granary.core.proxy;

import java.time.Duration;

/**
 * How a proxy should treat a path.
 *
 * @param metadata whether the document is a mutable index (re-validated after {@code maxAge}) or an
 *                 immutable artifact (served from cache forever once fetched)
 * @param maxAge   for metadata: how old a cached copy may be before upstream is asked again;
 *                 {@code null} means "use the repository's configured default"
 */
public record FetchOptions(boolean metadata, Duration maxAge) {

    private static final FetchOptions ARTIFACT = new FetchOptions(false, null);
    private static final FetchOptions METADATA = new FetchOptions(true, null);

    public static FetchOptions artifact() {
        return ARTIFACT;
    }

    public static FetchOptions index() {
        return METADATA;
    }

    public static FetchOptions index(Duration maxAge) {
        return new FetchOptions(true, maxAge);
    }
}
