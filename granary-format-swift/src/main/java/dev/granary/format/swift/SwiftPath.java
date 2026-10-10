package dev.granary.format.swift;

/**
 * A repository-relative request path of the Swift package registry protocol.
 *
 * <ul>
 *   <li>{@code ""} - the repository root (a small description; {@code POST} logs in)</li>
 *   <li>{@code login}, {@code availability}, {@code identifiers} - the fixed endpoints</li>
 *   <li>{@code <scope>/<name>[.json]} - the release list of a package</li>
 *   <li>{@code <scope>/<name>/<version>[.json]} - one release's metadata (and the publish/delete target)</li>
 *   <li>{@code <scope>/<name>/<version>.zip} - the source archive</li>
 *   <li>{@code <scope>/<name>/<version>/Package.swift} - the manifest ({@code ?swift-version=} picks a variant)</li>
 * </ul>
 * Scope, name and version are validated; anything malformed is {@link Kind#INVALID} with a message, anything
 * that is simply not an endpoint is {@link Kind#UNKNOWN}.
 *
 * @param kind     what the path addresses
 * @param identity the package, for package, release, archive and manifest paths
 * @param version  the release version, for release, archive and manifest paths
 * @param error    why the path is invalid, for {@link Kind#INVALID}
 */
public record SwiftPath(Kind kind, SwiftIdentity identity, String version, String error) {

    public enum Kind {
        ROOT, LOGIN, AVAILABILITY, IDENTIFIERS, PACKAGE, RELEASE, ARCHIVE, MANIFEST, UNKNOWN, INVALID
    }

    static final String JSON_SUFFIX = ".json";
    static final String ZIP_SUFFIX = ".zip";

    public static SwiftPath parse(String path) {
        String p = path == null ? "" : path;
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            return of(Kind.ROOT);
        }
        String[] s = p.split("/", -1);
        for (String segment : s) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return invalid("Illegal path");
            }
        }
        return switch (s.length) {
            case 1 -> switch (s[0]) {
                case "login" -> of(Kind.LOGIN);
                case "availability" -> of(Kind.AVAILABILITY);
                case "identifiers" -> of(Kind.IDENTIFIERS);
                default -> of(Kind.UNKNOWN);
            };
            case 2 -> pkg(s[0], strip(s[1], JSON_SUFFIX));
            case 3 -> {
                if (s[2].endsWith(ZIP_SUFFIX)) {
                    yield release(Kind.ARCHIVE, s[0], s[1], strip(s[2], ZIP_SUFFIX));
                }
                yield release(Kind.RELEASE, s[0], s[1], strip(s[2], JSON_SUFFIX));
            }
            case 4 -> SwiftManifests.MANIFEST.equals(s[3])
                    ? release(Kind.MANIFEST, s[0], s[1], s[2])
                    : of(Kind.UNKNOWN);
            default -> of(Kind.UNKNOWN);
        };
    }

    private static String strip(String segment, String suffix) {
        return segment.endsWith(suffix) ? segment.substring(0, segment.length() - suffix.length()) : segment;
    }

    private static SwiftPath pkg(String scope, String name) {
        String error = identityError(scope, name);
        return error != null ? invalid(error) : new SwiftPath(Kind.PACKAGE, new SwiftIdentity(scope, name), null, null);
    }

    private static SwiftPath release(Kind kind, String scope, String name, String version) {
        String error = identityError(scope, name);
        if (error != null) {
            return invalid(error);
        }
        if (!SwiftVersion.isValid(version)) {
            return invalid("'" + abbreviate(version) + "' is not a valid semantic version");
        }
        return new SwiftPath(kind, new SwiftIdentity(scope, name), version, null);
    }

    private static String identityError(String scope, String name) {
        if (!SwiftIdentity.isValidScope(scope)) {
            return "'" + abbreviate(scope) + "' is not a valid package scope";
        }
        if (!SwiftIdentity.isValidName(name)) {
            return "'" + abbreviate(name) + "' is not a valid package name";
        }
        return null;
    }

    private static String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    private static SwiftPath of(Kind kind) {
        return new SwiftPath(kind, null, null, null);
    }

    private static SwiftPath invalid(String error) {
        return new SwiftPath(Kind.INVALID, null, null, error);
    }
}
