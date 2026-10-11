package dev.granary.core.proxy;

import dev.granary.core.storage.StorageService;
import java.time.Duration;

/**
 * How a proxy should treat a path.
 *
 * @param metadata  whether the document is a mutable index (re-validated after {@code maxAge}) or an
 *                  immutable artifact (served from cache forever once fetched)
 * @param maxAge    for metadata: how old a cached copy may be before upstream is asked again;
 *                  {@code null} means "use the repository's configured default"
 * @param component the component (package version) the cached file belongs to, or {@code null} for files that
 *                  belong to none (index documents). A proxy stores what it fetches linked to this component, and
 *                  links a copy it cached earlier without one when it serves it, so the component browser and search
 *                  list what the proxy holds. Hosted repositories and groups ignore it: hosted content is linked
 *                  when it is uploaded, and a group stores nothing of its own (its proxy members do the linking).
 */
public record FetchOptions(boolean metadata, Duration maxAge, StorageService.ComponentCoordinates component) {

    private static final FetchOptions ARTIFACT = new FetchOptions(false, null);
    private static final FetchOptions METADATA = new FetchOptions(true, null);

    public FetchOptions(boolean metadata, Duration maxAge) {
        this(metadata, maxAge, null);
    }

    public static FetchOptions artifact() {
        return ARTIFACT;
    }

    /** An immutable artifact that belongs to the component with these coordinates ({@code null}: to none). */
    public static FetchOptions artifact(StorageService.ComponentCoordinates component) {
        return ARTIFACT.withComponent(component);
    }

    public static FetchOptions index() {
        return METADATA;
    }

    public static FetchOptions index(Duration maxAge) {
        return new FetchOptions(true, maxAge);
    }

    /** These options with the file linked to the component with these coordinates ({@code null}: to none). */
    public FetchOptions withComponent(StorageService.ComponentCoordinates coordinates) {
        return new FetchOptions(metadata, maxAge, coordinates);
    }
}
