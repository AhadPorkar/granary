package dev.granary.format.npm;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every write a hosted npm repository accepts: publish, deprecate, dist-tag edits and the three
 * unpublish shapes. Each method validates completely before it stores anything, so a refused publish
 * (a conflicting version, a bad integrity) leaves the repository exactly as it was.
 */
@Component
public class NpmHostedWriter {

    /** What npmjs.org serves tarballs as; npm does not care, but mirrors of it are easiest to compare. */
    public static final String TARBALL_CONTENT_TYPE = "application/octet-stream";
    public static final String VERSION_EXISTS = "cannot modify pre-existing version";

    private final StorageService storage;
    private final NpmPackuments packuments;

    public NpmHostedWriter(StorageService storage, NpmPackuments packuments) {
        this.storage = storage;
        this.packuments = packuments;
    }

    /**
     * Handles {@code PUT /{name}}: a publish when tarballs are attached, otherwise a metadata update such
     * as {@code npm deprecate}.
     *
     * @return the document as stored afterwards
     */
    public ObjectNode publish(Repository repo, String baseUrl, NpmPublishRequest request) {
        requireHosted(repo);
        String name = request.name();
        Instant now = Instant.now();
        Optional<ObjectNode> existing = packuments.stored(repo, name);
        if (!request.hasAttachments()) {
            ObjectNode doc = existing.orElseThrow(() -> request.versions().isEmpty()
                    ? NpmError.notFound("package '" + name + "' does not exist")
                    : NpmError.badRequest("no tarball attached to the publish of " + name));
            return updateMetadata(repo, doc, request, now);
        }
        ObjectNode doc = existing.orElseGet(() -> NpmPackument.newDocument(name));
        ObjectNode stored = NpmPackument.versions(doc);
        ObjectNode incoming = request.versions();
        for (String version : NpmPackument.versionNames(request.document())) {
            if (stored.has(version) && !repo.isAllowRedeploy()) {
                throw NpmError.conflict(VERSION_EXISTS);
            }
        }
        List<Published> published = new ArrayList<>();
        for (String version : NpmPackument.versionNames(request.document())) {
            JsonNode raw = incoming.get(version);
            if (!raw.isObject()) {
                throw NpmError.badRequest("version " + version + " is not an object");
            }
            ObjectNode versionNode = (ObjectNode) raw.deepCopy();
            String file = NpmPackument.tarballFileName(name, version, versionNode);
            byte[] tarball = attachmentFor(request, name, version, file);
            if (tarball == null) {
                if (stored.has(version)) {
                    mergeVersionMetadata((ObjectNode) stored.get(version), versionNode);
                    continue; // a deprecation riding along with a publish of another version
                }
                throw NpmError.badRequest("no tarball attached for version " + version);
            }
            published.add(new Published(version, file, prepare(name, version, versionNode, file, tarball, baseUrl), tarball));
        }
        for (Published p : published) {
            storage.store(repo, NpmPath.tarballPath(name, p.file()), new ByteArrayInputStream(p.tarball()),
                    TARBALL_CONTENT_TYPE, Asset.Origin.UPLOADED, false,
                    new StorageService.ComponentCoordinates(NpmPath.scopeOf(name), NpmPath.unscopedNameOf(name), p.version()));
            NpmPackument.putVersion(doc, p.version(), p.node(), now);
        }
        applyDistTags(doc, request.distTags());
        boolean describesLatest = NpmPackument.latest(doc)
                .map(latest -> published.stream().anyMatch(p -> p.version().equals(latest)))
                .orElse(true);
        if (existing.isEmpty() || describesLatest) {
            NpmPackument.copyDescriptiveFields(request.document(), doc);
        }
        return save(repo, doc, now);
    }

    private record Published(String version, String file, ObjectNode node, byte[] tarball) {
    }

