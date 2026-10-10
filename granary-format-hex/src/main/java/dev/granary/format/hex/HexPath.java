package dev.granary.format.hex;

import java.util.regex.Pattern;

/**
 * Classifies a repository-relative path into the Hex repository API ({@code names}, {@code versions},
 * {@code packages/<name>}, {@code tarballs/<name>-<version>.tar}, {@code public_key}, {@code installs/…},
 * {@code docs/…}) and the Hex HTTP API under {@code api/}. Anything odd is {@link Kind#INVALID}, so a
 * handler answers 400 instead of letting a strange path reach storage.
 *
 * @param kind    what the path addresses
 * @param name    the package name for package-scoped kinds, otherwise {@code null}
 * @param version the release version for version-scoped kinds, otherwise {@code null}
 * @param rest    the remainder for {@code installs/…} and {@code docs/…}, otherwise {@code null}
 */
public record HexPath(Kind kind, String name, String version, String rest) {

    public enum Kind {
        ROOT,
        NAMES,
        VERSIONS,
        PACKAGE,
        TARBALL,
        PUBLIC_KEY,
        INSTALLS,
        DOCS,
        API_ROOT,
        API_PUBLISH,
        API_PACKAGES,
        API_PACKAGE,
        API_RELEASES,
        API_RELEASE,
        API_RETIRE,
        API_RELEASE_DOCS,
        API_USERS_ME,
        API_AUTH,
        API_KEYS,
        API_KEY,
        /** Under {@code api/} but nothing Granary implements: answered in the API's error shape. */
        API_UNKNOWN,
        UNKNOWN,
        INVALID
    }

