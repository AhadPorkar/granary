package dev.granary.format.terraform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds, parses and merges the JSON documents of the provider registry, module registry and provider
 * network mirror protocols. Everything here is pure: hosted repositories feed it what they stored, proxies
 * what they cached, groups the documents of their members.
 */
final class TerraformDocuments {

    /** One {@code os}/{@code arch} pair a provider version is built for. */
    record Platform(String os, String arch) {
        String key() {
            return os + "_" + arch;
        }
    }

    /** One entry of a provider {@code versions} document. */
    record ProviderVersion(String version, List<String> protocols, List<Platform> platforms) {
        ProviderVersion {
            protocols = List.copyOf(protocols);
            platforms = List.copyOf(platforms);
        }
    }

    /** One archive of a network-mirror version document. */
    record MirrorArchive(String url, List<String> hashes) {
        MirrorArchive {
            hashes = List.copyOf(hashes);
        }
    }

    static final String SIGNING_KEYS = "signing_keys";
    static final String GPG_PUBLIC_KEYS = "gpg_public_keys";
    static final List<String> DOWNLOAD_URL_FIELDS = List.of("download_url", "shasums_url", "shasums_signature_url");

    private final ObjectMapper json;

    TerraformDocuments(ObjectMapper json) {
        this.json = json;
    }

    ObjectMapper mapper() {
        return json;
    }

    // --- service discovery -------------------------------------------------------------------

    ObjectNode discovery(String baseUrl) {
        ObjectNode doc = json.createObjectNode();
        doc.put("providers.v1", TerraformLayout.providersBase(baseUrl));
        doc.put("modules.v1", TerraformLayout.modulesBase(baseUrl));
        return doc;
    }

    // --- provider versions --------------------------------------------------------------------

    ObjectNode providerVersions(Collection<ProviderVersion> versions) {
        ObjectNode doc = json.createObjectNode();
        ArrayNode array = doc.putArray("versions");
        for (ProviderVersion v : versions) {
            ObjectNode entry = array.addObject();
            entry.put("version", v.version());
            ArrayNode protocols = entry.putArray("protocols");
            v.protocols().forEach(protocols::add);
            ArrayNode platforms = entry.putArray("platforms");
            for (Platform p : v.platforms()) {
                platforms.addObject().put("os", p.os()).put("arch", p.arch());
            }
        }
        return doc;
    }

    /** Reads a {@code versions} document leniently; entries without a parsable version are dropped. */
    List<ProviderVersion> parseProviderVersions(JsonNode doc) {
        List<ProviderVersion> result = new ArrayList<>();
        if (doc == null) {
            return result;
        }
        for (JsonNode entry : doc.path("versions")) {
            String version = text(entry, "version");
            if (version == null || !TerraformVersion.isValid(version)) {
                continue;
            }
            List<String> protocols = new ArrayList<>();
            for (JsonNode p : entry.path("protocols")) {
                if (p.isString() && !p.stringValue().isBlank()) {
                    protocols.add(p.stringValue());
                }
            }
            List<Platform> platforms = new ArrayList<>();
            for (JsonNode p : entry.path("platforms")) {
                String os = text(p, "os");
                String arch = text(p, "arch");
                if (os != null && arch != null) {
                    platforms.add(new Platform(os, arch));
                }
            }
            result.add(new ProviderVersion(version, protocols, platforms));
        }
        return result;
    }

    /**
     * Union of several members' version lists, members in precedence order: the first member that has a
     * version owns it, and its entry (protocols and platforms) is the one listed. Later members only add
     * versions nobody before them has, so every version's download documents, checksums and signature come
     * from one member and always belong together. Sorted by version.
     */
    List<ProviderVersion> mergeProviderVersions(List<List<ProviderVersion>> members) {
        Map<String, ProviderVersion> owned = new TreeMap<>(TerraformVersion.STRING_ORDER);
        for (List<ProviderVersion> member : members) {
            for (ProviderVersion v : member) {
                String key = TerraformPath.canonical(v.version());
                owned.putIfAbsent(key, new ProviderVersion(key, v.protocols(), v.platforms()));
            }
        }
        return new ArrayList<>(owned.values());
    }

    /** Sorted, de-duplicated versions, canonical form. */
    static List<String> sortVersions(Collection<String> versions) {
        Set<String> sorted = new java.util.TreeSet<>(TerraformVersion.STRING_ORDER);
        for (String v : versions) {
            if (v != null && !v.isBlank()) {
                sorted.add(TerraformVersion.tryParse(v).map(TerraformVersion::toString).orElse(v));
            }
        }
        return new ArrayList<>(sorted);
    }

