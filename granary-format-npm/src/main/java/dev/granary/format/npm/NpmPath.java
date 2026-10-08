package dev.granary.format.npm;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses a request path of the npm registry protocol into what it addresses.
 *
 * <p>The protocol is a flat CouchDB-style namespace in which a package name may itself contain a
 * slash ({@code @scope/name}), so the path cannot be split naively. The dispatcher hands us the
 * URL-decoded path, but a client that double-encodes the scope separator is tolerated as well.
 *
 * <pre>
 *   lodash                                  → PACKUMENT        name=lodash
 *   @babel/core                             → PACKUMENT        name=@babel/core (also @babel%2Fcore)
 *   lodash/4.17.21                          → VERSION          version=4.17.21 (or a dist-tag such as "latest")
 *   @babel/core/-/core-7.0.0.tgz            → TARBALL          fileName=core-7.0.0.tgz
 *   lodash/-rev/3-abc                       → PACKAGE_REVISION revision=3-abc (unpublish)
 *   lodash/-/lodash-1.0.0.tgz/-rev/3-abc    → TARBALL_REVISION (unpublish of one tarball)
 *   -/package/@babel/core/dist-tags         → DIST_TAGS
 *   -/package/lodash/dist-tags/beta         → DIST_TAG         tag=beta
 *   -/user/org.couchdb.user:alice           → LOGIN            username=alice
 *   -/v1/login                              → WEB_LOGIN        (answered 404 so npm falls back to LOGIN)
 *   -/whoami, -/ping, -/v1/search           → WHOAMI, PING, SEARCH
 *   -/npm/v1/security/…                     → AUDIT
 * </pre>
 */
