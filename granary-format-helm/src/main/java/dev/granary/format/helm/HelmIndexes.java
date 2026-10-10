package dev.granary.format.helm;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StoredContent;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The index of any repository type and the files it lists. Hosted repositories generate theirs from the stored
 * entries, proxies rewrite the upstream one, groups merge their members' (the first member that has a chart
 * version wins it). Indexes and their rendered YAML are cached by what they were computed from, so a large
 * upstream index is parsed once per change rather than once per {@code helm repo update}.
 */
@Component
public class HelmIndexes {

    /** A rendered {@code index.yaml}: the bytes and their ETag. */
    record Rendered(byte[] bytes, String etag) {
    }

    private final HelmHosted hosted;
    private final HelmProxy proxy;
    private final GroupResolver groups;
    private final BoundedCache<String, IndexSnapshot> snapshots = new BoundedCache<>(64, 200_000, IndexSnapshot::weight);
    private final BoundedCache<String, Rendered> rendered = new BoundedCache<>(64, 64L * 1024 * 1024, r -> r.bytes().length);

    public HelmIndexes(HelmHosted hosted, HelmProxy proxy, GroupResolver groups) {
        this.hosted = hosted;
        this.proxy = proxy;
        this.groups = groups;
    }

    /** The repository's index with repository-relative paths; empty when a proxy (or every member of a group) has none. */
    public Optional<IndexSnapshot> snapshot(Repository repo) {
        return switch (repo.getType()) {
            case HOSTED -> {
                HelmHosted.Listing listing = hosted.listing(repo);
                yield Optional.of(snapshots.get(listing.key(),
                        () -> new IndexSnapshot(listing.key(), hosted.index(repo, listing), Instant.now(), null)));
            }
            case PROXY -> proxy.snapshot(repo);
            case GROUP -> {
                List<IndexSnapshot> members = new ArrayList<>();
                for (Repository member : groups.leafMembers(repo)) {
                    try {
                        snapshot(member).ifPresent(members::add);
                    } catch (HelmError e) {
                        // a member whose upstream serves garbage must not take the whole group down
                    }
                }
                if (members.isEmpty()) {
                    yield Optional.empty();
                }
                StringBuilder key = new StringBuilder("group:").append(repo.getId());
                members.forEach(m -> key.append('|').append(m.key()));
                String k = key.toString();
                yield Optional.of(snapshots.get(k, () -> new IndexSnapshot(k,
                        HelmIndex.merge(members.stream().map(IndexSnapshot::index).toList()), Instant.now(), null)));
            }
        };
    }

    /** {@code index.yaml} for a client that addressed the repository as {@code baseUrl}. */
    Rendered render(Repository repo, IndexSnapshot snapshot, String baseUrl) {
        boolean relative = HelmSettings.relativeUrls(repo);
        String key = snapshot.key() + "|" + baseUrl + "|" + relative;
        return rendered.get(key, () -> {
            HelmIndex index = withUrls(snapshot.index(), baseUrl, relative);
            Map<String, Object> serverInfo = new LinkedHashMap<>();
            serverInfo.put("contextPath", contextPath(baseUrl));
            byte[] bytes = index.toYaml(DateTimeFormatter.ISO_INSTANT.format(snapshot.computedAt()), serverInfo);
            return new Rendered(bytes, HexFormat.of().formatHex(HelmHosted.sha256().digest(bytes)));
        });
    }

    /** The index with every repository path turned into the URL a client downloads from. */
    static HelmIndex withUrls(HelmIndex index, String baseUrl, boolean relative) {
        return index.map(entry -> {
            List<String> urls = entry.urls();
            if (urls.isEmpty()) {
                return entry;
            }
            String path = urls.getFirst();
            String url = relative ? relativeUrl(path) : UrlPaths.join(baseUrl, path);
            return entry.withUrls(List.of(url));
        });
    }

    private static String relativeUrl(String path) {
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(UrlPaths.encodeSegment(segment));
        }
        return sb.toString();
    }

    /**
     * The path of the repository URL, which ChartMuseum publishes as {@code serverInfo.contextPath}: {@code helm cm-push}
     * reads it to post uploads to {@code <contextPath>/api/charts}.
     */
    static String contextPath(String baseUrl) {
        try {
            String path = URI.create(baseUrl).getRawPath();
            return path == null ? "" : path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    // --- files -----------------------------------------------------------------------------------

    /** An archive or provenance file by repository path; a group asks its members in order. */
    public Optional<StoredContent> file(Repository repo, String path) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.file(repo, path);
            case PROXY -> proxy.file(repo, path);
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = file(member, path);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    /** The archive or provenance file of a chart version; a group asks its members in order. */
    public Optional<StoredContent> file(Repository repo, String name, String version, boolean provenance) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.file(repo, name, version, provenance);
            case PROXY -> proxy.file(repo, name, version, provenance);
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = file(member, name, version, provenance);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    public Optional<StoredContent> artifactHubMetadata(Repository repo) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.file(repo, HelmPath.ARTIFACTHUB_FILE);
            case PROXY -> proxy.artifactHubMetadata(repo);
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = artifactHubMetadata(member);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }
}
