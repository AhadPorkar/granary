package dev.granary.format.swift;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package manifests inside a release: the default {@code Package.swift} and version-specific variants named
 * {@code Package@swift-<major>[.<minor>[.<patch>]].swift}, the {@code // swift-tools-version:} line each one
 * starts with, and the {@code Link} header that advertises the variants to SwiftPM.
 */
public final class SwiftManifests {

    public static final String MANIFEST = "Package.swift";
    public static final String CONTENT_TYPE = "text/x-swift";

    private static final Pattern ALTERNATE = Pattern.compile("Package@swift-(\\d{1,9})(?:\\.(\\d{1,9}))?(?:\\.(\\d{1,9}))?\\.swift");
    private static final Pattern TOOLS_VERSION = Pattern.compile(
            "//\\s*swift-tools-version\\s*:\\s*(\\d{1,9})\\.(\\d{1,9})(?:\\.(\\d{1,9}))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SWIFT_VERSION = Pattern.compile("(\\d{1,9})(?:\\.(\\d{1,9}))?(?:\\.(\\d{1,9}))?");

    /**
     * One version-specific manifest.
     *
     * @param filename     e.g. {@code Package@swift-5.9.swift}
     * @param toolsVersion the tools version the file declares, e.g. {@code 5.9}
     * @param url          where it can be fetched; set for manifests learned from an upstream registry, otherwise {@code null}
     */
    public record Alternate(String filename, String toolsVersion, String url) {

        /** The Swift version in the file name, e.g. {@code 5.9} for {@code Package@swift-5.9.swift}. */
        public String swiftVersion() {
            return swiftVersionOf(filename).orElseThrow();
        }
    }

    private SwiftManifests() {
    }

    public static boolean isAlternateFileName(String filename) {
        return filename != null && ALTERNATE.matcher(filename).matches();
    }

    public static boolean isManifestFileName(String filename) {
        return MANIFEST.equals(filename) || isAlternateFileName(filename);
    }

    /** The Swift version part of a version-specific manifest name. */
    public static Optional<String> swiftVersionOf(String filename) {
        if (filename == null) {
            return Optional.empty();
        }
        Matcher m = ALTERNATE.matcher(filename);
        return m.matches() ? Optional.of(filename.substring("Package@swift-".length(), filename.length() - ".swift".length()))
                : Optional.empty();
    }

    /**
     * The tools version a manifest declares on its first line ({@code // swift-tools-version:5.9}, spacing and
     * case as SwiftPM tolerates them, a byte order mark and leading blank lines allowed), as {@code major.minor}
     * or {@code major.minor.patch} without leading zeros. Empty when the line is missing or malformed.
     */
    public static Optional<String> toolsVersion(String manifest) {
        if (manifest == null) {
            return Optional.empty();
        }
        int i = 0;
        if (manifest.startsWith("\uFEFF")) {
            i = 1;
        }
        while (i < manifest.length() && Character.isWhitespace(manifest.charAt(i))) {
            i++;
        }
        Matcher m = TOOLS_VERSION.matcher(manifest);
        m.region(i, manifest.length());
        if (!m.lookingAt()) {
            return Optional.empty();
        }
        String version = Long.parseLong(m.group(1)) + "." + Long.parseLong(m.group(2));
        return Optional.of(m.group(3) == null ? version : version + "." + Long.parseLong(m.group(3)));
    }

    /** {@code 5} -> {@code 5.0.0}, {@code 5.9} -> {@code 5.9.0}; empty for anything that is not 1 to 3 numbers. */
    public static Optional<String> normalise(String version) {
        if (version == null) {
            return Optional.empty();
        }
        Matcher m = SWIFT_VERSION.matcher(version.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(m.group(1)) + "." + (m.group(2) == null ? 0 : Long.parseLong(m.group(2))) + "."
                + (m.group(3) == null ? 0 : Long.parseLong(m.group(3))));
    }

    /**
     * The variant a {@code ?swift-version=} request means: the file named exactly so, else the one whose file-name
     * version is the same number ({@code 5.9.0} finds {@code Package@swift-5.9.swift}; SwiftPM asks with the
     * three-part form), else the one that declares that tools version. Empty when none matches, which the
     * protocol answers with a redirect to the default manifest.
     */
    public static Optional<Alternate> select(Collection<Alternate> alternates, String swiftVersion) {
        if (swiftVersion == null || swiftVersion.isBlank()) {
            return Optional.empty();
        }
        String requested = swiftVersion.trim();
        String exact = "Package@swift-" + requested + ".swift";
        for (Alternate a : alternates) {
            if (a.filename().equals(exact)) {
                return Optional.of(a);
            }
        }
        Optional<String> wanted = normalise(requested);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        for (Alternate a : alternates) {
            if (swiftVersionOf(a.filename()).flatMap(SwiftManifests::normalise).equals(wanted)) {
                return Optional.of(a);
            }
        }
        for (Alternate a : alternates) {
            if (normalise(a.toolsVersion()).equals(wanted)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /**
     * The {@code Link} header value advertising the variants on {@code manifestUrl} (the URL of the default
     * {@code Package.swift}), in the exact shape SwiftPM parses: four {@code ;}-separated fields per entry.
     */
    public static Optional<String> linkHeader(String manifestUrl, List<Alternate> alternates) {
        List<String> entries = new ArrayList<>();
        for (Alternate a : alternates) {
            if (!isAlternateFileName(a.filename()) || a.toolsVersion() == null || !safeAttribute(a.toolsVersion())) {
                continue;
            }
            entries.add("<" + manifestUrl + "?swift-version=" + a.swiftVersion() + ">; rel=\"alternate\"; filename=\""
                    + a.filename() + "\"; swift-tools-version=\"" + a.toolsVersion() + "\"");
        }
        return entries.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", entries));
    }

    /**
     * The variants an upstream registry advertises in its {@code Link} header on {@code Package.swift}. Entries
     * that are not {@code rel="alternate"} or whose file name is not a manifest variant are skipped.
     */
    public static List<Alternate> parseLinkHeader(String header) {
        List<Alternate> result = new ArrayList<>();
        if (header == null || header.isBlank()) {
            return result;
        }
        for (String entry : splitEntries(header)) {
            String trimmed = entry.trim();
            int close = trimmed.indexOf('>');
            if (!trimmed.startsWith("<") || close < 0) {
                continue;
            }
            String url = trimmed.substring(1, close).trim();
            String rel = null;
            String filename = null;
            String tools = null;
            for (String field : trimmed.substring(close + 1).split(";")) {
                int eq = field.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = field.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
                String value = field.substring(eq + 1).trim().replace("\"", "");
                switch (key) {
                    case "rel" -> rel = value;
                    case "filename" -> filename = value;
                    case "swift-tools-version" -> tools = value;
                    default -> {
                        // other link parameters are irrelevant here
                    }
                }
            }
            if ("alternate".equalsIgnoreCase(rel) && isAlternateFileName(filename) && tools != null && safeAttribute(tools)) {
                result.add(new Alternate(filename, tools, url));
            }
        }
        return result;
    }

    /** Splits a Link header at the commas between entries, not at commas inside {@code <...>}. */
    private static List<String> splitEntries(String header) {
        List<String> entries = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                entries.add(header.substring(start, i));
                start = i + 1;
            }
        }
        entries.add(header.substring(start));
        return entries;
    }

    /** Attribute values go into a quoted header field SwiftPM splits on {@code ;}, {@code ,} and {@code =}. */
    private static boolean safeAttribute(String value) {
        return SWIFT_VERSION.matcher(value).matches();
    }
}