    /** The newest release, or the newest pre-release when there is nothing else; empty for an empty list. */
    static Optional<String> latest(Collection<String> versions) {
        TerraformVersion bestRelease = null;
        TerraformVersion bestAny = null;
        for (String s : versions) {
            Optional<TerraformVersion> v = TerraformVersion.tryParse(s);
            if (v.isEmpty()) {
                continue;
            }
            if (bestAny == null || v.get().compareTo(bestAny) > 0) {
                bestAny = v.get();
            }
            if (!v.get().isPrerelease() && (bestRelease == null || v.get().compareTo(bestRelease) > 0)) {
                bestRelease = v.get();
            }
        }
        TerraformVersion best = bestRelease != null ? bestRelease : bestAny;
        return Optional.ofNullable(best).map(TerraformVersion::toString);
    }

    // --- provider download --------------------------------------------------------------------

    /**
     * The download document of a hosted provider build.
     *
     * @param signingKeys the stored {@code {"gpg_public_keys":[…]}} object, or {@code null} for none
     */
    ObjectNode providerDownload(String baseUrl, String ns, String type, String version, String os, String arch,
                                String filename, String shasum, List<String> protocols, JsonNode signingKeys) {
        ObjectNode doc = json.createObjectNode();
        ArrayNode protos = doc.putArray("protocols");
        protocols.forEach(protos::add);
        doc.put("os", os);
        doc.put("arch", arch);
        doc.put("filename", filename);
        doc.put("download_url", TerraformLayout.providerFileUrl(baseUrl, ns, type, version, filename));
        doc.put("shasums_url", TerraformLayout.providerFileUrl(baseUrl, ns, type, version, TerraformPath.SHASUMS));
        doc.put("shasums_signature_url", TerraformLayout.providerFileUrl(baseUrl, ns, type, version, TerraformPath.SHASUMS_SIG));
        doc.put("shasum", shasum);
        ObjectNode keys = doc.putObject(SIGNING_KEYS);
        if (signingKeys != null && signingKeys.path(GPG_PUBLIC_KEYS).isArray()) {
            keys.set(GPG_PUBLIC_KEYS, signingKeys.path(GPG_PUBLIC_KEYS).deepCopy());
        } else {
            keys.putArray(GPG_PUBLIC_KEYS);
        }
        return doc;
    }

    /**
     * A copy of an upstream download document whose archive, checksum and signature URLs point at this
     * repository; the file names are the last segment of the upstream URLs. {@code signing_keys} and every
     * other field pass through untouched, so clients verify exactly what upstream published.
     */
    ObjectNode rewriteProviderDownload(JsonNode upstream, String baseUrl, String ns, String type, String version) {
        ObjectNode doc = upstream.isObject() ? (ObjectNode) upstream.deepCopy() : json.createObjectNode();
        for (String field : DOWNLOAD_URL_FIELDS) {
            String original = text(doc, field);
            String filename = TerraformLayout.fileNameOf(original);
            if (filename != null && TerraformPath.filename(filename)) {
                doc.put(field, TerraformLayout.providerFileUrl(baseUrl, ns, type, version, filename));
            }
        }
        if (!doc.path("filename").isString() && doc.path("download_url").isString()) {
            String filename = TerraformLayout.fileNameOf(text(upstream, "download_url"));
            if (filename != null) {
                doc.put("filename", filename);
            }
        }
        return doc;
    }

    /** The upstream URL in a cached download document that ends with {@code filename}, if any. */
    static Optional<String> upstreamUrlFor(JsonNode downloadDocument, String filename) {
        for (String field : DOWNLOAD_URL_FIELDS) {
            String url = text(downloadDocument, field);
            if (url != null && filename.equals(TerraformLayout.fileNameOf(url))) {
                return Optional.of(url);
            }
        }
        return Optional.empty();
    }

    // --- provider info -------------------------------------------------------------------------

    ObjectNode providerInfo(String ns, String type, String version, List<String> versions, List<Platform> platforms) {
        ObjectNode doc = json.createObjectNode();
        doc.put("id", ns + "/" + type + "/" + version);
        doc.put("namespace", ns);
        doc.put("name", type);
        doc.put("version", version);
        ArrayNode all = doc.putArray("versions");
        versions.forEach(all::add);
        ArrayNode plats = doc.putArray("platforms");
        for (Platform p : platforms) {
            plats.addObject().put("os", p.os()).put("arch", p.arch());
        }
        return doc;
    }

    // --- modules -------------------------------------------------------------------------------

