package dev.granary.format.cargo;

import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Index files and crate files for every repository type: hosted repositories answer from storage, proxies
 * from their cache and the upstream sparse index, groups merge their members.
 *
 * <p>In a group the members are consulted hosted first (then in their configured order), and the first
 * member that lists a version owns it: its index line is the one served and its crate file the one
 * downloaded, so the checksum cargo verifies always matches the bytes it gets, and a private crate can never
 * be shadowed by a public one of the same name.
 *
 * <p>A proxy fetches one path at a time: concurrent first requests for the same index file or crate (two CI
 * jobs resolving the same lock file) wait for the first download and then read the cache, instead of
 * downloading the file twice and racing to record it. Locks are never nested, so they cannot deadlock.
 */
@org.springframework.stereotype.Component
public class CargoIndexes {

    private static final Logger log = LoggerFactory.getLogger(CargoIndexes.class);

    private final StorageService storage;
    private final ProxyService proxy;
    private final GroupResolver groups;
    private final CargoHosted hosted;
    private final ReentrantLock[] fetchLocks = new ReentrantLock[256];

    public CargoIndexes(StorageService storage, ProxyService proxy, GroupResolver groups, CargoHosted hosted) {
        this.storage = storage;
        this.proxy = proxy;
        this.groups = groups;
        this.hosted = hosted;
        for (int i = 0; i < fetchLocks.length; i++) {
            fetchLocks[i] = new ReentrantLock();
        }
    }

    /** The index lines of a crate as the repository serves them, each with the leaf repository it comes from. */
    public List<IndexEntry.Sourced<Repository>> entries(Repository repo, String name) {
        if (repo.isGroup()) {
            return IndexEntry.merge(members(repo), member -> leafEntries(member, name), member -> member);
        }
        return leafEntries(repo, name).stream().map(e -> new IndexEntry.Sourced<>(e, repo)).toList();
    }

    /** The members of a group in precedence order: hosted repositories first, otherwise as configured. */
    List<Repository> members(Repository group) {
        List<Repository> leaves = groups.leafMembers(group);
        List<Repository> ordered = new ArrayList<>(leaves.size());
        leaves.stream().filter(Repository::isHosted).forEach(ordered::add);
        leaves.stream().filter(r -> !r.isHosted()).forEach(ordered::add);
        return ordered;
    }

    /** A hosted or proxy repository's own lines for a crate; empty when it has none (or cannot get them now). */
    List<IndexEntry> leafEntries(Repository repo, String name) {
        if (!repo.isOnline()) {
            return List.of();
        }
        return switch (repo.getType()) {
            case HOSTED -> hosted.liveEntries(repo, name);
            case PROXY -> locked(repo, CrateNames.indexPath(name), () -> proxy.fetch(repo, CrateNames.indexPath(name),
                    upstreamIndexUri(repo, name), FetchOptions.index(), Map.of())).map(CargoIndexes::read).orElseGet(List::of);
            case GROUP -> entries(repo, name).stream().map(IndexEntry.Sourced::entry).toList();
        };
    }

    /** {@code <remoteUrl>/<dir>/<name>}: a sparse upstream keeps its files right below its root. */
    static URI upstreamIndexUri(Repository repo, String name) {
        return URI.create(UrlPaths.join(repo.getRemoteUrl().trim(), CrateNames.indexRelative(name)));
    }

    /**
     * The crate file of one version, from wherever the repository gets it. A proxy only downloads versions its
     * index lists (an upstream asked for a file it does not have may well answer 403, which would count as an
     * outage) and refuses a download whose checksum differs from the index.
     */
    public Optional<StoredContent> crate(Repository repo, String name, String version) {
        if (!repo.isOnline()) {
            return Optional.empty();
        }
        return switch (repo.getType()) {
            case HOSTED -> hosted.crate(repo, name, version);
            case PROXY -> proxyCrate(repo, name, version);
            case GROUP -> {
                for (Repository member : members(repo)) {
                    Optional<IndexEntry> entry = IndexEntry.find(leafEntries(member, name), version);
                    if (entry.isPresent()) {
                        yield crate(member, entry.get().name(), entry.get().vers());
                    }
                }
                yield Optional.empty();
            }
        };
    }

    private Optional<StoredContent> proxyCrate(Repository repo, String name, String version) {
        Optional<StoredContent> cached = storage.find(repo, CrateNames.cratePath(name, version));
        if (cached.isPresent()) {
            return cached;
        }
        Optional<IndexEntry> listed = IndexEntry.find(leafEntries(repo, name), version);
        if (listed.isEmpty()) {
            return Optional.empty();
        }
        IndexEntry entry = listed.get();
        String path = CrateNames.cratePath(entry.name(), entry.vers());
        return locked(repo, path, () -> fetchCrate(repo, entry, path));
    }

    /** Downloads (or, when another request just did, reads) a crate file, checks its checksum and records its component. */
    private Optional<StoredContent> fetchCrate(Repository repo, IndexEntry entry, String path) {
        URI upstream;
        try {
            upstream = URI.create(DownloadTemplate.expand(CargoSettings.downloadUrl(repo), entry.name(), entry.vers(),
                    entry.cksum()));
        } catch (IllegalArgumentException e) {
            throw CargoError.badGateway("the download URL of repository " + repo.getName() + " is not a valid URL: "
                    + e.getMessage());
        }
        Optional<StoredContent> fetched = proxy.fetch(repo, path, upstream, FetchOptions.artifact(), Map.of());
        if (fetched.isEmpty()) {
            return fetched;
        }
        StoredContent content = fetched.get();
        if (!entry.cksum().isEmpty() && !entry.cksum().equalsIgnoreCase(content.asset().getBlobSha256())) {
            storage.delete(repo, path);
            log.warn("{}: {} {} from {} has SHA-256 {} but the index says {}; not cached", repo.getName(), entry.name(),
                    entry.vers(), upstream, content.asset().getBlobSha256(), entry.cksum());
            throw CargoError.badGateway("the upstream of " + repo.getName() + " served " + entry.name() + " "
                    + entry.vers() + " with a checksum that does not match its index entry");
        }
        if (content.asset().getComponentId() == null) {
            Component component = storage.ensureComponent(repo,
                    new StorageService.ComponentCoordinates("", entry.name(), entry.vers()));
            content.asset().setComponentId(component.getId());
            storage.save(content.asset());
        }
        return Optional.of(content);
    }

    /** Runs {@code body} while holding the fetch lock of one repository path. */
    private <T> T locked(Repository repo, String path, Supplier<T> body) {
        ReentrantLock lock = fetchLocks[Math.floorMod((repo.getId() + "|" + path).hashCode(), fetchLocks.length)];
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    static List<IndexEntry> read(StoredContent content) {
        try {
            return IndexEntry.parseFile(content.readUtf8());
        } catch (IOException e) {
            log.warn("index file {} is unreadable: {}", content.asset().getPath(), e.getMessage());
            return List.of();
        }
    }
}
