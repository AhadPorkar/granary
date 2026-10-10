package dev.granary.format.composer;

import java.util.Arrays;
import java.util.Locale;

/**
 * The URL layout of a Composer repository, relative to the repository root.
 *
 * <pre>
 *   packages.json                                     root document (metadata-url, search, notify-batch, …)
 *   p2/{vendor}/{name}.json, p2/{vendor}/{name}~dev.json   package metadata, tagged releases and branches
 *   dist/{vendor}/{name}/{version}/{reference}.{ext}  dist archives (the version may contain slashes)
 *   search.json?q=…&amp;type=…                            full-text search
 *   list.json?filter=…&amp;vendor=…                        package names
 *   providers/{vendor}/{name}.json                    packages providing a virtual package
 *   api/security-advisories                           security advisories (POST or GET, packages[]=…)
 *   downloads                                         install notifications (notify-batch)
 *   packages                                          upload: POST multipart or PUT of a zip
 *   packages/{vendor}/{name}[/{version}[.zip]]        upload (PUT/POST) and delete
 *   packages/upload/{vendor}/{name}/{version}         upload, the URL Nexus Repository uses
 * </pre>
 */
public record ComposerPath(Kind kind, String name, String version, String file, boolean dev) {

    public enum Kind {
        ROOT, PACKAGES_JSON, P2, DIST, SEARCH, LIST, PROVIDERS, ADVISORIES, DOWNLOADS,
        PACKAGES, PACKAGE, PACKAGE_VERSION, INVALID, UNKNOWN
    }

    public static final String PACKAGES_JSON = "packages.json";
    public static final String P2 = "p2/";
    public static final String DIST = "dist/";
    public static final String DEV_SUFFIX = "~dev";

    private static final ComposerPath ROOT_PATH = new ComposerPath(Kind.ROOT, null, null, null, false);
    private static final ComposerPath INVALID_PATH = new ComposerPath(Kind.INVALID, null, null, null, false);
    private static final ComposerPath UNKNOWN_PATH = new ComposerPath(Kind.UNKNOWN, null, null, null, false);

    public static ComposerPath parse(String raw) {
        String path = raw == null ? "" : raw;
        if (path.isEmpty() || path.equals("/")) {
            return ROOT_PATH;
        }
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            return INVALID_PATH;
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        switch (trimmed) {
            case PACKAGES_JSON -> {
                return simple(Kind.PACKAGES_JSON);
            }
            case "search.json" -> {
                return simple(Kind.SEARCH);
            }
            case "list.json" -> {
                return simple(Kind.LIST);
            }
            case "downloads" -> {
                return simple(Kind.DOWNLOADS);
            }
            case "api/security-advisories" -> {
                return simple(Kind.ADVISORIES);
            }
            case "packages" -> {
                return simple(Kind.PACKAGES);
            }
            default -> {
                // fall through to the structured paths below
            }
        }
        String[] segments = trimmed.split("/", -1);
        if (Arrays.stream(segments).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals(".."))) {
            return INVALID_PATH;
        }
        return switch (segments[0]) {
            case "p2" -> metadata(segments);
            case "providers" -> providers(segments);
            case "dist" -> dist(segments);
            case "packages" -> packages(segments);
            default -> UNKNOWN_PATH;
        };
    }

    private static ComposerPath simple(Kind kind) {
        return new ComposerPath(kind, null, null, null, false);
    }

    private static ComposerPath metadata(String[] segments) {
        if (segments.length != 3 || !segments[2].endsWith(".json")) {
            return UNKNOWN_PATH;
        }
        String file = segments[2].substring(0, segments[2].length() - ".json".length());
        boolean dev = file.endsWith(DEV_SUFFIX);
        String project = dev ? file.substring(0, file.length() - DEV_SUFFIX.length()) : file;
        String name = segments[1] + "/" + project;
        if (!ComposerNames.isValid(name)) {
            return INVALID_PATH;
        }
        return new ComposerPath(Kind.P2, name.toLowerCase(Locale.ROOT), null, null, dev);
    }

    private static ComposerPath providers(String[] segments) {
        if (segments.length != 3 || !segments[2].endsWith(".json")) {
            return UNKNOWN_PATH;
        }
        String name = segments[1] + "/" + segments[2].substring(0, segments[2].length() - ".json".length());
        if (!ComposerNames.isValid(name)) {
            return INVALID_PATH;
        }
        return new ComposerPath(Kind.PROVIDERS, name.toLowerCase(Locale.ROOT), null, null, false);
    }

    private static ComposerPath dist(String[] segments) {
        if (segments.length < 5) {
            return UNKNOWN_PATH;
        }
        String name = segments[1] + "/" + segments[2];
        if (!ComposerNames.isValid(name)) {
            return INVALID_PATH;
        }
        String version = String.join("/", Arrays.copyOfRange(segments, 3, segments.length - 1));
        String file = segments[segments.length - 1];
        return new ComposerPath(Kind.DIST, name.toLowerCase(Locale.ROOT), version, file, ComposerVersions.isDev(version));
    }

    private static ComposerPath packages(String[] segments) {
        int offset = 1;
        if (segments.length >= 5 && segments[1].equals("upload")) {
            offset = 2; // the Nexus Repository upload URL: packages/upload/{vendor}/{name}/{version}
        }
        int remaining = segments.length - offset;
        if (remaining < 2) {
            return UNKNOWN_PATH;
        }
        String name = segments[offset] + "/" + segments[offset + 1];
        if (!ComposerNames.isValid(name)) {
            return INVALID_PATH;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (remaining == 2) {
            return new ComposerPath(Kind.PACKAGE, lower, null, null, false);
        }
        String version = String.join("/", Arrays.copyOfRange(segments, offset + 2, segments.length));
        if (version.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            version = version.substring(0, version.length() - ".zip".length());
        }
        if (version.isEmpty()) {
            return INVALID_PATH;
        }
        return new ComposerPath(Kind.PACKAGE_VERSION, lower, version, null, ComposerVersions.isDev(version));
    }

    /** Index documents change; dist archives and uploads do not. */
    public boolean isMetadata() {
        return switch (kind) {
            case ROOT, PACKAGES_JSON, P2, SEARCH, LIST, PROVIDERS, ADVISORIES -> true;
            default -> false;
        };
    }

    /** {@code p2/{vendor}/{name}.json} or the {@code ~dev} variant. */
    public static String metadataPath(String name, boolean dev) {
        return P2 + name + (dev ? DEV_SUFFIX : "") + ".json";
    }

    /** The storage path of a dist archive, the same path the client downloads it from. */
    public static String distPath(String name, String version, String file) {
        return DIST + name + "/" + version + "/" + file;
    }

    /** The p2 file name Composer asks for, {@code vendor/name} or {@code vendor/name~dev}. */
    public String metadataFileName() {
        return name + (dev ? DEV_SUFFIX : "");
    }
}
