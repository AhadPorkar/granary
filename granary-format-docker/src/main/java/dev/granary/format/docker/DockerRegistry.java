package dev.granary.format.docker;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The registry behind the HTTP handler: where manifests, tags and blobs of hosted, proxy and group repositories
 * come from and how pushes are validated. Image names are stored without the repository prefix; a proxy stores
 * them under the name the upstream knows them by ({@code library/alpine} for Docker Hub's {@code alpine}).
 */
@Service
public class DockerRegistry {

    private static final Logger log = LoggerFactory.getLogger(DockerRegistry.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** A manifest or blob that was found, with the digest and media type to answer with. */
    public record Found(StoredContent content, Digest digest, String contentType) {
    }

    /** The outcome of a manifest push. */
    public record Pushed(Digest digest, Optional<Digest> subject) {
    }

    private final StorageService storage;
    private final DockerUpstream upstream;
    private final GroupResolver groups;
    private final ObjectMapper mapper;

    public DockerRegistry(StorageService storage, DockerUpstream upstream, GroupResolver groups, ObjectMapper mapper) {
        this.storage = storage;
        this.upstream = upstream;
        this.groups = groups;
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ---- manifests -------------------------------------------------------------------------------------------

    /** The manifest {@code reference} (tag or digest) of image {@code name}, honouring {@code accept} for proxies. */
    public Optional<Found> manifest(Repository repo, String name, String reference, String accept) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> hostedManifest(repo, name, reference);
            case PROXY -> ConcurrentWrites.retryOnce(name + ":" + reference,
                    () -> proxyManifest(repo, name, reference, accept));
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<Found> found = manifest(member, name, reference, accept);
                    if (found.isPresent()) {
                        yield found;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    private Optional<Found> hostedManifest(Repository repo, String name, String reference) throws IOException {
        Optional<Digest> digest = Digest.parse(reference);
        if (digest.isEmpty()) {
            Optional<StoredContent> pointer = storage.find(repo, DockerLayout.tagPath(name, reference));
            if (pointer.isEmpty()) {
                return Optional.empty();
            }
            digest = Digest.parse(pointer.get().readUtf8().trim());
            if (digest.isEmpty()) {
                log.warn("tag {} of {} in {} does not point at a digest", reference, name, repo.getName());
                return Optional.empty();
            }
        }
        Digest d = digest.get();
        return storage.find(repo, DockerLayout.manifestPath(name, d))
                .map(content -> new Found(content, d, manifestContentType(content)));
    }

    private Optional<Found> proxyManifest(Repository repo, String name, String reference, String accept) throws IOException {
        String upstreamName = upstream.upstreamName(repo, name);
        Optional<Digest> digest = Digest.parse(reference);
        if (digest.isPresent()) {
            Digest d = digest.get();
            String path = DockerLayout.manifestPath(upstreamName, d);
            // a manifest pulled by digest (a platform manifest of an index, or an image pinned by digest) is the
            // component <name>@<digest>, as a manifest pushed by digest to a hosted repository is; one cached through
            // a tag already belongs to that tag's component and stays there
            Optional<StoredContent> content = upstream.fetch(repo, path, upstreamName + "/manifests/" + d, upstreamName,
                    FetchOptions.artifact(DockerLayout.coordinates(upstreamName, d.toString())), MediaTypes.DEFAULT_ACCEPT);
            if (content.isPresent() && !content.get().asset().getBlobSha256().equals(d.hex())) {
                log.warn("upstream of {} returned a manifest with digest sha256:{} for {}; discarding it",
                        repo.getName(), content.get().asset().getBlobSha256(), d);
                storage.delete(repo, path);
                return Optional.empty();
            }
            return content.map(c -> new Found(c, d, manifestContentType(c)));
        }
        String tagPath = DockerLayout.tagPath(upstreamName, reference, MediaTypes.variantKey(accept));
        Optional<StoredContent> content = upstream.fetch(repo, tagPath, upstreamName + "/manifests/" + reference,
                upstreamName, FetchOptions.index(), MediaTypes.upstreamAccept(accept));
        if (content.isEmpty()) {
            return Optional.empty();
        }
        StoredContent tag = content.get();
        Digest d = Digest.sha256(tag.asset().getBlobSha256());
        String contentType = manifestContentType(tag);
        String manifestPath = DockerLayout.manifestPath(upstreamName, d);
        StorageService.ComponentCoordinates coordinates = DockerLayout.coordinates(upstreamName, reference);
        if (!storage.exists(repo, manifestPath)) {
            try (InputStream in = tag.open()) {
                storage.store(repo, manifestPath, in, contentType, Asset.Origin.CACHED, false, coordinates);
            }
        }
        if (tag.asset().getComponentId() == null) {
            Component component = storage.ensureComponent(repo, coordinates);
            tag.asset().setComponentId(component.getId());
            storage.save(tag.asset());
        }
        return Optional.of(new Found(tag, d, contentType));
    }

    /** The media type to serve a stored manifest with: what was stored, or what the document declares. */
    String manifestContentType(StoredContent content) {
        String stored = content.contentType() == null ? "" : MediaTypes.stripParameters(content.contentType());
        if (MediaTypes.isManifestType(stored)) {
            return stored;
        }
        try {
            Optional<String> declared = Manifest.declaredMediaType(mapper, content.readAllBytes());
            if (declared.isPresent()) {
                return declared.get();
            }
        } catch (IOException | RuntimeException e) {
            log.debug("could not read the media type of {}: {}", content.asset().getPath(), e.getMessage());
        }
        return stored.isBlank() || stored.equals(MediaTypes.OCTET_STREAM) ? MediaTypes.OCI_MANIFEST : stored;
    }

    /**
     * Stores a pushed manifest in a hosted repository: validates it, checks every referenced blob and manifest is
     * present, refuses to move a tag unless the repository allows redeploys and records a component.
     */
    public Pushed putManifest(Repository repo, String name, String reference, byte[] body, String contentType) throws IOException {
        // a concurrent push of the same manifest or tag may win the insert; the retry re-runs every check against it
        return ConcurrentWrites.retryOnce(name + ":" + reference, () -> storeManifest(repo, name, reference, body, contentType));
    }

    private Pushed storeManifest(Repository repo, String name, String reference, byte[] body, String contentType) throws IOException {
        if (body.length > Manifest.MAX_SIZE) {
            throw DockerError.manifestInvalid("manifest exceeds " + Manifest.MAX_SIZE + " bytes");
        }
        Manifest manifest = Manifest.parse(mapper, body, contentType);
        Digest digest = Digest.of(body);
        Optional<Digest> byDigest = Digest.parse(reference);
        String tag = null;
        if (byDigest.isPresent()) {
            if (!byDigest.get().equals(digest)) {
                throw DockerError.badRequest(OciErrorCode.DIGEST_INVALID,
                        "manifest digest is " + digest + ", not " + reference,
                        Map.of("expected", reference, "actual", digest.toString()));
            }
        } else if (DockerPath.isValidTag(reference)) {
            tag = reference;
        } else if (reference.startsWith("sha256:") || reference.contains(":")) {
            throw DockerError.badRequest(OciErrorCode.DIGEST_INVALID, "unsupported digest " + reference);
        } else {
            throw DockerError.badRequest(OciErrorCode.TAG_INVALID, "invalid tag " + reference);
        }
        for (Digest blob : manifest.referencedBlobs()) {
            if (!storage.exists(repo, DockerLayout.blobPath(blob))) {
                throw DockerError.notFound(OciErrorCode.MANIFEST_BLOB_UNKNOWN, "blob " + blob + " is not in the repository",
                        Map.of("digest", blob.toString()));
            }
        }
        for (Digest child : manifest.referencedManifests()) {
            if (!storage.exists(repo, DockerLayout.manifestPath(name, child))) {
                throw DockerError.notFound(OciErrorCode.MANIFEST_BLOB_UNKNOWN, "manifest " + child + " is not in the repository",
                        Map.of("digest", child.toString()));
            }
        }
        if (tag != null) {
            Optional<StoredContent> existing = storage.find(repo, DockerLayout.tagPath(name, tag));
            if (existing.isPresent() && !repo.isAllowRedeploy()) {
                String current = existing.get().readUtf8().trim();
                if (!current.equals(digest.toString())) {
                    throw new DockerError(HttpStatus.CONFLICT, OciErrorCode.DENIED,
                            "tag " + tag + " of " + name + " already points at " + current
                                    + "; enable redeploys on repository " + repo.getName() + " to move it",
                            Map.of("tag", tag, "digest", current));
                }
            }
        }
        StorageService.ComponentCoordinates coordinates = DockerLayout.coordinates(name, tag != null ? tag : digest.toString());
        storage.store(repo, DockerLayout.manifestPath(name, digest), new ByteArrayInputStream(body), manifest.mediaType(),
                Asset.Origin.UPLOADED, false, coordinates);
        if (tag != null) {
            storage.store(repo, DockerLayout.tagPath(name, tag),
                    new ByteArrayInputStream(digest.toString().getBytes(StandardCharsets.UTF_8)),
                    "text/plain", Asset.Origin.UPLOADED, true, coordinates);
        }
        return new Pushed(digest, manifest.subjectDigest());
    }

    /** Deletes a manifest (by digest, together with the tags pointing at it) or untags (by tag). */
    public boolean deleteManifest(Repository repo, String name, String reference) throws IOException {
        Optional<Digest> digest = Digest.parse(reference);
        if (digest.isEmpty()) {
            return storage.delete(repo, DockerLayout.tagPath(name, reference));
        }
        boolean deleted = storage.delete(repo, DockerLayout.manifestPath(name, digest.get()));
        if (deleted) {
            for (Asset asset : storage.list(repo, DockerLayout.tagsPrefix(name))) {
                if (DockerLayout.tagOf(name, asset.getPath()).isEmpty()) {
                    continue;
                }
                Optional<StoredContent> pointer = storage.find(repo, asset.getPath());
                if (pointer.isPresent() && pointer.get().readUtf8().trim().equals(digest.get().toString())) {
                    storage.delete(repo, asset.getPath());
                }
            }
        }
        return deleted;
    }

    // ---- blobs -----------------------------------------------------------------------------------------------

    public Optional<Found> blob(Repository repo, String name, Digest digest) throws IOException {
        String path = DockerLayout.blobPath(digest);
        return switch (repo.getType()) {
            case HOSTED -> storage.find(repo, path).map(c -> new Found(c, digest, MediaTypes.OCTET_STREAM));
            case PROXY -> {
                Optional<StoredContent> cached = storage.find(repo, path);
                if (cached.isPresent()) {
                    yield cached.map(c -> new Found(c, digest, MediaTypes.OCTET_STREAM));
                }
                String upstreamName = upstream.upstreamName(repo, name);
                Optional<StoredContent> fetched = upstream.fetch(repo, path, upstreamName + "/blobs/" + digest, upstreamName,
                        FetchOptions.artifact(), "*/*");
                if (fetched.isPresent() && !fetched.get().asset().getBlobSha256().equals(digest.hex())) {
                    log.warn("upstream of {} returned a blob with digest sha256:{} for {}; discarding it",
                            repo.getName(), fetched.get().asset().getBlobSha256(), digest);
                    storage.delete(repo, path);
                    yield Optional.empty();
                }
                yield fetched.map(c -> new Found(c, digest, MediaTypes.OCTET_STREAM));
            }
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<Found> found = blob(member, name, digest);
                    if (found.isPresent()) {
                        yield found;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    public boolean blobExists(Repository repo, Digest digest) {
        return storage.exists(repo, DockerLayout.blobPath(digest));
    }

    public boolean deleteBlob(Repository repo, Digest digest) {
        return storage.delete(repo, DockerLayout.blobPath(digest));
    }

    // ---- listings --------------------------------------------------------------------------------------------

    /** The tags of {@code name}, lexically sorted; empty when the image is unknown to the repository. */
    public Optional<SortedSet<String>> tags(Repository repo, String name) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> {
                SortedSet<String> tags = storedTags(repo, name);
                boolean known = !tags.isEmpty() || !storage.list(repo, DockerLayout.manifestPrefix(name)).isEmpty();
                yield known ? Optional.of(tags) : Optional.empty();
            }
            case PROXY -> {
                String upstreamName = upstream.upstreamName(repo, name);
                SortedSet<String> tags = storedTags(repo, upstreamName);
                boolean known = !tags.isEmpty() || !storage.list(repo, DockerLayout.manifestPrefix(upstreamName)).isEmpty();
                Optional<StoredContent> listing = upstream.fetch(repo, DockerLayout.tagListCachePath(upstreamName),
                        upstreamName + "/tags/list?n=1000", upstreamName, FetchOptions.index(), MediaTypes.JSON);
                if (listing.isPresent()) {
                    known = true;
                    tags.addAll(parseTagList(listing.get()));
                }
                yield known ? Optional.of(tags) : Optional.empty();
            }
            case GROUP -> {
                SortedSet<String> tags = new TreeSet<>();
                boolean known = false;
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<SortedSet<String>> memberTags = tags(member, name);
                    if (memberTags.isPresent()) {
                        known = true;
                        tags.addAll(memberTags.get());
                    }
                }
                yield known ? Optional.of(tags) : Optional.empty();
            }
        };
    }

    private SortedSet<String> storedTags(Repository repo, String name) {
        SortedSet<String> tags = new TreeSet<>();
        for (Asset asset : storage.list(repo, DockerLayout.tagsPrefix(name))) {
            DockerLayout.tagOf(name, asset.getPath()).ifPresent(tags::add);
        }
        return tags;
    }

    private List<String> parseTagList(StoredContent listing) {
        try {
            JsonNode node = mapper.readTree(listing.readAllBytes());
            JsonNode tags = node == null ? null : node.get("tags");
            List<String> result = new ArrayList<>();
            if (tags != null && tags.isArray()) {
                for (JsonNode tag : tags) {
                    if (tag.isString() && DockerPath.isValidTag(tag.stringValue())) {
                        result.add(tag.stringValue());
                    }
                }
            }
            return result;
        } catch (IOException | RuntimeException e) {
            log.warn("could not parse the cached tag list {}: {}", listing.asset().getPath(), e.getMessage());
            return List.of();
        }
    }

    /** The image names of the repository (hosted: pushed, proxy: cached, group: union), lexically sorted. */
    public SortedSet<String> catalog(Repository repo) {
        SortedSet<String> names = new TreeSet<>();
        if (repo.isGroup()) {
            for (Repository member : groups.leafMembers(repo)) {
                names.addAll(catalog(member));
            }
            return names;
        }
        for (Asset asset : storage.list(repo, DockerLayout.MANIFESTS_PREFIX)) {
            DockerLayout.nameOfManifestPath(asset.getPath()).ifPresent(names::add);
        }
        return names;
    }

    /**
     * The OCI 1.1 referrers of {@code subject} in image {@code name}: an index of the stored manifests whose
     * {@code subject} is that digest, optionally filtered by artifact type.
     */
    public ObjectNode referrers(Repository repo, String name, Digest subject, String artifactType) throws IOException {
        Map<String, ObjectNode> descriptors = new LinkedHashMap<>();
        collectReferrers(repo, name, subject, artifactType, descriptors);
        ObjectNode index = NODES.objectNode();
        index.put("schemaVersion", 2);
        index.put("mediaType", MediaTypes.OCI_INDEX);
        ArrayNode manifests = index.putArray("manifests");
        descriptors.values().forEach(manifests::add);
        return index;
    }

    private void collectReferrers(Repository repo, String name, Digest subject, String artifactType,
                                  Map<String, ObjectNode> into) throws IOException {
        if (repo.isGroup()) {
            for (Repository member : groups.leafMembers(repo)) {
                collectReferrers(member, name, subject, artifactType, into);
            }
            return;
        }
        String storedName = repo.isProxy() ? upstream.upstreamName(repo, name) : name;
        for (Asset asset : storage.list(repo, DockerLayout.manifestPrefix(storedName))) {
            Optional<Digest> digest = DockerLayout.digestOfManifestPath(asset.getPath());
            if (digest.isEmpty() || into.containsKey(digest.get().toString()) || asset.getSize() > Manifest.MAX_SIZE) {
                continue;
            }
            Optional<StoredContent> content = storage.find(repo, asset.getPath());
            if (content.isEmpty()) {
                continue;
            }
            Manifest manifest;
            try {
                manifest = Manifest.parse(mapper, content.get().readAllBytes(), asset.getContentType());
            } catch (DockerError e) {
                continue;
            }
            if (manifest.subjectDigest().filter(subject::equals).isEmpty()) {
                continue;
            }
            String type = manifest.artifactType();
            if (type == null && manifest.config() != null) {
                type = manifest.config().mediaType();
            }
            if (artifactType != null && !artifactType.isBlank() && !artifactType.equals(type)) {
                continue;
            }
            ObjectNode descriptor = NODES.objectNode();
            descriptor.put("mediaType", manifest.mediaType());
            descriptor.put("digest", digest.get().toString());
            descriptor.put("size", asset.getSize());
            if (type != null) {
                descriptor.put("artifactType", type);
            }
            if (!manifest.annotations().isEmpty()) {
                ObjectNode annotations = descriptor.putObject("annotations");
                manifest.annotations().forEach(annotations::put);
            }
            into.put(digest.get().toString(), descriptor);
        }
    }
}
