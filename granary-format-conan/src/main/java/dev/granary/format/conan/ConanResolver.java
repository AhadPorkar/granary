package dev.granary.format.conan;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Answers every read of the protocol for any repository type: hosted repositories derive the documents from their
 * stored files, proxies cache the upstream documents (re-validated after the metadata max age, served stale while
 * upstream is unreachable) and files (forever), and groups merge their members, hosted ones first.
 *
 * <p>Every method returns empty for "does not exist here"; the handler turns that into a 404, which the dispatcher
 * reports as 503 when it is really an unreachable upstream.
 */
public class ConanResolver {

    private static final Logger log = LoggerFactory.getLogger(ConanResolver.class);
    private static final Map<String, String> JSON_ACCEPT = Map.of("Accept", "application/json");

    private final StorageService storage;
    private final ProxyService proxy;
    private final UpstreamClient upstreamClient;
    private final GroupResolver groups;
    private final ConanUpstreamAuth auth;
    private final ObjectMapper json;

    public ConanResolver(StorageService storage, ProxyService proxy, UpstreamClient upstreamClient, GroupResolver groups,
                         ConanUpstreamAuth auth, ObjectMapper json) {
        this.storage = storage;
        this.proxy = proxy;
        this.upstreamClient = upstreamClient;
        this.groups = groups;
        this.auth = auth;
        this.json = json;
    }

    // --- recipes -------------------------------------------------------------------------------

    /** Complete recipe revisions, newest first; empty when the recipe is unknown. */
    public Optional<List<ConanRevisions.Entry>> recipeRevisions(Repository repo, ConanReference ref) {
        return switch (repo.getType()) {
            case HOSTED -> nonEmpty(index(repo, ref).recipeRevisions());
            case PROXY -> document(repo, ConanRoutes.recipeRevisions(ref)).flatMap(doc -> nonEmpty(ConanRevisions.parseRevisions(doc)));
            case GROUP -> mergeRevisions(repo, member -> recipeRevisions(member, ref));
        };
    }

    /**
     * The newest recipe revision. Proxies forward {@code …/latest} to their upstream as the client would (falling
     * back to the first entry of the cached revision list), groups answer the newest of their members' answers.
     */
    public Optional<ConanRevisions.Entry> recipeLatest(Repository repo, ConanReference ref) {
        return switch (repo.getType()) {
            case HOSTED -> index(repo, ref).recipeRevisions().stream().findFirst();
            case PROXY -> document(repo, ConanRoutes.recipeLatest(ref)).flatMap(ConanRevisions::parseEntry)
                    .or(() -> recipeRevisions(repo, ref).map(entries -> entries.get(0)));
            case GROUP -> newest(repo, member -> recipeLatest(member, ref));
        };
    }

    public Optional<List<String>> recipeFiles(Repository repo, ConanReference ref, String rrev) {
        return switch (repo.getType()) {
            case HOSTED -> index(repo, ref).revision(rrev).map(r -> new ArrayList<>(r.files()));
            case PROXY -> document(repo, ConanRoutes.recipeFiles(ref, rrev)).flatMap(doc -> nonEmpty(ConanRevisions.parseFiles(doc)));
            case GROUP -> first(repo, member -> recipeFiles(member, ref, rrev));
        };
    }

    public Optional<StoredContent> recipeFile(Repository repo, ConanReference ref, String rrev, String file) {
        String stored = ConanLayout.exportPath(ref, rrev, file);
        return switch (repo.getType()) {
            case HOSTED -> storage.find(repo, stored);
            case PROXY -> cachedFile(repo, ref, stored, ConanRoutes.recipeFile(ref, rrev, file));
            case GROUP -> first(repo, member -> recipeFile(member, ref, rrev, file));
        };
    }

    // --- packages ------------------------------------------------------------------------------

