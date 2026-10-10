package dev.granary.format.cargo;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Cargo's {@code dl} rule: a template with the markers {@code {crate}}, {@code {version}}, {@code {prefix}},
 * {@code {lowerprefix}} and {@code {sha256-checksum}}; a value without any marker gets
 * {@code /{crate}/{version}/download} appended.
 */
final class DownloadTemplate {

    static final List<String> MARKERS = List.of("{crate}", "{version}", "{prefix}", "{lowerprefix}", "{sha256-checksum}");

    private DownloadTemplate() {
    }

    static boolean hasMarkers(String template) {
        return MARKERS.stream().anyMatch(template::contains);
    }

    static String expand(String template, String crate, String version, String cksum) {
        String t = template.trim();
        if (!hasMarkers(t)) {
            while (t.endsWith("/")) {
                t = t.substring(0, t.length() - 1);
            }
            return t + "/" + crate + "/" + version + "/download";
        }
        String prefix = CrateNames.prefixOf(crate);
        return t.replace("{crate}", crate)
                .replace("{version}", version)
                .replace("{prefix}", prefix)
                .replace("{lowerprefix}", prefix.toLowerCase(Locale.ROOT))
                .replace("{sha256-checksum}", cksum == null ? "" : cksum);
    }

    /** Why a template cannot be used, or {@code null}: it must expand to an absolute http(s) URL. */
    static String problem(String template) {
        String sample = expand(template, "sample-crate", "1.0.0", "0".repeat(64));
        URI uri;
        try {
            uri = URI.create(sample);
        } catch (IllegalArgumentException e) {
            return "does not expand to a valid URL";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            return "must expand to an absolute http(s) URL";
        }
        return null;
    }
}