public record NpmPath(String path, Kind kind, String packageName, String version, String fileName,
                      String revision, String tag, String username) {

    public enum Kind {
        /** The registry root. */
        ROOT,
        /** The full metadata document of a package. */
        PACKUMENT,
        /** One version object, addressed by version number or dist-tag. */
        VERSION,
        /** A {@code .tgz} under {@code {name}/-/}. */
        TARBALL,
        /** {@code {name}/-rev/{rev}}: npm's unpublish of a whole package (DELETE) or of versions (PUT). */
        PACKAGE_REVISION,
        /** {@code {name}/-/{file}/-rev/{rev}}: npm's unpublish of a single tarball. */
        TARBALL_REVISION,
        DIST_TAGS,
        DIST_TAG,
        /** The legacy CouchDB-style login endpoint ({@code npm login --auth-type=legacy}, {@code npm adduser}). */
        LOGIN,
        /** The web login endpoint, which this registry does not offer. */
        WEB_LOGIN,
        WHOAMI,
        PING,
        SEARCH,
        /** The security audit endpoints, which this registry does not offer. */
        AUDIT,
        UNKNOWN
    }

    /** Lenient on purpose: old registries hold names with upper-case letters that today's npm would refuse. */
    private static final Pattern NAME_SEGMENT = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._~-]{0,213}$");
    private static final String USER_PREFIX = "org.couchdb.user:";

    public static NpmPath parse(String rawPath) {
        String path = normalise(rawPath);
        if (path.isEmpty()) {
            return of(path, Kind.ROOT);
        }
        List<String> segments = List.of(path.split("/"));
        if (segments.stream().anyMatch(String::isEmpty)) {
            return of(path, Kind.UNKNOWN);
        }
        if (segments.getFirst().equals("-")) {
            return parseSpecial(path, segments);
        }
        return parsePackage(path, segments);
    }

    private static NpmPath parsePackage(String path, List<String> segments) {
        int nameLength = nameLength(segments, 0);
        if (nameLength < 0) {
            return of(path, Kind.UNKNOWN);
        }
        String name = String.join("/", segments.subList(0, nameLength));
        List<String> rest = segments.subList(nameLength, segments.size());
        if (rest.isEmpty()) {
            return new NpmPath(path, Kind.PACKUMENT, name, null, null, null, null, null);
        }
        String first = rest.getFirst();
        if (first.equals("-")) {
            if (rest.size() == 2 && isFileName(rest.get(1))) {
                return new NpmPath(path, Kind.TARBALL, name, null, rest.get(1), null, null, null);
            }
            if (rest.size() == 4 && isFileName(rest.get(1)) && rest.get(2).equals("-rev")) {
                return new NpmPath(path, Kind.TARBALL_REVISION, name, null, rest.get(1), rest.get(3), null, null);
            }
            return of(path, Kind.UNKNOWN);
        }
        if (first.equals("-rev")) {
            return rest.size() == 2
                    ? new NpmPath(path, Kind.PACKAGE_REVISION, name, null, null, rest.get(1), null, null)
                    : of(path, Kind.UNKNOWN);
        }
        if (rest.size() == 1) {
            return new NpmPath(path, Kind.VERSION, name, first, null, null, null, null);
        }
        return of(path, Kind.UNKNOWN);
    }

    private static NpmPath parseSpecial(String path, List<String> s) {
        if (s.size() < 2) {
            return of(path, Kind.UNKNOWN);
        }
        return switch (s.get(1)) {
            case "ping" -> s.size() == 2 ? of(path, Kind.PING) : of(path, Kind.UNKNOWN);
            case "whoami" -> s.size() == 2 ? of(path, Kind.WHOAMI) : of(path, Kind.UNKNOWN);
            case "v1" -> {
                if (s.size() != 3) {
                    yield of(path, Kind.UNKNOWN);
                }
                yield switch (s.get(2)) {
                    case "search" -> of(path, Kind.SEARCH);
                    case "login" -> of(path, Kind.WEB_LOGIN);
                    default -> of(path, Kind.UNKNOWN);
                };
            }
            case "user" -> parseUser(path, s);
            case "package" -> parseDistTags(path, s);
            case "npm" -> path.startsWith("-/npm/v1/security/") ? of(path, Kind.AUDIT) : of(path, Kind.UNKNOWN);
            default -> of(path, Kind.UNKNOWN);
        };
    }

    private static NpmPath parseUser(String path, List<String> s) {
        if (s.size() < 3 || !s.get(2).startsWith(USER_PREFIX) || s.get(2).length() == USER_PREFIX.length()) {
            return of(path, Kind.UNKNOWN);
        }
        String username = s.get(2).substring(USER_PREFIX.length());
        if (s.size() == 3) {
            return new NpmPath(path, Kind.LOGIN, null, null, null, null, null, username);
        }
        if (s.size() == 5 && s.get(3).equals("-rev")) {
            return new NpmPath(path, Kind.LOGIN, null, null, null, s.get(4), null, username);
        }
        return of(path, Kind.UNKNOWN);
    }

    private static NpmPath parseDistTags(String path, List<String> s) {
        int nameLength = nameLength(s, 2);
        if (nameLength < 0) {
            return of(path, Kind.UNKNOWN);
        }
        String name = String.join("/", s.subList(2, 2 + nameLength));
        int idx = 2 + nameLength;
        if (idx >= s.size() || !s.get(idx).equals("dist-tags")) {
            return of(path, Kind.UNKNOWN);
        }
        if (idx == s.size() - 1) {
            return new NpmPath(path, Kind.DIST_TAGS, name, null, null, null, null, null);
        }
        if (idx == s.size() - 2) {
            return new NpmPath(path, Kind.DIST_TAG, name, null, null, null, s.get(idx + 1), null);
        }
        return of(path, Kind.UNKNOWN);
    }

    /** Number of segments the package name occupies starting at {@code from}: 1, 2 for a scoped name, or -1. */
    private static int nameLength(List<String> segments, int from) {
        if (from >= segments.size()) {
            return -1;
        }
        String first = segments.get(from);
        if (first.startsWith("@")) {
            if (from + 1 >= segments.size()) {
                return -1;
            }
            return isNameSegment(first.substring(1)) && isNameSegment(segments.get(from + 1)) ? 2 : -1;
        }
        return isNameSegment(first) ? 1 : -1;
    }

    private static boolean isNameSegment(String s) {
        return NAME_SEGMENT.matcher(s).matches();
    }

    private static boolean isFileName(String s) {
        return !s.isEmpty() && !s.equals("-rev") && !s.equals("..") && !s.equals(".");
    }

    private static String normalise(String rawPath) {
        String p = rawPath == null ? "" : rawPath.trim();
        // the dispatcher decodes once; a client that encoded the scope separator twice still means "/"
        p = p.replace("%2F", "/").replace("%2f", "/");
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static NpmPath of(String path, Kind kind) {
        return new NpmPath(path, kind, null, null, null, null, null, null);
    }

    /** Whether the path addresses a package at all (as opposed to a registry-level endpoint). */
    public boolean hasPackage() {
        return packageName != null;
    }

    /** The scope without the leading {@code @}, or {@code null} for an unscoped package. */
    public String scope() {
        return scopeOf(packageName);
    }

    /** The name without its scope. */
    public String unscopedName() {
        return unscopedNameOf(packageName);
    }

    /** The repository-relative path a tarball is stored under: {@code {name}/-/{file}}. */
    public String tarballPath() {
        return tarballPath(packageName, fileName);
    }

    /**
     * Packuments, versions and dist-tags change whenever someone publishes, so a proxy must re-validate
     * them; tarballs are immutable once published and are cached forever.
     */
    public boolean isMetadata() {
        return kind != Kind.TARBALL && kind != Kind.TARBALL_REVISION;
    }

    public static String scopeOf(String packageName) {
        if (packageName == null || !packageName.startsWith("@")) {
            return null;
        }
        int slash = packageName.indexOf('/');
        return slash < 0 ? null : packageName.substring(1, slash);
    }

    public static String unscopedNameOf(String packageName) {
        if (packageName == null) {
            return null;
        }
        int slash = packageName.indexOf('/');
        return slash < 0 ? packageName : packageName.substring(slash + 1);
    }

    public static String tarballPath(String packageName, String fileName) {
        return packageName + "/-/" + fileName;
    }

    /** Whether {@code name} is a syntactically acceptable package name. */
    public static boolean isValidPackageName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        List<String> segments = List.of(name.split("/", -1));
        return nameLength(segments, 0) == segments.size();
    }
}
