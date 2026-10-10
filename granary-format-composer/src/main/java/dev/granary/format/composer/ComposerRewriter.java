package dev.granary.format.composer;

import dev.granary.core.model.Repository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns stored or cached metadata into what a client sees, and merges the documents of a group's members.
 *
 * <p>Every dist URL a client receives points at the repository it asked (for a group, the group), so that the
 * archive is fetched, cached and served by Granary: hosted entries carry repository-relative paths that are made
 * absolute, proxied entries are pointed at {@code dist/{vendor}/{name}/{version}/{file}} unless the proxy's
 * {@code composer.distRewrite} is off. {@code source} entries (git) are left alone.
 */
public final class ComposerRewriter {

    private ComposerRewriter() {
    }

    /** A hosted document with absolute dist URLs under {@code baseUrl}. */
    public static P2Document hosted(P2Document doc, String baseUrl) {
        List<ObjectNode> versions = new ArrayList<>(doc.versions().size());
        for (ObjectNode entry : doc.versions()) {
            ObjectNode copy = entry.deepCopy();
            if (copy.get(P2Document.DIST) instanceof ObjectNode dist) {
                String url = P2Document.string(dist, "url");
                if (url != null && !url.isEmpty() && !ComposerDist.isAbsolute(url)) {
                    dist.put("url", ComposerDist.absolute(baseUrl, url));
                }
            }
            versions.add(copy);
        }
        return doc.withVersions(versions);
    }

    /** A proxied document whose archives are served by the repository at {@code baseUrl}. */
    public static P2Document proxied(P2Document doc, String baseUrl, boolean rewriteDist) {
        List<ObjectNode> versions = new ArrayList<>(doc.versions().size());
        for (ObjectNode entry : doc.versions()) {
            ObjectNode copy = entry.deepCopy();
            // a per-package notification URL would send install notifications past Granary
            copy.remove("notification-url");
            if (rewriteDist && copy.get(P2Document.DIST) instanceof ObjectNode dist) {
                String version = P2Document.version(copy);
                Optional<String> file = ComposerDist.fileName(copy);
                if (file.isPresent() && ComposerDist.isPathSafe(version)) {
                    dist.put("url", ComposerDist.url(baseUrl, doc.name(), version, file.get()));
                    dist.remove("mirrors");
                }
            }
            versions.add(copy);
        }
        return doc.withVersions(versions);
    }

    /**
     * The union of the members' documents, earlier documents first: the first document that has a version (by
     * normalised version) supplies it. Versions come out newest first, extra top-level keys from the first document
     * that has them, security advisories from all of them.
     */
    public static P2Document merge(String name, List<P2Document> docs) {
        Map<String, ObjectNode> byVersion = new LinkedHashMap<>();
        Instant lastModified = null;
        ObjectNode extras = null;
        List<JsonNode> advisories = new ArrayList<>();
        for (P2Document doc : docs) {
            for (ObjectNode entry : doc.versions()) {
                String key = P2Document.normalized(entry);
                if (key != null) {
                    byVersion.putIfAbsent(key, entry);
                }
            }
            lastModified = latest(lastModified, doc.lastModified());
            if (extras == null) {
                extras = doc.extras().deepCopy();
            } else {
                for (Map.Entry<String, JsonNode> e : doc.extras().properties()) {
                    if (!extras.has(e.getKey())) {
                        extras.set(e.getKey(), e.getValue());
                    }
                }
            }
            if (doc.extras().get("security-advisories") instanceof ArrayNode list) {
                list.forEach(advisories::add);
            }
        }
        if (extras != null && !advisories.isEmpty()) {
            extras.set("security-advisories", dedupeAdvisories(advisories));
        }
        List<Map.Entry<String, ObjectNode>> sorted = new ArrayList<>(byVersion.entrySet());
        sorted.sort((a, b) -> ComposerVersions.NEWEST_FIRST.compare(a.getKey(), b.getKey()));
        List<ObjectNode> versions = sorted.stream().map(Map.Entry::getValue).toList();
        return new P2Document(name, versions, extras, lastModified);
    }

    /** Advisories from several sources, each {@code advisoryId} once, in first-seen order. */
    public static ArrayNode dedupeAdvisories(Collection<JsonNode> advisories) {
        ArrayNode out = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode advisory : advisories) {
            String id = P2Document.string(advisory, "advisoryId");
            if (id == null || seen.add(id)) {
                out.add(advisory);
            }
        }
        return out;
    }

    /** The later of two instants, either of which may be {@code null}. */
    public static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    /** When a document served for {@code repo} may have changed: its content or the repository's configuration. */
    public static Instant lastModified(P2Document doc, Repository repo) {
        return latest(doc.lastModified(), repo.getUpdatedAt());
    }
}