    /**
     * Finds the tarball for a version. npm names the attachment {@code {name}-{version}.tgz} with the full,
     * scoped name, while the tarball URL ends in the unscoped file name, so both are tried; a lone
     * attachment for a lone version is accepted whatever it is called.
     */
    private static byte[] attachmentFor(NpmPublishRequest request, String name, String version, String file) {
        Map<String, byte[]> attachments = request.attachments();
        byte[] data = attachments.get(file);
        if (data == null) {
            data = attachments.get(name + "-" + version + ".tgz");
        }
        if (data == null && attachments.size() == 1 && request.versions().size() == 1) {
            data = attachments.values().iterator().next();
        }
        return data;
    }

    /** Fills in {@code dist} from the bytes, refusing a document whose own digests disagree with them. */
    private static ObjectNode prepare(String name, String version, ObjectNode node, String file, byte[] tarball,
                                      String baseUrl) {
        ObjectNode dist = NpmPackument.object(node, NpmPackument.DIST);
        String integrity = NpmPackument.string(dist, "integrity");
        if (integrity != null && !NpmIntegrity.matches(integrity, tarball)) {
            throw NpmError.badRequest("integrity of " + file + " does not match the attached tarball");
        }
        String shasum = NpmPackument.string(dist, "shasum");
        String actualShasum = NpmIntegrity.shasum(tarball);
        if (shasum != null && !shasum.equalsIgnoreCase(actualShasum)) {
            throw NpmError.badRequest("shasum of " + file + " does not match the attached tarball");
        }
        dist.put("shasum", actualShasum);
        dist.put("integrity", NpmIntegrity.integrity(tarball));
        dist.put(NpmPackument.TARBALL, NpmPackument.tarballUrl(baseUrl, name, file));
        node.put(NpmPackument.NAME, name);
        node.put("version", version);
        if (!node.has(NpmPackument.ID)) {
            node.put(NpmPackument.ID, name + "@" + version);
        }
        return node;
    }

    private ObjectNode updateMetadata(Repository repo, ObjectNode doc, NpmPublishRequest request, Instant now) {
        ObjectNode stored = NpmPackument.versions(doc);
        for (Map.Entry<String, JsonNode> e : request.versions().properties()) {
            JsonNode current = stored.get(e.getKey());
            if (current != null && current.isObject() && e.getValue().isObject()) {
                mergeVersionMetadata((ObjectNode) current, (ObjectNode) e.getValue());
            }
        }
        applyDistTags(doc, request.distTags());
        return save(repo, doc, now);
    }

    /** The only per-version member a client may change after publishing is the deprecation notice. */
    private static void mergeVersionMetadata(ObjectNode target, ObjectNode incoming) {
        String deprecated = NpmPackument.string(incoming, "deprecated");
        if (deprecated != null && !deprecated.isEmpty()) {
            target.put("deprecated", deprecated);
        } else {
            target.remove("deprecated");
        }
    }

    /** Sets the tags the client sent, ignoring any that point at a version the package does not have. */
    private static void applyDistTags(ObjectNode doc, ObjectNode incoming) {
        ObjectNode versions = NpmPackument.versions(doc);
        ObjectNode tags = NpmPackument.distTags(doc);
        for (Map.Entry<String, JsonNode> e : incoming.properties()) {
            if (e.getValue().isString() && versions.has(e.getValue().stringValue())) {
                tags.put(e.getKey(), e.getValue().stringValue());
            }
        }
        if (!tags.has(NpmPackument.LATEST)) {
            NpmSemver.highestRelease(NpmPackument.versionNames(doc)).ifPresent(v -> tags.put(NpmPackument.LATEST, v));
        }
    }

    // --- unpublish -----------------------------------------------------------------------------

