package dev.granary.format.docker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What the registry needs to know about a manifest: its media type, the blobs and manifests it references
 * (so a push can be refused until they exist) and its OCI 1.1 {@code subject} and {@code artifactType}
 * (so the referrers API can find it). Everything else in the document passes through untouched; the bytes
 * are stored verbatim because the digest covers them.
 *
 * @param mediaType     the manifest's media type, from the document when it says, else from the request
 * @param config        the config descriptor of an image manifest, or {@code null}
 * @param layers        the layers of an image manifest (empty for an index)
 * @param manifests     the entries of an index (empty for an image manifest)
 * @param subject       the OCI 1.1 subject this manifest refers to, or {@code null}
 * @param artifactType  the OCI 1.1 artifact type, or {@code null}
 * @param annotations   the manifest's annotations
 * @param index         whether this is a multi-platform index rather than a single image manifest
 */
public record Manifest(String mediaType, Descriptor config, List<Descriptor> layers, List<Descriptor> manifests,
                       Descriptor subject, String artifactType, Map<String, String> annotations, boolean index) {

    /** The largest manifest accepted on push; real manifests are a few kilobytes. */
    public static final int MAX_SIZE = 4 * 1024 * 1024;

    /** A content descriptor: what a manifest says about something it references. */
    public record Descriptor(String mediaType, Digest digest, long size, String artifactType,
                             Map<String, String> annotations, List<String> urls) {

        /** Foreign layers carry {@code urls} and are fetched from elsewhere; a registry does not store them. */
        public boolean isForeign() {
            return !urls.isEmpty();
        }
    }

    public Manifest {
        layers = layers == null ? List.of() : List.copyOf(layers);
        manifests = manifests == null ? List.of() : List.copyOf(manifests);
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
    }

    /**
     * Parses a manifest; throws a {@link DockerError} of code {@code MANIFEST_INVALID} for anything a registry
     * must refuse (malformed JSON, schema 1, bad digests).
     */
    public static Manifest parse(ObjectMapper mapper, byte[] body, String contentType) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JacksonException e) {
            throw DockerError.manifestInvalid("manifest is not valid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw DockerError.manifestInvalid("manifest must be a JSON object");
        }
        JsonNode schema = root.get("schemaVersion");
        if (root.has("fsLayers") || (schema != null && !(schema.isNumber() && schema.intValue() == 2))) {
            throw DockerError.manifestInvalid("only schema version 2 manifests are supported");
        }
        boolean index = root.path("manifests").isArray();
        String mediaType = string(root, "mediaType");
        if (mediaType == null) {
            String header = MediaTypes.stripParameters(contentType);
            mediaType = MediaTypes.isManifestType(header) ? header : (index ? MediaTypes.OCI_INDEX : MediaTypes.OCI_MANIFEST);
        }
        Descriptor config = root.path("config").isObject() ? descriptor(root.get("config"), "config") : null;
        List<Descriptor> layers = descriptors(root.get("layers"), "layers");
        List<Descriptor> manifests = descriptors(root.get("manifests"), "manifests");
        Descriptor subject = root.path("subject").isObject() ? descriptor(root.get("subject"), "subject") : null;
        return new Manifest(mediaType, config, layers, manifests, subject, string(root, "artifactType"),
                stringMap(root.get("annotations")), index);
    }

    /** Every blob the registry must hold before this manifest may be accepted. */
    public List<Digest> referencedBlobs() {
        List<Digest> result = new ArrayList<>();
        if (config != null) {
            result.add(config.digest());
        }
        for (Descriptor layer : layers) {
            if (!layer.isForeign()) {
                result.add(layer.digest());
            }
        }
        return result;
    }

    /** Every manifest an index requires to exist. */
    public List<Digest> referencedManifests() {
        return manifests.stream().map(Descriptor::digest).toList();
    }

    public Optional<Digest> subjectDigest() {
        return Optional.ofNullable(subject).map(Descriptor::digest);
    }

    /** The {@code mediaType} member of a manifest document, if it has one; cheap enough to run per request. */
    public static Optional<String> declaredMediaType(ObjectMapper mapper, byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);
            return Optional.ofNullable(root == null ? null : string(root, "mediaType"));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private static List<Descriptor> descriptors(JsonNode array, String field) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<Descriptor> result = new ArrayList<>();
        int i = 0;
        for (JsonNode node : array) {
            result.add(descriptor(node, field + "[" + i++ + "]"));
        }
        return result;
    }

    private static Descriptor descriptor(JsonNode node, String where) {
        if (node == null || !node.isObject()) {
            throw DockerError.manifestInvalid(where + " must be a descriptor object");
        }
        String digestText = string(node, "digest");
        Digest digest = Digest.parse(digestText).orElseThrow(() ->
                DockerError.manifestInvalid(where + " has an invalid digest '" + digestText + "'"));
        JsonNode sizeNode = node.get("size");
        long size = sizeNode != null && sizeNode.isNumber() ? sizeNode.longValue() : -1;
        List<String> urls = new ArrayList<>();
        JsonNode urlsNode = node.get("urls");
        if (urlsNode != null && urlsNode.isArray()) {
            for (JsonNode u : urlsNode) {
                if (u.isString()) {
                    urls.add(u.stringValue());
                }
            }
        }
        return new Descriptor(string(node, "mediaType"), digest, size, string(node, "artifactType"),
                stringMap(node.get("annotations")), List.copyOf(urls));
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if (e.getValue().isString()) {
                    map.put(e.getKey(), e.getValue().stringValue());
                }
            }
        }
        return map;
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
