package dev.granary.format.go;

import java.util.regex.Pattern;

/**
 * A repository-relative request path of the GOPROXY protocol, taken apart.
 *
 * <p>Module paths and versions arrive case-encoded ({@code github.com/!burnt!sushi/toml}); {@link #module()} and
 * {@link #version()} are the decoded forms and {@link #escapedModule()}/{@link #escapedVersion()} the forms used
 * in storage paths and upstream URLs. Anything that is not a well-formed path of a known shape is
 * {@link Kind#UNKNOWN}; malformed escaping or an illegal module path is a 400 ({@link GoError}).
 */
public record GoPath(Kind kind, String module, String escapedModule, String version, String escapedVersion,
                     String sumdbName, String sumdbRest, String raw) {

    public enum Kind {
        /** The repository root. */
        ROOT,
        /** {@code <module>/@v/list}. */
        LIST,
        /** {@code <module>/@v/<version>.info}. */
        INFO,
        /** {@code <module>/@v/<version>.mod}. */
        MOD,
        /** {@code <module>/@v/<version>.zip}. */
        ZIP,
        /** {@code <module>/@latest}. */
        LATEST,
        /** {@code <module>/@v/<version>} without a suffix; only meaningful for DELETE. */
        VERSION,
        /** {@code sumdb/<name>/supported}. */
        SUMDB_SUPPORTED,
        /** {@code sumdb/<name>/<rest>}: a checksum database request passed through. */
        SUMDB,
        UNKNOWN
    }

    static final String SUMDB_PREFIX = "sumdb/";
    private static final Pattern SUMDB_NAME = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$");
    private static final Pattern VERSION_CHARS = Pattern.compile("^[A-Za-z0-9!._+~-]+$");

    public static GoPath parse(String path) {
        String p = path == null ? "" : path;
        if (p.isEmpty()) {
            return new GoPath(Kind.ROOT, null, null, null, null, null, null, p);
        }
        if (p.startsWith(SUMDB_PREFIX)) {
            return parseSumdb(p);
        }
        int at = p.indexOf("/@v/");
        if (at > 0) {
            String module = p.substring(0, at);
            String file = p.substring(at + 4);
            if (file.isEmpty() || file.indexOf('/') >= 0) {
                return unknown(p);
            }
            if (file.equals("list")) {
                return module(Kind.LIST, module, null, p);
            }
            if (file.endsWith(".info")) {
                return module(Kind.INFO, module, file.substring(0, file.length() - 5), p);
            }
            if (file.endsWith(".mod")) {
                return module(Kind.MOD, module, file.substring(0, file.length() - 4), p);
            }
            if (file.endsWith(".zip")) {
                return module(Kind.ZIP, module, file.substring(0, file.length() - 4), p);
            }
            return module(Kind.VERSION, module, file, p);
        }
        if (p.endsWith("/@latest") && p.length() > 8) {
            return module(Kind.LATEST, p.substring(0, p.length() - 8), null, p);
        }
        return unknown(p);
    }

    private static GoPath module(Kind kind, String escapedModule, String escapedVersion, String raw) {
        String module = GoModulePaths.unescape(escapedModule);
        GoModulePaths.checkModulePath(module);
        String version = null;
        if (escapedVersion != null) {
            if (!VERSION_CHARS.matcher(escapedVersion).matches()) {
                throw GoError.invalidVersion("malformed version \"" + escapedVersion + "\"");
            }
            version = GoModulePaths.unescape(escapedVersion);
        }
        return new GoPath(kind, module, GoModulePaths.escape(module), version,
                version == null ? null : GoModulePaths.escape(version), null, null, raw);
    }

    private static GoPath parseSumdb(String p) {
        String rest = p.substring(SUMDB_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return unknown(p);
        }
        String name = rest.substring(0, slash);
        String tail = rest.substring(slash + 1);
        if (!isValidSumdbName(name)) {
            return unknown(p);
        }
        for (String segment : tail.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return unknown(p);
            }
        }
        if (tail.equals("supported")) {
            return new GoPath(Kind.SUMDB_SUPPORTED, null, null, null, null, name, tail, p);
        }
        return new GoPath(Kind.SUMDB, null, null, null, null, name, tail, p);
    }

    /** A checksum database name as the go command derives it from {@code GOSUMDB}: a lower-case host name. */
    public static boolean isValidSumdbName(String name) {
        return name != null && name.length() <= 253 && SUMDB_NAME.matcher(name).matches();
    }

    private static GoPath unknown(String raw) {
        return new GoPath(Kind.UNKNOWN, null, null, null, null, null, null, raw);
    }

    public boolean isModuleFile() {
        return kind == Kind.INFO || kind == Kind.MOD || kind == Kind.ZIP;
    }

    /**
     * Whether the path names a mutable document: version lists, {@code @latest}, the checksum database's
     * {@code latest} tree head, and {@code .info} lookups of anything but a canonical version (branch names,
     * commits, version prefixes such as {@code v1.2}), which upstream resolves to a version that changes over time.
     */
    public boolean isMetadata() {
        return switch (kind) {
            case LIST, LATEST -> true;
            case INFO -> !hasCanonicalVersion();
            case SUMDB -> !sumdbRest.startsWith("lookup/") && !sumdbRest.startsWith("tile/");
            default -> false;
        };
    }

    /** {@code true} when the version is one the go command can download files for (canonical semver). */
    public boolean hasCanonicalVersion() {
        return version != null && GoSemver.isValid(version) && GoSemver.canonical(version).equals(version);
    }

    /** The storage/upstream path of this version's {@code .info}, {@code .mod} or {@code .zip}. */
    public String filePath(String extension) {
        return escapedModule + "/@v/" + escapedVersion + "." + extension;
    }

    public static String filePath(String escapedModule, String escapedVersion, String extension) {
        return escapedModule + "/@v/" + escapedVersion + "." + extension;
    }

    public static String listPath(String escapedModule) {
        return escapedModule + "/@v/list";
    }

    public static String latestPath(String escapedModule) {
        return escapedModule + "/@latest";
    }

    /** The {@code <module>/@v/} prefix every file of a module lives under. */
    public static String versionsPrefix(String escapedModule) {
        return escapedModule + "/@v/";
    }
}
