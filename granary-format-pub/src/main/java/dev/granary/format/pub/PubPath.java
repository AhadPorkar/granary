package dev.granary.format.pub;

import java.util.Locale;

/**
 * A repository-relative request path, classified by what the Hosted Pub Repository Specification v2 (and
 * pub.dev's administrative API) uses it for.
 *
 * <pre>
 * api/packages/{name}                                  package listing (versions and their pubspecs)
 * api/packages/{name}/versions/{version}               one version
 * api/packages/{name}/advisories                       security advisories
 * api/packages/{name}/options                          discontinued flag (pub.dev admin API)
 * api/packages/{name}/versions/{version}/options       retracted flag (pub.dev admin API)
 * api/packages/{name}/versions/{version}/retract       retract (POST) or restore (DELETE)
 * api/packages/versions/new                            start a publish
 * api/packages/versions/newUpload                      multipart upload of the archive
 * api/packages/versions/newUploadFinish                outcome of the upload
 * packages/{name}/versions/{version}.tar.gz            the archive
 * </pre>
 *
 * The publishing endpoints take precedence over a package that happens to be called {@code versions}:
 * {@code new}, {@code newUpload} and {@code newUploadFinish} are not versions, so nothing is lost.
 *
 * @param kind    what the path addresses
 * @param name    the package name, when the path names one
 * @param version the version as written in the path, when the path names one
 */
public record PubPath(Kind kind, String name, String version) {

    public enum Kind {
        ROOT, PACKAGE, VERSION, ADVISORIES, PACKAGE_OPTIONS, VERSION_OPTIONS, RETRACT, ARCHIVE,
        PUBLISH_NEW, PUBLISH_UPLOAD, PUBLISH_FINISH,
        /** A pub path with a malformed package name or version: answered with 400. */
        INVALID,
        /** Anything else: answered with 404. */
        UNKNOWN
    }

    public static final String API_PACKAGES = "api/packages/";
    public static final String ARCHIVE_SUFFIX = ".tar.gz";
    static final String NEW = "api/packages/versions/new";
    static final String NEW_UPLOAD = "api/packages/versions/newUpload";
    static final String NEW_UPLOAD_FINISH = "api/packages/versions/newUploadFinish";

    private static final PubPath ROOT = new PubPath(Kind.ROOT, null, null);
    private static final PubPath UNKNOWN = new PubPath(Kind.UNKNOWN, null, null);
    private static final PubPath INVALID = new PubPath(Kind.INVALID, null, null);

    public static PubPath parse(String raw) {
        String path = raw == null ? "" : raw;
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return ROOT;
        }
        switch (path) {
            case NEW -> {
                return new PubPath(Kind.PUBLISH_NEW, null, null);
            }
            case NEW_UPLOAD -> {
                return new PubPath(Kind.PUBLISH_UPLOAD, null, null);
            }
            case NEW_UPLOAD_FINISH -> {
                return new PubPath(Kind.PUBLISH_FINISH, null, null);
            }
            default -> {
                // fall through to the structured forms
            }
        }
        String[] s = path.split("/", -1);
        if (s.length >= 3 && s[0].equals("api") && s[1].equals("packages")) {
            return api(s);
        }
        if (s.length == 4 && s[0].equals("packages") && s[2].equals("versions")) {
            if (!s[3].endsWith(ARCHIVE_SUFFIX)) {
                return UNKNOWN;
            }
            return named(Kind.ARCHIVE, s[1], s[3].substring(0, s[3].length() - ARCHIVE_SUFFIX.length()));
        }
        return UNKNOWN;
    }

    /**
     * The path of a request as the client wrote it: {@code rawUri} is the undecoded request URI. Package names
     * and versions never contain a slash, so a segment with an encoded one ({@code %2F}) names nothing and is
     * invalid rather than silently read as two segments.
     */
    public static PubPath parse(String decoded, String rawUri) {
        if (rawUri != null && rawUri.toLowerCase(Locale.ROOT).contains("%2f")) {
            return INVALID;
        }
        return parse(decoded);
    }

    private static PubPath api(String[] s) {
        String name = s[2];
        return switch (s.length) {
            case 3 -> named(Kind.PACKAGE, name, null);
            case 4 -> switch (s[3]) {
                case "advisories" -> named(Kind.ADVISORIES, name, null);
                case "options" -> named(Kind.PACKAGE_OPTIONS, name, null);
                default -> UNKNOWN;
            };
            case 5 -> s[3].equals("versions") ? named(Kind.VERSION, name, s[4]) : UNKNOWN;
            case 6 -> {
                if (!s[3].equals("versions")) {
                    yield UNKNOWN;
                }
                yield switch (s[5]) {
                    case "options" -> named(Kind.VERSION_OPTIONS, name, s[4]);
                    case "retract" -> named(Kind.RETRACT, name, s[4]);
                    default -> UNKNOWN;
                };
            }
            default -> UNKNOWN;
        };
    }

    private static PubPath named(Kind kind, String name, String version) {
        if (!PubNames.isValid(name)) {
            return INVALID;
        }
        if (version != null && !PubVersion.isValid(version)) {
            return INVALID;
        }
        return new PubPath(kind, name, version);
    }

    /** Index documents that change over time, as opposed to archives, which never do once published. */
    public boolean isMetadata() {
        return switch (kind) {
            case ARCHIVE, INVALID, UNKNOWN -> false;
            default -> true;
        };
    }

    /** Where a package's listing lives in storage (hosted state or proxy cache). */
    public static String listingPath(String name) {
        return API_PACKAGES + name;
    }

    /** Where a proxy caches a package's advisories. */
    public static String advisoriesPath(String name) {
        return API_PACKAGES + name + "/advisories";
    }

    /** The prefix under which all archives of a package live. */
    public static String archivePrefix(String name) {
        return "packages/" + name + "/versions/";
    }

    /** Where the archive of one version lives, in storage and in the repository's URL space. */
    public static String archivePath(String name, String version) {
        return archivePrefix(name) + version + ARCHIVE_SUFFIX;
    }
}
