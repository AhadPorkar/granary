package dev.granary.format.cocoapods;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Answers the questions a CDN source asks, for every repository type: hosted repositories from their storage,
 * proxies through their cache, groups by asking their members in order. Groups always present the standard
 * layout ({@code [1, 1, 1]}); a proxied repository that shards differently is translated, as long as its shards
 * are not finer than the group's.
 */
@Component
public class CocoaPodsResolver {

    private static final Logger log = LoggerFactory.getLogger(CocoaPodsResolver.class);

    private final CocoaPodsHosted hosted;
    private final CocoaPodsProxy proxy;
    private final GroupResolver groups;

    public CocoaPodsResolver(CocoaPodsHosted hosted, CocoaPodsProxy proxy, GroupResolver groups) {
        this.hosted = hosted;
        this.proxy = proxy;
        this.groups = groups;
    }

    /**
     * A podspec and the repository that holds it.
     *
     * @param holder  the hosted or proxy repository the podspec came from
     * @param content the stored podspec, exactly as pushed or as the upstream served it
     */
    public record Found(Repository holder, StoredContent content) {
    }

    /** The layout {@code repo} presents to clients: the upstream's for a proxy, the standard one otherwise. */
    public ShardLayout layout(Repository repo) {
        return repo.isProxy() ? proxy.layout(repo) : ShardLayout.STANDARD;
    }

    // --- index documents --------------------------------------------------------------------