    /** The binaries of a recipe revision keyed by package id, each described by its {@code conaninfo.txt}. */
    public Optional<ObjectNode> packageSearch(Repository repo, ConanReference ref, String rrev) {
        return switch (repo.getType()) {
            case HOSTED -> index(repo, ref).revision(rrev).map(revision -> describePackages(repo, revision));
            case PROXY -> document(repo, ConanRoutes.packageSearch(ref, rrev))
                    .filter(JsonNode::isObject).map(ObjectNode.class::cast);
            case GROUP -> {
                ObjectNode merged = JsonNodeFactory.instance.objectNode();
                boolean found = false;
                for (Repository member : members(repo)) {
                    Optional<ObjectNode> doc = packageSearch(member, ref, rrev);
                    if (doc.isPresent()) {
                        found = true;
                        for (Map.Entry<String, JsonNode> e : doc.get().properties()) {
                            if (!merged.has(e.getKey())) {
                                merged.set(e.getKey(), e.getValue());
                            }
                        }
                    }
                }
                yield found ? Optional.of(merged) : Optional.empty();
            }
        };
    }

    public Optional<List<ConanRevisions.Entry>> packageRevisions(Repository repo, ConanReference ref, String rrev, String packageId) {
        return switch (repo.getType()) {
            case HOSTED -> index(repo, ref).revision(rrev).flatMap(r -> r.pkg(packageId))
                    .flatMap(p -> nonEmpty(p.revisionEntries()));
            case PROXY -> document(repo, ConanRoutes.packageRevisions(ref, rrev, packageId))
                    .flatMap(doc -> nonEmpty(ConanRevisions.parseRevisions(doc)));
            case GROUP -> mergeRevisions(repo, member -> packageRevisions(member, ref, rrev, packageId));
        };
    }

    /** The newest revision of one binary, resolved like {@link #recipeLatest}. */
    public Optional<ConanRevisions.Entry> packageLatest(Repository repo, ConanReference ref, String rrev, String packageId) {
        return switch (repo.getType()) {
            case HOSTED -> packageRevisions(repo, ref, rrev, packageId).map(entries -> entries.get(0));
            case PROXY -> document(repo, ConanRoutes.packageLatest(ref, rrev, packageId)).flatMap(ConanRevisions::parseEntry)
                    .or(() -> packageRevisions(repo, ref, rrev, packageId).map(entries -> entries.get(0)));
            case GROUP -> newest(repo, member -> packageLatest(member, ref, rrev, packageId));
        };
    }

    public Optional<List<String>> packageFiles(Repository repo, ConanReference ref, String rrev, String packageId, String prev) {
        return switch (repo.getType()) {
            case HOSTED -> index(repo, ref).revision(rrev).flatMap(r -> r.pkg(packageId)).flatMap(p -> p.revision(prev))
                    .map(p -> new ArrayList<>(p.files()));
            case PROXY -> document(repo, ConanRoutes.packageFiles(ref, rrev, packageId, prev))
                    .flatMap(doc -> nonEmpty(ConanRevisions.parseFiles(doc)));
            case GROUP -> first(repo, member -> packageFiles(member, ref, rrev, packageId, prev));
        };
    }

    public Optional<StoredContent> packageFile(Repository repo, ConanReference ref, String rrev, String packageId,
                                               String prev, String file) {
        String stored = ConanLayout.packagePath(ref, rrev, packageId, prev, file);
        return switch (repo.getType()) {
            case HOSTED -> storage.find(repo, stored);
            case PROXY -> cachedFile(repo, ref, stored, ConanRoutes.packageFile(ref, rrev, packageId, prev, file));
            case GROUP -> first(repo, member -> packageFile(member, ref, rrev, packageId, prev, file));
        };
    }

    // --- search --------------------------------------------------------------------------------

