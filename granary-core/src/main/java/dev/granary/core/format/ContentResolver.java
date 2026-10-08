package dev.granary.core.format;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Looks a path up the way a client expects it, whatever the repository type: hosted repositories answer
 * from local storage, proxies consult their cache and upstream, groups ask their members in order and
 * return the first hit.
 */
@Component
public class ContentResolver {

    private final StorageService storage;
    private final ProxyService proxy;
    private final GroupResolver groups;

    public ContentResolver(StorageService storage, ProxyService proxy, GroupResolver groups) {
        this.storage = storage;
        this.proxy = proxy;
        this.groups = groups;
    }

    public Optional<StoredContent> resolve(Repository repo, String path, FetchOptions options) {
        if (!repo.isOnline()) {
            return Optional.empty();
        }
        return switch (repo.getType()) {
            case HOSTED -> storage.find(repo, path);
            case PROXY -> proxy.fetch(repo, path, options);
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = resolve(member, path, options);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    public Optional<StoredContent> resolve(Repository repo, String path) {
        return resolve(repo, path, FetchOptions.artifact());
    }
}
