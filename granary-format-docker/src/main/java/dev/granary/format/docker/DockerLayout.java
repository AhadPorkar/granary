package dev.granary.format.docker;

import dev.granary.core.storage.StorageService;
import java.util.Optional;

/**
 * Where a repository keeps its content.
 *
 * <pre>
 *   blobs/sha256/{hex}                  a layer or config blob, shared by every image of the repository
 *   manifests/{name}/sha256/{hex}       a manifest, immutable, stored under its own digest
 *   tags/{name}/{tag}                   hosted: the digest the tag points at; proxy: the manifest the upstream answered
 *   tags/{name}/{tag}@{variant}         a proxy's copy of a tag for clients that accept no multi-platform index
 *   tags/{name}/.list                   a proxy's cached upstream tag listing
 * </pre>
 *
 * Tag names never start with a dot and never contain {@code @}, so the two proxy-only entries cannot collide
 * with a tag. A proxy keeps the upstream's manifest bytes under the tag path rather than a pointer because that is
 * what it re-validates with the upstream's ETag; the blob store deduplicates them with the copy under
 * {@code manifests/}, and the digest of the tag is the asset's own blob digest.
 */
public final class DockerLayout {

    public static final String BLOBS_PREFIX = "blobs/sha256/";
    public static final String MANIFESTS_PREFIX = "manifests/";
    public static final String TAGS_PREFIX = "tags/";
    public static final String TAG_LIST_FILE = ".list";

    private DockerLayout() {
    }

    public static String blobPath(Digest digest) {
        return BLOBS_PREFIX + digest.hex();
    }

    public static String manifestPath(String name, Digest digest) {
        return manifestPrefix(name) + digest.hex();
    }

    public static String manifestPrefix(String name) {
        return MANIFESTS_PREFIX + name + "/sha256/";
    }

    public static String tagPath(String name, String tag) {
        return tagsPrefix(name) + tag;
    }

    public static String tagPath(String name, String tag, String variant) {
        return tagPath(name, tag) + (variant == null ? "" : variant);
    }

    public static String tagsPrefix(String name) {
        return TAGS_PREFIX + name + "/";
    }

    public static String tagListCachePath(String name) {
        return tagsPrefix(name) + TAG_LIST_FILE;
    }

    /** Tags, catalog listings and cached tag lists change; manifests by digest and blobs never do. */
    public static boolean isMetadata(String path) {
        return path != null && path.startsWith(TAGS_PREFIX);
    }

    /** The tag an asset under {@code tags/{name}/} represents, or empty for variants' owners, listings and nested names. */
    public static Optional<String> tagOf(String name, String assetPath) {
        String prefix = tagsPrefix(name);
        if (assetPath == null || !assetPath.startsWith(prefix)) {
            return Optional.empty();
        }
        String rest = assetPath.substring(prefix.length());
        if (rest.isEmpty() || rest.contains("/") || rest.startsWith(".")) {
            return Optional.empty();
        }
        int at = rest.indexOf('@');
        String tag = at >= 0 ? rest.substring(0, at) : rest;
        return DockerPath.isValidTag(tag) ? Optional.of(tag) : Optional.empty();
    }

    /** The image name of a {@code manifests/{name}/sha256/{hex}} asset path. */
    public static Optional<String> nameOfManifestPath(String assetPath) {
        if (assetPath == null || !assetPath.startsWith(MANIFESTS_PREFIX)) {
            return Optional.empty();
        }
        int marker = assetPath.lastIndexOf("/sha256/");
        if (marker <= MANIFESTS_PREFIX.length() - 1) {
            return Optional.empty();
        }
        String name = assetPath.substring(MANIFESTS_PREFIX.length(), marker);
        String hex = assetPath.substring(marker + "/sha256/".length());
        if (hex.contains("/") || !DockerPath.isValidName(name)) {
            return Optional.empty();
        }
        return Optional.of(name);
    }

    /** The digest of a {@code manifests/{name}/sha256/{hex}} asset path. */
    public static Optional<Digest> digestOfManifestPath(String assetPath) {
        int marker = assetPath == null ? -1 : assetPath.lastIndexOf("/sha256/");
        if (marker < 0) {
            return Optional.empty();
        }
        return Digest.parse("sha256:" + assetPath.substring(marker + "/sha256/".length()));
    }

    /** The component an image version belongs to: namespace = everything before the last segment, name = the last one. */
    public static StorageService.ComponentCoordinates coordinates(String name, String version) {
        int slash = name.lastIndexOf('/');
        String namespace = slash < 0 ? "" : name.substring(0, slash);
        String last = slash < 0 ? name : name.substring(slash + 1);
        return new StorageService.ComponentCoordinates(namespace, last, version);
    }
}
