package dev.granary.format.composer;

import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.storage.Checksums;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where a version's dist archive lives inside a Granary repository and how its URL is written for a client.
 *
 * <p>Every archive is addressed as {@code dist/{vendor}/{name}/{version}/{file}}. For an uploaded archive the
 * file is {@code {sha1}.zip}; for a proxied one it is {@code {reference}.{extension}}, the reference being the
 * commit the upstream names (a digest of the original URL when there is none) and the extension the one the
 * original URL ends with, else the dist type's. Composer names its temporary download after that extension, so
 * keeping it keeps tar, phar and gzip dists extractable.
 */
public final class ComposerDist {

    /** Dist types Composer extracts as archives; others ({@code file}, {@code path}) keep their original URL. */
    private static final Set<String> ARCHIVE_TYPES = Set.of("zip", "tar", "gzip", "xz", "rar", "phar");
    private static final List<String> KNOWN_EXTENSIONS = List.of(
            "tar.gz", "tar.bz2", "tar.xz", "zip", "tar", "tgz", "tbz2", "txz", "phar", "rar", "7z", "gz", "bz2", "xz");
    private static final Pattern SAFE_REFERENCE = Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$");
    private static final int MAX_VERSION_LENGTH = 255;

    private ComposerDist() {
    }

    /**
     * The file name a proxied version's archive is cached and served under, or empty when the version has no dist,
     * an unusable one, or one that is not an archive.
     */
    public static Optional<String> fileName(ObjectNode entry) {
        JsonNode dist = entry.get(P2Document.DIST);
        if (!(dist instanceof ObjectNode d)) {
            return Optional.empty();
        }
        String url = P2Document.string(d, "url");
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String type = P2Document.string(d, "type");
        String normalisedType = type == null ? "zip" : type.toLowerCase(Locale.ROOT);
        if (!ARCHIVE_TYPES.contains(normalisedType)) {
            return Optional.empty();
        }
        String reference = P2Document.string(d, "reference");
        String ref = reference != null && SAFE_REFERENCE.matcher(reference).matches()
                ? reference
                : Checksums.hex("SHA-1", url.getBytes(StandardCharsets.UTF_8));
        return Optional.of(ref + "." + extension(url, normalisedType));
    }

    /** The archive extension of the original URL's last path segment, else the one that goes with the dist type. */
    static String extension(String url, String type) {
        String path;
        try {
            path = URI.create(url.trim()).getPath();
        } catch (IllegalArgumentException e) {
            path = null;
        }
        if (path != null) {
            String last = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            for (String ext : KNOWN_EXTENSIONS) {
                if (last.length() > ext.length() + 1 && last.endsWith("." + ext)) {
                    return ext;
                }
            }
        }
        return switch (type) {
            case "gzip" -> "gz";
            case "tar", "xz", "rar", "phar" -> type;
            default -> "zip";
        };
    }

    /**
     * Whether {@code version} can become path segments that survive a round trip through a URL unchanged. A
     * semicolon is refused as well: it starts a path parameter, so the server's HTTP firewall rejects such a URL
     * whether the semicolon is literal or percent-encoded, and the archive could never be downloaded.
     */
    public static boolean isPathSafe(String version) {
        if (version == null || version.isEmpty() || version.length() > MAX_VERSION_LENGTH) {
            return false;
        }
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\' || c == '?' || c == '#' || c == '%' || c == ';') {
                return false;
            }
        }
        for (String segment : version.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** {@code <base>/dist/<vendor>/<name>/<version>/<file>}, each segment percent-encoded, slashes in the version kept. */
    public static String url(String baseUrl, String name, String version, String file) {
        return UrlPaths.join(baseUrl, ComposerPath.distPath(name, version, file));
    }

    /** The absolute URL of a repository-relative path, encoded segment by segment. */
    public static String absolute(String baseUrl, String relativePath) {
        return UrlPaths.join(baseUrl, relativePath);
    }

    /** Whether a dist URL is absolute ({@code http(s)://…}) rather than a path relative to the repository. */
    public static boolean isAbsolute(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.contains("://");
    }
}