    /**
     * Pod name to versions of every pod whose MD5 starts with {@code hexPrefix} (the group's shard, e.g. {@code a75}).
     * Empty when no member could answer at all, or when nothing was found while a member could not answer (its
     * upstream is down and nothing is cached), so the client gets a retryable error instead of an empty shard.
     */
    public Optional<Map<String, List<String>>> shard(Repository repo, String hexPrefix) {
        return switch (repo.getType()) {
            case HOSTED -> Optional.of(hosted.shard(repo, ShardLayout.STANDARD.split(hexPrefix)));
            case PROXY -> proxyShard(repo, hexPrefix);
            case GROUP -> {
                List<Map<String, List<String>>> found = new ArrayList<>();
                boolean missing = false;
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<Map<String, List<String>>> shard = shard(member, hexPrefix);
                    if (shard.isPresent()) {
                        found.add(shard.get());
                    } else {
                        missing = true;
                    }
                }
                Map<String, List<String>> merged = PodIndex.mergeShards(found);
                yield found.isEmpty() || (merged.isEmpty() && missing) ? Optional.empty() : Optional.of(merged);
            }
        };
    }

    private Optional<Map<String, List<String>>> proxyShard(Repository repo, String hexPrefix) {
        ShardLayout layout = proxy.layout(repo);
        if (layout.totalLength() > hexPrefix.length()) {
            log.warn("{} shards by {} hex characters, more than the {} of a group; its pods are left out of group indexes",
                    repo.getName(), layout.totalLength(), hexPrefix.length());
            return Optional.empty();
        }
        String file = ShardLayout.shardFileName(layout.split(hexPrefix));
        return proxy.index(repo, file).flatMap(CocoaPodsResolver::text).map(text -> {
            Map<String, List<String>> pods = PodIndex.parseShard(text);
            return layout.totalLength() < hexPrefix.length() ? PodIndex.filterByHashPrefix(pods, hexPrefix) : pods;
        });
    }

    /** Every pod name; same presence rules as {@link #shard}. */
    public Optional<List<String>> podNames(Repository repo) {
        return switch (repo.getType()) {
            case HOSTED -> Optional.of(hosted.podNames(repo));
            case PROXY -> proxy.index(repo, CocoaPodsPath.ALL_PODS).flatMap(CocoaPodsResolver::text).map(PodIndex::lines);
            case GROUP -> union(repo, this::podNames);
        };
    }

    /** The podspec paths of deprecated versions, in the standard layout. */
    public Optional<List<String>> deprecated(Repository repo) {
        return switch (repo.getType()) {
            case HOSTED -> Optional.of(hosted.deprecated(repo));
            case PROXY -> proxy.index(repo, CocoaPodsPath.DEPRECATED).flatMap(CocoaPodsResolver::text).map(text -> {
                ShardLayout layout = proxy.layout(repo);
                List<String> lines = PodIndex.lines(text);
                if (layout.equals(ShardLayout.STANDARD)) {
                    return lines;
                }
                List<String> translated = new ArrayList<>(lines.size());
                for (String line : lines) {
                    PodIndex.translatePodspecPath(line, ShardLayout.STANDARD).ifPresent(translated::add);
                }
                return translated;
            });
            case GROUP -> union(repo, this::deprecated);
        };
    }

    private Optional<List<String>> union(Repository group, java.util.function.Function<Repository, Optional<List<String>>> source) {
        Set<String> all = new LinkedHashSet<>();
        boolean any = false;
        boolean missing = false;
        for (Repository member : groups.leafMembers(group)) {
            Optional<List<String>> lines = source.apply(member);
            if (lines.isPresent()) {
                any = true;
                all.addAll(lines.get());
            } else {
                missing = true;
            }
        }
        return !any || (all.isEmpty() && missing) ? Optional.empty() : Optional.of(new ArrayList<>(all));
    }

    // --- podspecs and sources ---------------------------------------------------------------

    /** The podspec of a version from the first repository that has it. */
    public Optional<Found> podspec(Repository repo, String name, String version) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.podspec(repo, name, version).map(c -> new Found(repo, c));
            case PROXY -> proxy.podspec(repo, name, version).map(c -> new Found(repo, c));
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<Found> hit = podspec(member, name, version);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    /** A source archive from the first repository that has (or can fetch) it. */
    public Optional<StoredContent> source(Repository repo, String name, String version, String filename) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.source(repo, name, version, filename);
            case PROXY -> proxy.source(repo, name, version, filename);
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = source(member, name, version, filename);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    /**
     * The podspec as the client asked for it through {@code entry} (the repository in the URL) at {@code baseUrl}:
     * an http source becomes {@code <baseUrl>/sources/<Name>/<version>/<file>} when it is an archive stored in a
     * hosted repository, or when the proxy that served it rewrites sources (and so does the group in between).
     * Anything that is not a parseable podspec with such a source is returned byte for byte.
     */
    public byte[] render(Repository entry, Found found, String name, String version, String baseUrl) throws IOException {
        byte[] original = found.content().readAllBytes();
        Optional<Podspec> spec = Podspec.tryParse(original);
        if (spec.isEmpty()) {
            log.warn("podspec {} in {} is not a JSON object; served unchanged", found.content().asset().getPath(),
                    found.holder().getName());
            return original;
        }
        Optional<String> url = spec.get().httpSource().filter(SourceArchive::isHttpUrl);
        if (url.isEmpty()) {
            return original;
        }
        Repository holder = found.holder();
        boolean rewrite = holder.isHosted()
                ? hosted.isLocalSource(holder, name, version, url.get())
                : CocoaPodsProxy.rewritesSources(holder) && (!entry.isGroup() || CocoaPodsProxy.rewritesSources(entry));
        if (!rewrite) {
            return original;
        }
        String filename = SourceArchive.filename(url.get(), spec.get().sourceType().orElse(null));
        String local = UrlPaths.join(baseUrl, CocoaPodsPath.sourcePath(name, version, filename));
        if (local.equals(url.get()) && spec.get().sourceHeaders().isEmpty()) {
            return original;
        }
        return spec.get().withHttpSource(local).toBytes();
    }

    private static Optional<String> text(StoredContent content) {
        try {
            return Optional.of(content.readUtf8());
        } catch (IOException e) {
            log.warn("cached {} is unreadable: {}", content.asset().getPath(), e.getMessage());
            return Optional.empty();
        }
    }
}
