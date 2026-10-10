package dev.granary.format.pypi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * One distribution file as a simple-index page describes it, in whichever representation it came in
 * (PEP 503 anchor attributes or PEP 691 JSON members), so a page can be parsed from one and rendered in
 * the other.
 *
 * @param filename       the distribution file name
 * @param url            the download URL (absolute once a page has been resolved), without the hash fragment
 * @param hashes         digest algorithm (lower case) to hex value; the HTML fragment and the JSON {@code hashes}
 * @param requiresPython the {@code Requires-Python} specifier, or {@code null}
 * @param yanked         {@code null} when not yanked, the empty string when yanked without a reason, otherwise the reason
 * @param coreMetadata   {@code null} when no PEP 658 metadata file is advertised, an empty map for "available, hash
 *                       unknown" and otherwise the digests of the metadata file
 * @param gpgSig         whether a {@code .asc} signature exists, or {@code null} when not stated
 * @param size           the file size in bytes, or {@code null}
 * @param uploadTime     the ISO 8601 upload time, or {@code null}
 * @param extra          other JSON members of the file object (PEP 740 {@code provenance}, …), passed through
 * @param attributes     other {@code data-*} attributes of the anchor, passed through
 */
public record PypiFile(String filename, String url, Map<String, String> hashes, String requiresPython, String yanked,
                       Map<String, String> coreMetadata, Boolean gpgSig, Long size, String uploadTime,
                       Map<String, JsonNode> extra, Map<String, String> attributes) {

    public static final String SHA256 = "sha256";

    public PypiFile {
        hashes = hashes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(hashes));
        coreMetadata = coreMetadata == null ? null : Map.copyOf(new LinkedHashMap<>(coreMetadata));
        extra = extra == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(extra));
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }

    /** A file with the given name, URL and SHA-256, nothing else known. */
    public static PypiFile of(String filename, String url, String sha256) {
        return new PypiFile(filename, url, sha256 == null ? Map.of() : Map.of(SHA256, sha256), null, null, null, null,
                null, null, Map.of(), Map.of());
    }

    public PypiFile withUrl(String newUrl) {
        return new PypiFile(filename, newUrl, hashes, requiresPython, yanked, coreMetadata, gpgSig, size, uploadTime, extra,
                attributes);
    }

    public PypiFile withRequiresPython(String value) {
        return new PypiFile(filename, url, hashes, value, yanked, coreMetadata, gpgSig, size, uploadTime, extra, attributes);
    }

    public PypiFile withCoreMetadata(Map<String, String> digests) {
        return new PypiFile(filename, url, hashes, requiresPython, yanked, digests, gpgSig, size, uploadTime, extra, attributes);
    }

    public PypiFile withSize(Long bytes) {
        return new PypiFile(filename, url, hashes, requiresPython, yanked, coreMetadata, gpgSig, bytes, uploadTime, extra, attributes);
    }

    public PypiFile withUploadTime(String time) {
        return new PypiFile(filename, url, hashes, requiresPython, yanked, coreMetadata, gpgSig, size, time, extra, attributes);
    }

    public PypiFile withYanked(String reason) {
        return new PypiFile(filename, url, hashes, requiresPython, reason, coreMetadata, gpgSig, size, uploadTime, extra, attributes);
    }

    public boolean isYanked() {
        return yanked != null;
    }

    public Optional<String> sha256() {
        return Optional.ofNullable(hashes.get(SHA256));
    }

    /** The version the file name encodes, when the name parses as a distribution. */
    public Optional<String> version() {
        return PypiNames.parseFilename(filename).map(PypiNames.Distribution::version);
    }

    /** The hash fragment for an HTML link: {@code sha256=…} when known, otherwise the first digest, otherwise empty. */
    public Optional<String> fragment() {
        if (hashes.containsKey(SHA256)) {
            return Optional.of(SHA256 + "=" + hashes.get(SHA256));
        }
        return hashes.entrySet().stream().findFirst().map(e -> e.getKey() + "=" + e.getValue());
    }
}