    ObjectNode moduleVersions(String ns, String name, String system, Collection<String> versions) {
        ObjectNode doc = json.createObjectNode();
        ObjectNode module = doc.putArray("modules").addObject();
        module.put("source", ns + "/" + name + "/" + system);
        ArrayNode array = module.putArray("versions");
        for (String v : versions) {
            array.addObject().put("version", v);
        }
        return doc;
    }

    /** The version strings of a module {@code versions} document (all listed modules, usually one). */
    List<String> parseModuleVersions(JsonNode doc) {
        List<String> result = new ArrayList<>();
        if (doc == null) {
            return result;
        }
        for (JsonNode module : doc.path("modules")) {
            for (JsonNode v : module.path("versions")) {
                String version = text(v, "version");
                if (version != null && TerraformVersion.isValid(version)) {
                    result.add(version);
                }
            }
        }
        return result;
    }

    ObjectNode moduleInfo(String ns, String name, String system, String version, List<String> versions) {
        ObjectNode doc = json.createObjectNode();
        doc.put("id", ns + "/" + name + "/" + system + "/" + version);
        doc.put("namespace", ns);
        doc.put("name", name);
        doc.put("provider", system);
        doc.put("version", version);
        doc.put("source", ns + "/" + name + "/" + system);
        ArrayNode all = doc.putArray("versions");
        versions.forEach(all::add);
        return doc;
    }

    // --- network mirror ------------------------------------------------------------------------

    ObjectNode mirrorIndex(Collection<String> versions) {
        ObjectNode doc = json.createObjectNode();
        ObjectNode map = doc.putObject("versions");
        for (String v : sortVersions(versions)) {
            map.putObject(v);
        }
        return doc;
    }

    ObjectNode mirrorVersion(Map<String, MirrorArchive> archives) {
        ObjectNode doc = json.createObjectNode();
        ObjectNode map = doc.putObject("archives");
        for (Map.Entry<String, MirrorArchive> e : new TreeMap<>(archives).entrySet()) {
            ObjectNode archive = map.putObject(e.getKey());
            archive.put("url", e.getValue().url());
            if (!e.getValue().hashes().isEmpty()) {
                ArrayNode hashes = archive.putArray("hashes");
                e.getValue().hashes().forEach(hashes::add);
            }
        }
        return doc;
    }

    /** Terraform's {@code zh:} hash scheme: the hex SHA-256 of the provider zip. */
    static String zipHash(String sha256) {
        return "zh:" + sha256;
    }

    // --- signing keys ----------------------------------------------------------------------------

    /**
     * Normalises an uploaded signing-key document to {@code {"gpg_public_keys":[{key_id, ascii_armor, …}]}}.
     * Accepts one key object or the already-normalised form; rejects anything without a key id and an
     * ASCII-armored public key block.
     */
    Optional<ObjectNode> normaliseSigningKeys(JsonNode uploaded) {
        if (uploaded == null || !uploaded.isObject()) {
            return Optional.empty();
        }
        List<JsonNode> keys = new ArrayList<>();
        if (uploaded.path(GPG_PUBLIC_KEYS).isArray()) {
            uploaded.path(GPG_PUBLIC_KEYS).forEach(keys::add);
        } else {
            keys.add(uploaded);
        }
        ObjectNode doc = json.createObjectNode();
        ArrayNode array = doc.putArray(GPG_PUBLIC_KEYS);
        for (JsonNode key : keys) {
            String id = text(key, "key_id");
            String armor = text(key, "ascii_armor");
            if (id == null || id.isBlank() || armor == null || !armor.contains("BEGIN PGP PUBLIC KEY BLOCK")) {
                return Optional.empty();
            }
            ObjectNode normalised = array.addObject();
            normalised.put("key_id", id.strip());
            normalised.put("ascii_armor", armor);
            normalised.put("trust_signature", key.path("trust_signature").isString() ? key.path("trust_signature").stringValue() : "");
            normalised.put("source", key.path("source").isString() ? key.path("source").stringValue() : "");
            normalised.put("source_url", key.path("source_url").isString() ? key.path("source_url").stringValue() : "");
        }
        return array.isEmpty() ? Optional.empty() : Optional.of(doc);
    }

    // --- helpers -------------------------------------------------------------------------------

    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.path(field);
        return v.isString() ? v.stringValue() : null;
    }

    static List<String> parseProtocols(String setting) {
        Set<String> result = new LinkedHashSet<>();
        for (String p : setting.split(",")) {
            if (!p.isBlank()) {
                result.add(p.strip());
            }
        }
        return new ArrayList<>(result);
    }
}