    /**
     * References matching the pattern, sorted. A proxy whose upstream answered without results (or with a 404) has
     * none; a proxy that has nothing cached while it is offline or its upstream unreachable answers empty, so that
     * the handler's 404 becomes the dispatcher's 503 instead of an authoritative "nothing found". Groups merge what
     * their members know and skip members that cannot answer.
     */
    public Optional<List<String>> search(Repository repo, String pattern, boolean ignoreCase) {
        return switch (repo.getType()) {
            case HOSTED -> Optional.of(hostedSearch(repo, pattern, ignoreCase));
            case PROXY -> {
                String route = ConanRoutes.search(pattern, ignoreCase);
                // the query is already encoded; only the path goes through the upstream URL builder
                URI upstream = URI.create(upstreamClient.upstreamUri(repo, ConanRoutes.search())
                        + route.substring(ConanRoutes.search().length()));
                Optional<List<String>> found = document(repo, route, upstream)
                        .map(doc -> ConanSearch.merge(List.of(ConanSearch.parseResults(doc))));
                yield found.isPresent() || upstreamUnavailable(repo) ? found : Optional.of(List.of());
            }
            case GROUP -> {
                List<List<String>> sources = new ArrayList<>();
                for (Repository member : members(repo)) {
                    search(member, pattern, ignoreCase).ifPresent(sources::add);
                }
                yield Optional.of(ConanSearch.merge(sources));
            }
        };
    }

    /** The dispatcher's test for a miss that is the upstream's fault: offline mode or a failing upstream. */
    private boolean upstreamUnavailable(Repository repo) {
        if (proxy.isOffline(repo)) {
            return true;
        }
        UpstreamHealth.Status status = proxy.status(repo);
        return status != null && (!status.reachable() || status.consecutiveFailures() > 0);
    }

    private List<String> hostedSearch(Repository repo, String pattern, boolean ignoreCase) {
        TreeSet<String> references = new TreeSet<>();
        for (Asset asset : storage.list(repo, ConanLayout.ROOT)) {
            ConanLayout.parse(asset.getPath())
                    .filter(f -> !f.isPackage() && ConanHostedIndex.MANIFEST.equals(f.file()))
                    .map(f -> f.reference().display())
                    .filter(display -> ConanSearch.matches(pattern, display, ignoreCase))
                    .ifPresent(references::add);
        }
        return new ArrayList<>(references);
    }

    // --- groups --------------------------------------------------------------------------------

    /** The leaf members of a group with hosted repositories first, so their content takes precedence. */
    public List<Repository> members(Repository group) {
        List<Repository> leaves = groups.leafMembers(group);
        List<Repository> ordered = new ArrayList<>(leaves.size());
        for (Repository r : leaves) {
            if (r.isHosted()) {
                ordered.add(r);
            }
        }
        for (Repository r : leaves) {
            if (!r.isHosted()) {
                ordered.add(r);
            }
        }
        return ordered;
    }

    /** The first hosted leaf member of a group, for the "write here instead" message. */
    public Optional<String> hostedMember(Repository group) {
        return members(group).stream().filter(Repository::isHosted).map(Repository::getName).findFirst();
    }

