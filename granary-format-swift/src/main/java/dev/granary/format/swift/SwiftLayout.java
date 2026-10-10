package dev.granary.format.swift;

import dev.granary.core.storage.Checksums;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Where a Swift repository keeps things in its blob store. Scope and name are lower-cased because package
 * identities are case-insensitive; versions are kept as published.
 *
 * <pre>
 * &lt;scope&gt;/&lt;name&gt;/&lt;version&gt;.zip                     source archive
 * &lt;scope&gt;/&lt;name&gt;/&lt;version&gt;/metadata.json           release metadata document (written last on publish)
 * &lt;scope&gt;/&lt;name&gt;/&lt;version&gt;/Package.swift           manifests extracted from the archive
 * &lt;scope&gt;/&lt;name&gt;/&lt;version&gt;/Package@swift-5.9.swift
 * &lt;scope&gt;/&lt;name&gt;/&lt;version&gt;/source-archive.sig      signatures as published (hosted)
 * &lt;scope&gt;/&lt;name&gt;/&lt;version&gt;/metadata.sig
 * &lt;scope&gt;/&lt;name&gt;/&lt;version&gt;/manifest-links.json     the upstream's manifest variants (proxy)
 * &lt;scope&gt;/&lt;name&gt;/releases.json                    the upstream's release list (proxy)
 * -/identifiers/&lt;sha256 of normalised URL&gt;.json    repository URL to package identifiers
 * </pre>
 * {@code -} cannot start a scope, so the identifier documents never collide with packages.
 */
public final class SwiftLayout {

    public static final String RELEASE_DOCUMENT = "metadata.json";
    public static final String RELEASES_DOCUMENT = "releases.json";
    public static final String MANIFEST_LINKS = "manifest-links.json";
    public static final String ARCHIVE_SIGNATURE = "source-archive.sig";
    public static final String METADATA_SIGNATURE = "metadata.sig";
    public static final String IDENTIFIERS_PREFIX = "-/identifiers/";

    private SwiftLayout() {
    }

    public static String packagePrefix(SwiftIdentity id) {
        return id.lowerScope() + "/" + id.lowerName() + "/";
    }

    public static String releasePrefix(SwiftIdentity id, String version) {
        return packagePrefix(id) + version + "/";
    }

    public static String archivePath(SwiftIdentity id, String version) {
        return packagePrefix(id) + version + SwiftPath.ZIP_SUFFIX;
    }

    public static String releaseDocumentPath(SwiftIdentity id, String version) {
        return releasePrefix(id, version) + RELEASE_DOCUMENT;
    }

    public static String manifestPath(SwiftIdentity id, String version, String filename) {
        return releasePrefix(id, version) + filename;
    }

    public static String manifestLinksPath(SwiftIdentity id, String version) {
        return releasePrefix(id, version) + MANIFEST_LINKS;
    }

    public static String signaturePath(SwiftIdentity id, String version) {
        return releasePrefix(id, version) + ARCHIVE_SIGNATURE;
    }

    public static String metadataSignaturePath(SwiftIdentity id, String version) {
        return releasePrefix(id, version) + METADATA_SIGNATURE;
    }

    public static String releasesPath(SwiftIdentity id) {
        return packagePrefix(id) + RELEASES_DOCUMENT;
    }

    /** The identifiers document of a normalised repository URL (see {@link RepositoryUrls}). */
    public static String identifiersPath(String normalisedUrl) {
        return IDENTIFIERS_PREFIX + Checksums.hex("SHA-256", normalisedUrl.getBytes(StandardCharsets.UTF_8)) + ".json";
    }

    /**
     * The version of a release metadata document path below {@code packagePrefix}
     * ({@code <scope>/<name>/<version>/metadata.json}); empty for every other path.
     */
    public static Optional<String> versionOfReleaseDocument(String packagePrefix, String path) {
        if (path == null || !path.startsWith(packagePrefix) || !path.endsWith("/" + RELEASE_DOCUMENT)) {
            return Optional.empty();
        }
        String version = path.substring(packagePrefix.length(), path.length() - RELEASE_DOCUMENT.length() - 1);
        return SwiftVersion.isValid(version) ? Optional.of(version) : Optional.empty();
    }

    /** Index documents that change over time (re-validated upstream for proxies). */
    public static boolean isMetadata(String path) {
        if (path == null) {
            return false;
        }
        return path.startsWith(IDENTIFIERS_PREFIX) || path.endsWith("/" + RELEASES_DOCUMENT)
                || path.endsWith("/" + RELEASE_DOCUMENT);
    }
}