    /**
     * Hex package names as hex.pm validates them ({@code ~r"^[a-z]\w*$"}): a lower-case letter, then ASCII letters
     * of either case, digits and underscores. Nearly every name is lower-case, but a proxy must not refuse the few
     * that are not.
     */
    public static final Pattern PACKAGE_NAME = Pattern.compile("^[a-z][A-Za-z0-9_]*$");
    static final int MAX_NAME_LENGTH = 100;
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._+-]*$");
    private static final Pattern KEY_NAME = Pattern.compile("^[^/\\\\\\p{Cntrl}]{1,200}$");

    public static HexPath parse(String path) {
        String p = path == null ? "" : path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            return new HexPath(Kind.ROOT, null, null, null);
        }
        if (p.contains("\\") || p.contains("//") || p.contains("/../") || p.startsWith("../") || p.endsWith("/..")
                || p.equals("..") || hasControlCharacters(p) || p.startsWith(".hex/") || p.equals(".hex")) {
            return invalid();
        }
        if (p.startsWith("api/") || p.equals("api")) {
            return parseApi(p.equals("api") ? "" : p.substring(4));
        }
        String[] segments = p.split("/", -1);
        return switch (segments[0]) {
            case "names" -> segments.length == 1 ? new HexPath(Kind.NAMES, null, null, null) : unknown();
            case "versions" -> segments.length == 1 ? new HexPath(Kind.VERSIONS, null, null, null) : unknown();
            case "public_key" -> segments.length == 1 ? new HexPath(Kind.PUBLIC_KEY, null, null, null) : unknown();
            case "packages" -> segments.length == 2 ? packageDocument(segments[1]) : unknown();
            case "tarballs" -> segments.length == 2 ? tarball(segments[1]) : unknown();
            case "docs" -> segments.length == 2 ? docs(segments[1]) : unknown();
            case "installs" -> segments.length >= 2 ? installs(p.substring("installs/".length())) : unknown();
            default -> unknown();
        };
    }

    private static HexPath parseApi(String rest) {
        if (rest.isEmpty()) {
            return new HexPath(Kind.API_ROOT, null, null, null);
        }
        String[] s = rest.split("/", -1);
        int i = 0;
        // hex.pm scopes organisation repositories as api/repos/<repo>/…; the repository is the URL's already
        if (s.length >= 3 && s[0].equals("repos")) {
            if (!isValidName(s[1])) {
                return invalid();
            }
            i = 2;
        }
        int n = s.length - i;
        if (n == 0) {
            return new HexPath(Kind.API_ROOT, null, null, null);
        }
        switch (s[i]) {
            case "publish" -> {
                return n == 1 ? new HexPath(Kind.API_PUBLISH, null, null, null) : apiUnknown();
            }
            case "users" -> {
                return n == 2 && s[i + 1].equals("me") ? new HexPath(Kind.API_USERS_ME, null, null, null) : apiUnknown();
            }
            case "auth" -> {
                return n == 1 ? new HexPath(Kind.API_AUTH, null, null, null) : apiUnknown();
            }
            case "keys" -> {
                if (n == 1) {
                    return new HexPath(Kind.API_KEYS, null, null, null);
                }
                if (n == 2) {
                    return KEY_NAME.matcher(s[i + 1]).matches() ? new HexPath(Kind.API_KEY, s[i + 1], null, null) : invalid();
                }
                return apiUnknown();
            }
            case "packages" -> {
                if (n == 1) {
                    return new HexPath(Kind.API_PACKAGES, null, null, null);
                }
                String name = s[i + 1];
                if (!isValidName(name)) {
                    return invalid();
                }
                if (n == 2) {
                    return new HexPath(Kind.API_PACKAGE, name, null, null);
                }
                if (!s[i + 2].equals("releases")) {
                    return apiUnknown();
                }
                if (n == 3) {
                    return new HexPath(Kind.API_RELEASES, name, null, null);
                }
                String version = s[i + 3];
                if (!isValidVersionText(version)) {
                    return invalid();
                }
                if (n == 4) {
                    return new HexPath(Kind.API_RELEASE, name, version, null);
                }
                if (n == 5 && s[i + 4].equals("retire")) {
                    return new HexPath(Kind.API_RETIRE, name, version, null);
                }
                if (n == 5 && s[i + 4].equals("docs")) {
                    return new HexPath(Kind.API_RELEASE_DOCS, name, version, null);
                }
                return apiUnknown();
            }
            default -> {
                return apiUnknown();
            }
        }
    }

    private static HexPath packageDocument(String name) {
        return isValidName(name) ? new HexPath(Kind.PACKAGE, name, null, null) : invalid();
    }

    /** {@code <name>-<version>.tar}: names never contain a dash, so the first one separates the version. */
    private static HexPath tarball(String file) {
        return release(file, ".tar", Kind.TARBALL);
    }

    private static HexPath docs(String file) {
        return release(file, ".tar.gz", Kind.DOCS);
    }

    private static HexPath release(String file, String extension, Kind kind) {
        if (!file.endsWith(extension)) {
            return invalid();
        }
        String stem = file.substring(0, file.length() - extension.length());
        int dash = stem.indexOf('-');
        if (dash <= 0) {
            return invalid();
        }
        String name = stem.substring(0, dash);
        String version = stem.substring(dash + 1);
        if (!isValidName(name) || !isValidVersionText(version)) {
            return invalid();
        }
        return new HexPath(kind, name, version, file);
    }

    private static HexPath installs(String rest) {
        for (String segment : rest.split("/", -1)) {
            if (!SAFE_SEGMENT.matcher(segment).matches()) {
                return invalid();
            }
        }
        return new HexPath(Kind.INSTALLS, null, null, rest);
    }

    public static boolean isValidName(String name) {
        return name != null && name.length() <= MAX_NAME_LENGTH && PACKAGE_NAME.matcher(name).matches();
    }

    /** Lenient check for versions in URLs; publishing enforces full semantic versions. */
    public static boolean isValidVersionText(String version) {
        return version != null && version.length() <= 100 && HexVersion.LENIENT.matcher(version).matches();
    }

    private static boolean hasControlCharacters(String p) {
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) < 0x20 || p.charAt(i) == 0x7F) {
                return true;
            }
        }
        return false;
    }

    /** A path refused before parsing (an encoded separator in the raw request, for example). */
    static HexPath invalidPath() {
        return invalid();
    }

    private static HexPath invalid() {
        return new HexPath(Kind.INVALID, null, null, null);
    }

    private static HexPath unknown() {
        return new HexPath(Kind.UNKNOWN, null, null, null);
    }

    private static HexPath apiUnknown() {
        return new HexPath(Kind.API_UNKNOWN, null, null, null);
    }

    public boolean isApi() {
        return kind.name().startsWith("API_");
    }

    /**
     * Mutable documents a proxy re-validates: the registry indexes, the public key and the client install lists
     * ({@code installs/hex-1.x.csv} and its {@code .signed} signature). Tarballs, documentation and the versioned
     * client archives never change.
     */
    public boolean isMetadata() {
        return switch (kind) {
            case NAMES, VERSIONS, PACKAGE, PUBLIC_KEY -> true;
            case INSTALLS -> rest != null && (rest.endsWith(".csv") || rest.endsWith(".signed"));
            default -> false;
        };
    }

    /** The storage path of the document or artifact this path addresses, for the kinds that have one. */
    public String storagePath() {
        return switch (kind) {
            case NAMES -> "names";
            case VERSIONS -> "versions";
            case PUBLIC_KEY -> "public_key";
            case PACKAGE -> packagePath(name);
            case TARBALL -> tarballPath(name, version);
            case DOCS -> "docs/" + rest;
            case INSTALLS -> "installs/" + rest;
            default -> throw new IllegalStateException(kind + " has no storage path");
        };
    }

    public static String packagePath(String name) {
        return "packages/" + name;
    }

    public static String tarballPath(String name, String version) {
        return "tarballs/" + name + "-" + version + ".tar";
    }
}