    private <T> Optional<T> first(Repository group, Function<Repository, Optional<T>> lookup) {
        for (Repository member : members(group)) {
            Optional<T> hit = lookup.apply(member);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /** The newest entry any member knows; on equal times the earlier member (hosted before proxies) wins. */
    private Optional<ConanRevisions.Entry> newest(Repository group, Function<Repository, Optional<ConanRevisions.Entry>> lookup) {
        List<List<ConanRevisions.Entry>> sources = new ArrayList<>();
        for (Repository member : members(group)) {
            lookup.apply(member).ifPresent(entry -> sources.add(List.of(entry)));
        }
        return ConanRevisions.merge(sources).stream().findFirst();
    }

    private Optional<List<ConanRevisions.Entry>> mergeRevisions(Repository group,
                                                               Function<Repository, Optional<List<ConanRevisions.Entry>>> lookup) {
        List<List<ConanRevisions.Entry>> sources = new ArrayList<>();
        for (Repository member : members(group)) {
            lookup.apply(member).ifPresent(sources::add);
        }
        return sources.isEmpty() ? Optional.empty() : Optional.of(ConanRevisions.merge(sources));
    }

    // --- hosted --------------------------------------------------------------------------------

    private ConanHostedIndex index(Repository repo, ConanReference ref) {
        return ConanHostedIndex.of(ref, storage.list(repo, ConanLayout.recipePrefix(ref)));
    }

    /** Each binary of the revision described by the {@code conaninfo.txt} of its newest package revision. */
    private ObjectNode describePackages(Repository repo, ConanHostedIndex.Revision revision) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (ConanHostedIndex.Package pkg : revision.packages().values()) {
            Optional<ConanHostedIndex.PackageRevision> latest = pkg.latest();
            if (latest.isEmpty() || latest.get().conanInfo() == null) {
                continue;
            }
            try {
                String text = new StoredContent(latest.get().conanInfo(), storage.blobStoreOf(repo)).readUtf8();
                result.set(pkg.id(), ConanInfo.parse(text));
            } catch (IOException | RuntimeException e) {
                log.warn("conaninfo.txt of package {} in {} is unreadable: {}", pkg.id(), repo.getName(), e.getMessage());
            }
        }
        return result;
    }

    // --- proxy ---------------------------------------------------------------------------------

    private Optional<JsonNode> document(Repository repo, String path) {
        return document(repo, path, upstreamClient.upstreamUri(repo, path));
    }

    /** A cached upstream index document, parsed; a corrupt cache entry counts as missing. */
    private Optional<JsonNode> document(Repository repo, String cachePath, URI upstream) {
        Optional<StoredContent> content = proxy.fetch(upstreamView(repo), cachePath, upstream, FetchOptions.index(), JSON_ACCEPT);
        if (content.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode node = json.readTree(content.get().readAllBytes());
            return node == null || node.isMissingNode() || node.isNull() ? Optional.empty() : Optional.of(node);
        } catch (IOException | RuntimeException e) {
            log.warn("cached document {} of {} is not valid JSON: {}", cachePath, repo.getName(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A file, cached under the hosted layout and linked to its component. Revision files are immutable and cached
     * forever; files below {@code metadata/} may change without a new revision and are re-validated like documents.
     *
     * <p>The content type is the one this format serves the file with, not the upstream's: {@code conan_server}
     * for one answers {@code Content-Type: auto} for every file.
     */
    private Optional<StoredContent> cachedFile(Repository repo, ConanReference ref, String cachePath, String upstreamPath) {
        boolean mutable = ConanLayout.isMutableFile(cachePath);
        Optional<StoredContent> content = mutable ? Optional.empty() : storage.find(repo, cachePath);
        if (content.isEmpty()) {
            content = proxy.fetch(upstreamView(repo), cachePath, upstreamClient.upstreamUri(repo, upstreamPath),
                    mutable ? FetchOptions.index() : FetchOptions.artifact(), Map.of());
        }
        if (content.isEmpty()) {
            return content;
        }
        Asset asset = content.get().asset();
        String contentType = ConanLayout.contentType(cachePath.substring(cachePath.lastIndexOf('/') + 1));
        boolean unlinked = asset.getComponentId() == null;
        boolean retype = !contentType.equals(asset.getContentType());
        if (!unlinked && !retype) {
            return content;
        }
        if (unlinked) {
            Component component = ConanComponents.ensure(storage, repo, ConanComponents.coordinates(ref));
            asset.setComponentId(component.getId());
        }
        asset.setContentType(contentType);
        return Optional.of(new StoredContent(storage.save(asset), content.get().blobStore()));
    }

    /**
     * The repository definition handed to the proxy service: with upstream credentials that need the token flow,
     * a copy carrying the exchanged token. Offline repositories never talk to the upstream, not even to log in.
     * After the upstream answered 401 the token obtained before that answer is dropped and exchanged again.
     */
    private Repository upstreamView(Repository repo) {
        if (proxy.isOffline(repo)) {
            return repo;
        }
        UpstreamHealth.Status status = proxy.status(repo);
        if (status != null && status.lastError() != null && status.lastError().startsWith("HTTP 401")) {
            auth.rejected(repo, status.lastFailureAt());
        }
        return auth.authorised(repo);
    }

    // --- helpers -------------------------------------------------------------------------------

    private static <T> Optional<List<T>> nonEmpty(List<T> list) {
        return list.isEmpty() ? Optional.empty() : Optional.of(list);
    }
}