    /**
     * {@code PUT /{name}/-rev/{rev}}: npm sends the document without the versions it wants gone. Those
     * versions and their tarballs are removed; the last version going removes the package.
     */
    public ObjectNode unpublishVersions(Repository repo, String name, ObjectNode incoming) {
        requireHosted(repo);
        ObjectNode doc = require(repo, name);
        Instant now = Instant.now();
        var keep = NpmPackument.versionNames(incoming);
        for (String version : new ArrayList<>(NpmPackument.versionNames(doc))) {
            if (keep.contains(version)) {
                continue;
            }
            String file = NpmPackument.tarballFileName(name, version, NpmPackument.versions(doc).get(version));
            NpmPackument.removeVersion(doc, version);
            storage.delete(repo, NpmPath.tarballPath(name, file));
        }
        if (NpmPackument.versionNames(doc).isEmpty()) {
            unpublishPackage(repo, name);
            return doc;
        }
        JsonNode tags = incoming.get(NpmPackument.DIST_TAGS);
        if (tags != null && tags.isObject()) {
            doc.putObject(NpmPackument.DIST_TAGS);
            applyDistTags(doc, (ObjectNode) tags);
        }
        return save(repo, doc, now);
    }

    /** {@code DELETE /{name}/-rev/{rev}}: removes the package, its tarballs and its components. */
    public void unpublishPackage(Repository repo, String name) {
        requireHosted(repo);
        boolean existed = false;
        for (Asset asset : storage.list(repo, name + "/-/")) {
            existed |= storage.delete(repo, asset.getPath());
        }
        existed |= storage.delete(repo, name);
        if (!existed) {
            throw NpmError.notFound("package '" + name + "' does not exist");
        }
    }

    /**
     * {@code DELETE /{name}/-/{file}/-rev/{rev}}: removes one tarball and, so the document never
     * advertises a download that would 404, every version that pointed at it.
     */
    public void deleteTarball(Repository repo, NpmPath path) {
        requireHosted(repo);
        boolean deleted = storage.delete(repo, path.tarballPath());
        Optional<ObjectNode> doc = packuments.stored(repo, path.packageName());
        if (doc.isEmpty()) {
            if (!deleted) {
                throw NpmError.notFound("tarball " + path.fileName() + " does not exist");
            }
            return;
        }
        boolean changed = false;
        for (String version : new ArrayList<>(NpmPackument.versionNames(doc.get()))) {
            String file = NpmPackument.tarballFileName(path.packageName(), version, NpmPackument.versions(doc.get()).get(version));
            if (path.fileName().equals(file)) {
                changed |= NpmPackument.removeVersion(doc.get(), version);
            }
        }
        if (!changed) {
            return;
        }
        if (NpmPackument.versionNames(doc.get()).isEmpty()) {
            unpublishPackage(repo, path.packageName());
        } else {
            save(repo, doc.get(), Instant.now());
        }
    }

    // --- dist-tags -----------------------------------------------------------------------------

    public ObjectNode setDistTag(Repository repo, String name, String tag, String version) {
        requireHosted(repo);
        ObjectNode doc = require(repo, name);
        if (!NpmPackument.versions(doc).has(version)) {
            throw NpmError.badRequest("version " + version + " of " + name + " does not exist");
        }
        NpmPackument.distTags(doc).put(tag, version);
        return save(repo, doc, Instant.now());
    }

    public ObjectNode removeDistTag(Repository repo, String name, String tag) {
        requireHosted(repo);
        ObjectNode doc = require(repo, name);
        if (NpmPackument.LATEST.equals(tag)) {
            throw NpmError.badRequest("the latest tag cannot be removed");
        }
        if (NpmPackument.distTags(doc).remove(tag) == null) {
            throw NpmError.notFound("tag " + tag + " does not exist on " + name);
        }
        return save(repo, doc, Instant.now());
    }

    // --- helpers -------------------------------------------------------------------------------

    private ObjectNode require(Repository repo, String name) {
        return packuments.stored(repo, name)
                .orElseThrow(() -> NpmError.notFound("package '" + name + "' does not exist"));
    }

    private ObjectNode save(Repository repo, ObjectNode doc, Instant now) {
        NpmPackument.touch(doc, now);
        NpmPackument.bumpRevision(doc);
        packuments.store(repo, doc);
        return doc;
    }

    static void requireHosted(Repository repo) {
        if (!repo.isHosted()) {
            throw NpmError.methodNotAllowed("'" + repo.getName() + "' is a " + repo.getType().name().toLowerCase(Locale.ROOT)
                    + " repository; publish to a hosted repository instead");
        }
    }
}
