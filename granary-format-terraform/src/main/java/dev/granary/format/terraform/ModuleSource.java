package dev.granary.format.terraform;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The value of the {@code X-Terraform-Get} header a module registry answers with: a go-getter source
 * address. Granary can only cache sources that are plain HTTP(S) archive downloads; those are rewritten
 * so the client fetches the archive from Granary. Forced getters ({@code git::…}, {@code s3::…}), bare
 * repository shorthands ({@code github.com/org/repo}) and HTTP URLs whose archive type cannot be told are
 * passed through unchanged, so the client fetches them itself.
 *
 * <p>go-getter syntax handled here: a {@code //sub/dir} suffix after the host selects a directory inside
 * the archive ({@code //*} is the single top-level directory of GitHub tarballs) and the {@code archive}
 * query parameter forces the archive type ({@code tar.gz}, {@code tgz}, {@code zip}, …); both are kept
 * on the rewritten address, the subdirectory verbatim and the type as the file extension.
 */
public final class ModuleSource {

    /**
     * An HTTP(S) archive Granary can cache.
     *
     * @param url       the address to download, without go-getter decorations
     * @param subdir    the {@code //…} subdirectory selector without its leading slashes, or empty
     * @param extension the archive type as a file extension go-getter recognises ({@code tgz}, {@code zip}, …)
     */
    public record Archive(URI url, String subdir, String extension) {
    }

    private static final Map<String, String> ARCHIVE_TYPES = Map.ofEntries(
            Map.entry("tar.gz", "tgz"), Map.entry("tgz", "tgz"),
            Map.entry("zip", "zip"),
            Map.entry("tar.bz2", "tbz2"), Map.entry("tbz2", "tbz2"),
            Map.entry("tar.xz", "txz"), Map.entry("txz", "txz"),
            Map.entry("tar", "tar"));

    private static final List<String> EXTENSIONS = List.of(".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz", ".zip", ".tar");

    private ModuleSource() {
    }

    /** The cacheable archive behind a source address, or empty when the address must be passed through. */
    public static Optional<Archive> archive(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String source = raw.strip();
        int forced = source.indexOf("::");
        if (forced > 0 && forced < source.indexOf("://")) {
            String getter = source.substring(0, forced).toLowerCase(Locale.ROOT);
            if (!getter.equals("http") && !getter.equals("https")) {
                return Optional.empty();
            }
            source = source.substring(forced + 2);
        }
        String lower = source.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Optional.empty();
        }
        // go-getter: the first "//" after the scheme separates the URL from the subdirectory; a query string
        // that follows the subdirectory still belongs to the URL
        int offset = source.indexOf("://") + 3;
        int slashes = source.indexOf("//", offset);
        String url = source;
        String subdir = "";
        if (slashes >= 0) {
            subdir = source.substring(slashes + 2);
            url = source.substring(0, slashes);
            int q = subdir.indexOf('?');
            if (q >= 0) {
                url += subdir.substring(q);
                subdir = subdir.substring(0, q);
            }
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (uri.getHost() == null) {
            return Optional.empty();
        }
        String forcedType = null;
        List<String> keptQuery = new ArrayList<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                String key = decode(eq < 0 ? pair : pair.substring(0, eq));
                if (key.equals("archive")) {
                    forcedType = eq < 0 ? "" : decode(pair.substring(eq + 1)).toLowerCase(Locale.ROOT);
                } else if (!pair.isEmpty()) {
                    keptQuery.add(pair);
                }
            }
        }
        if (forcedType != null && (forcedType.equals("false") || forcedType.isEmpty())) {
            return Optional.empty(); // explicitly not an archive
        }
        String extension = forcedType != null ? ARCHIVE_TYPES.get(forcedType) : extensionOf(uri.getPath());
        if (extension == null) {
            return Optional.empty();
        }
        try {
            URI clean = new URI(uri.getScheme(), uri.getRawAuthority(), uri.getPath(),
                    keptQuery.isEmpty() ? null : String.join("&", keptQuery), null);
            if (!keptQuery.isEmpty()) {
                // keep the caller's encoding of the query untouched
                clean = URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath() + "?" + String.join("&", keptQuery));
            }
            return Optional.of(new Archive(clean, subdir, extension));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String extensionOf(String path) {
        if (path == null) {
            return null;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return ARCHIVE_TYPES.get(ext.substring(1));
            }
        }
        return null;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** The source address a client gets instead: Granary's file URL with the subdirectory selector re-attached. */
    public static String rewrite(Archive archive, String fileUrl) {
        return archive.subdir().isEmpty() ? fileUrl : fileUrl + "//" + archive.subdir();
    }
}
