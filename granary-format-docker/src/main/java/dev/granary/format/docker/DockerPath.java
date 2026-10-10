package dev.granary.format.docker;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses a repository-relative request path of the distribution protocol (everything under {@code v2/}).
 *
 * <p>Image names may contain slashes ({@code library/nginx}, {@code acme/team/app}), so the path cannot be
 * split naively; instead the endpoint is recognised from its trailing segments and whatever precedes it is
 * the name. Like the reference registry, the last occurrence of an endpoint marker wins, so an image called
 * {@code acme/manifests} still works.
 *
 * <pre>
 *   v2 or v2/                                -> PING
 *   v2/_catalog                              -> CATALOG
 *   v2/{name}/manifests/{reference}          -> MANIFEST   reference = tag or digest
 *   v2/{name}/blobs/uploads                  -> UPLOAD_START
 *   v2/{name}/blobs/uploads/{uuid}           -> UPLOAD
 *   v2/{name}/blobs/{digest}                 -> BLOB
 *   v2/{name}/tags/list                      -> TAGS
 *   v2/{name}/referrers/{digest}             -> REFERRERS
 * </pre>
 *
 * The name and reference are returned as found; the handler validates them so a bad one becomes a
 * {@code NAME_INVALID} or {@code MANIFEST_UNKNOWN} error instead of a routing surprise.
 */
public record DockerPath(Kind kind, String name, String reference, String uuid) {

    public enum Kind { PING, CATALOG, MANIFEST, BLOB, UPLOAD_START, UPLOAD, TAGS, REFERRERS, UNKNOWN }

    /** The name grammar of the distribution specification, bounded to 255 characters like every real registry. */
    public static final Pattern NAME = Pattern.compile(
            "^[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*(/[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*)*$");
    public static final int MAX_NAME_LENGTH = 255;
    public static final Pattern TAG = Pattern.compile("^[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}$");
    private static final Pattern UUID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$");

    public static DockerPath parse(String rawPath) {
        String path = normalise(rawPath);
        if (path.equals("v2")) {
            return of(Kind.PING);
        }
        if (!path.startsWith("v2/")) {
            return of(Kind.UNKNOWN);
        }
        String rest = path.substring(3);
        if (rest.equals("_catalog")) {
            return of(Kind.CATALOG);
        }
        List<String> s = List.of(rest.split("/", -1));
        if (s.size() < 3 || s.stream().anyMatch(seg -> seg.isEmpty() || seg.equals(".") || seg.equals(".."))) {
            return of(Kind.UNKNOWN);
        }
        int n = s.size();
        String last = s.get(n - 1);
        String beforeLast = s.get(n - 2);
        if (beforeLast.equals("manifests")) {
            return new DockerPath(Kind.MANIFEST, join(s, n - 2), last, null);
        }
        if (beforeLast.equals("tags") && last.equals("list")) {
            return new DockerPath(Kind.TAGS, join(s, n - 2), null, null);
        }
        if (beforeLast.equals("referrers")) {
            return new DockerPath(Kind.REFERRERS, join(s, n - 2), last, null);
        }
        if (n >= 4 && s.get(n - 3).equals("blobs") && beforeLast.equals("uploads")) {
            return new DockerPath(Kind.UPLOAD, join(s, n - 3), null, last);
        }
        if (beforeLast.equals("blobs") && last.equals("uploads")) {
            return new DockerPath(Kind.UPLOAD_START, join(s, n - 2), null, null);
        }
        if (beforeLast.equals("blobs")) {
            return new DockerPath(Kind.BLOB, join(s, n - 2), last, null);
        }
        return of(Kind.UNKNOWN);
    }

    public static boolean isValidName(String name) {
        return name != null && name.length() <= MAX_NAME_LENGTH && NAME.matcher(name).matches();
    }

    public static boolean isValidTag(String tag) {
        return tag != null && TAG.matcher(tag).matches();
    }

    public static boolean isValidUploadId(String uuid) {
        return uuid != null && UUID.matcher(uuid).matches();
    }

    /** Whether the reference of a manifest request is a digest rather than a tag. */
    public boolean referenceIsDigest() {
        return Digest.isDigest(reference);
    }

    private static String join(List<String> segments, int end) {
        return String.join("/", segments.subList(0, end));
    }

    private static String normalise(String rawPath) {
        String p = rawPath == null ? "" : rawPath.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static DockerPath of(Kind kind) {
        return new DockerPath(kind, null, null, null);
    }
}
