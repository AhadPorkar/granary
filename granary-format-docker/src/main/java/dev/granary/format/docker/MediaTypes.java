package dev.granary.format.docker;

import dev.granary.core.storage.Checksums;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** The manifest media types of the distribution protocol and the {@code Accept} negotiation around them. */
public final class MediaTypes {

    public static final String DOCKER_MANIFEST = "application/vnd.docker.distribution.manifest.v2+json";
    public static final String DOCKER_MANIFEST_LIST = "application/vnd.docker.distribution.manifest.list.v2+json";
    public static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
    public static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";
    public static final String DOCKER_MANIFEST_V1 = "application/vnd.docker.distribution.manifest.v1+json";
    public static final String DOCKER_MANIFEST_V1_SIGNED = "application/vnd.docker.distribution.manifest.v1+prettyjws";
    public static final String OCTET_STREAM = "application/octet-stream";
    public static final String JSON = "application/json";

    public static final Set<String> INDEX_TYPES = Set.of(DOCKER_MANIFEST_LIST, OCI_INDEX);
    public static final Set<String> IMAGE_TYPES = Set.of(DOCKER_MANIFEST, OCI_MANIFEST);
    public static final Set<String> MANIFEST_TYPES = Set.of(DOCKER_MANIFEST, DOCKER_MANIFEST_LIST, OCI_MANIFEST, OCI_INDEX);

    /** What a modern client sends; used upstream when the client itself expressed no preference. */
    public static final String DEFAULT_ACCEPT = OCI_INDEX + ", " + DOCKER_MANIFEST_LIST + ", " + OCI_MANIFEST + ", " + DOCKER_MANIFEST;

    private MediaTypes() {
    }

    /** The media types of an {@code Accept} header in order, parameters stripped, wildcards left out. */
    public static List<String> accepted(String accept) {
        List<String> result = new ArrayList<>();
        if (accept == null) {
            return result;
        }
        for (String part : accept.split(",")) {
            String type = part.trim();
            int semi = type.indexOf(';');
            if (semi >= 0) {
                type = type.substring(0, semi).trim();
            }
            if (type.isEmpty() || type.contains("*")) {
                continue;
            }
            result.add(type.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    public static boolean acceptsIndex(String accept) {
        return accepted(accept).stream().anyMatch(INDEX_TYPES::contains);
    }

    public static boolean isManifestType(String mediaType) {
        return mediaType != null && MANIFEST_TYPES.contains(stripParameters(mediaType).toLowerCase(Locale.ROOT));
    }

    public static boolean isIndexType(String mediaType) {
        return mediaType != null && INDEX_TYPES.contains(stripParameters(mediaType).toLowerCase(Locale.ROOT));
    }

    /**
     * The {@code Accept} value to send upstream for a client's header: the client's own list when it names at
     * least one manifest type, otherwise the full modern list, because a registry asked with {@code *&#47;*}
     * may answer with a legacy single-platform manifest.
     */
    public static String upstreamAccept(String accept) {
        return accepted(accept).stream().anyMatch(MANIFEST_TYPES::contains) ? accept : DEFAULT_ACCEPT;
    }

    /**
     * Distinguishes cached copies of a tag by what the client accepted. A client that accepts an index (every
     * current one) gets the canonical copy; a client limited to single manifests gets a copy of its own,
     * keyed by a short hash of its list, because the upstream answer differs for multi-platform images.
     */
    public static String variantKey(String accept) {
        List<String> types = accepted(accept);
        if (types.isEmpty() || types.stream().anyMatch(INDEX_TYPES::contains)) {
            return "";
        }
        TreeSet<String> known = new TreeSet<>();
        for (String t : types) {
            if (MANIFEST_TYPES.contains(t)) {
                known.add(t);
            }
        }
        if (known.isEmpty()) {
            return "";
        }
        return "@" + Checksums.hex("SHA-256", String.join(",", known).getBytes(StandardCharsets.UTF_8)).substring(0, 8);
    }

    public static String stripParameters(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
    }
}
