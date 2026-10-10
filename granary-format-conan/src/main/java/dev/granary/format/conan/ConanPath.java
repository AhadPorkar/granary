package dev.granary.format.conan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Conan v2 REST routes, parsed from a repository-relative path.
 *
 * <pre>
 * v1/ping                                                     PING
 * v2/users/authenticate, v2/users/check_credentials            AUTHENTICATE, CHECK_CREDENTIALS
 *   (also under v1/: Conan 1.x logs in there even with revisions enabled)
 * v2/conans/search                                            SEARCH
 * v2/conans/{n}/{v}/{u}/{c}                                   RECIPE
 *   /latest, /revisions, /search                              RECIPE_LATEST, RECIPE_REVISIONS, PACKAGE_SEARCH
 *   /revisions/{rrev}                                         RECIPE_REVISION
 *   /revisions/{rrev}/files[/{file}]                          RECIPE_FILES, RECIPE_FILE
 *   /revisions/{rrev}/search                                  PACKAGE_SEARCH
 *   /revisions/{rrev}/packages                                PACKAGES
 *   /revisions/{rrev}/packages/{pid}                          PACKAGE
 *   /revisions/{rrev}/packages/{pid}/latest                   PACKAGE_LATEST
 *   /revisions/{rrev}/packages/{pid}/revisions                PACKAGE_REVISIONS
 *   /revisions/{rrev}/packages/{pid}/revisions/{prev}         PACKAGE_REVISION
 *   /revisions/{rrev}/packages/{pid}/revisions/{prev}/files[/{file}]   PACKAGE_FILES, PACKAGE_FILE
 * </pre>
 *
 * Any other route under {@code v1/} is {@link Kind#V1} (unsupported), a structurally valid route with an unsafe
 * segment ({@code ..}, empty, odd characters) is {@link Kind#INVALID}, and the rest is {@link Kind#UNKNOWN}.
 *
 * @param kind            the route
 * @param reference       the recipe reference, for recipe and package routes
 * @param recipeRevision  {@code rrev}, when the route names one
 * @param packageId       {@code package_id}, for package routes
 * @param packageRevision {@code prev}, when the route names one
 * @param file            the file name (possibly with sub-folders such as {@code metadata/sign/...}) for file routes
 * @param path            the parsed path without a query string
 */
public record ConanPath(Kind kind, ConanReference reference, String recipeRevision, String packageId,
                        String packageRevision, String file, String path) {

    public enum Kind {
        ROOT, PING, AUTHENTICATE, CHECK_CREDENTIALS, SEARCH,
        RECIPE, RECIPE_LATEST, RECIPE_REVISIONS, RECIPE_REVISION, RECIPE_FILES, RECIPE_FILE,
        PACKAGE_SEARCH, PACKAGES, PACKAGE, PACKAGE_LATEST, PACKAGE_REVISIONS, PACKAGE_REVISION,
        PACKAGE_FILES, PACKAGE_FILE,
        V1, UNKNOWN, INVALID
    }

    public static ConanPath parse(String raw) {
        String path = raw == null ? "" : raw;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return simple(Kind.ROOT, path);
        }
        String[] s = path.split("/", -1);
        boolean v1 = s[0].equals("v1");
        if (!v1 && !s[0].equals("v2")) {
            return simple(Kind.UNKNOWN, path);
        }
        if (s.length == 2 && s[1].equals("ping")) {
            return simple(Kind.PING, path);
        }
        if (s.length == 3 && s[1].equals("users")) {
            return switch (s[2]) {
                case "authenticate" -> simple(Kind.AUTHENTICATE, path);
                case "check_credentials" -> simple(Kind.CHECK_CREDENTIALS, path);
                default -> simple(v1 ? Kind.V1 : Kind.UNKNOWN, path);
            };
        }
        if (v1) {
            return simple(Kind.V1, path);
        }
        if (s.length < 3 || !s[1].equals("conans")) {
            return simple(Kind.UNKNOWN, path);
        }
        if (s.length == 3 && s[2].equals("search")) {
            return simple(Kind.SEARCH, path);
        }
        if (s.length < 6) {
            return simple(Kind.UNKNOWN, path);
        }
        for (int i = 2; i < 6; i++) {
            if (!ConanReference.isValidSegment(s[i])) {
                return simple(Kind.INVALID, path);
            }
        }
        ConanReference ref = new ConanReference(s[2], s[3], s[4], s[5]);
        List<String> rest = Arrays.asList(s).subList(6, s.length);
        return recipeRoute(ref, rest, path);
    }

    private static ConanPath recipeRoute(ConanReference ref, List<String> rest, String path) {
        if (rest.isEmpty()) {
            return new ConanPath(Kind.RECIPE, ref, null, null, null, null, path);
        }
        switch (rest.get(0)) {
            case "latest" -> {
                return rest.size() == 1 ? new ConanPath(Kind.RECIPE_LATEST, ref, null, null, null, null, path) : unknown(path);
            }
            case "search" -> {
                return rest.size() == 1 ? new ConanPath(Kind.PACKAGE_SEARCH, ref, null, null, null, null, path) : unknown(path);
            }
            case "revisions" -> {
                if (rest.size() == 1) {
                    return new ConanPath(Kind.RECIPE_REVISIONS, ref, null, null, null, null, path);
                }
                String rrev = rest.get(1);
                if (!ConanReference.isValidSegment(rrev)) {
                    return simple(Kind.INVALID, path);
                }
                return revisionRoute(ref, rrev, rest.subList(2, rest.size()), path);
            }
            default -> {
                return unknown(path);
            }
        }
    }

    private static ConanPath revisionRoute(ConanReference ref, String rrev, List<String> rest, String path) {
        if (rest.isEmpty()) {
            return new ConanPath(Kind.RECIPE_REVISION, ref, rrev, null, null, null, path);
        }
        switch (rest.get(0)) {
            case "files" -> {
                if (rest.size() == 1) {
                    return new ConanPath(Kind.RECIPE_FILES, ref, rrev, null, null, null, path);
                }
                String file = file(rest.subList(1, rest.size()));
                return file == null ? simple(Kind.INVALID, path)
                        : new ConanPath(Kind.RECIPE_FILE, ref, rrev, null, null, file, path);
            }
            case "search" -> {
                return rest.size() == 1 ? new ConanPath(Kind.PACKAGE_SEARCH, ref, rrev, null, null, null, path) : unknown(path);
            }
            case "packages" -> {
                if (rest.size() == 1) {
                    return new ConanPath(Kind.PACKAGES, ref, rrev, null, null, null, path);
                }
                String pid = rest.get(1);
                if (!ConanReference.isValidSegment(pid)) {
                    return simple(Kind.INVALID, path);
                }
                return packageRoute(ref, rrev, pid, rest.subList(2, rest.size()), path);
            }
            default -> {
                return unknown(path);
            }
        }
    }

    private static ConanPath packageRoute(ConanReference ref, String rrev, String pid, List<String> rest, String path) {
        if (rest.isEmpty()) {
            return new ConanPath(Kind.PACKAGE, ref, rrev, pid, null, null, path);
        }
        switch (rest.get(0)) {
            case "latest" -> {
                return rest.size() == 1 ? new ConanPath(Kind.PACKAGE_LATEST, ref, rrev, pid, null, null, path) : unknown(path);
            }
            case "revisions" -> {
                if (rest.size() == 1) {
                    return new ConanPath(Kind.PACKAGE_REVISIONS, ref, rrev, pid, null, null, path);
                }
                String prev = rest.get(1);
                if (!ConanReference.isValidSegment(prev)) {
                    return simple(Kind.INVALID, path);
                }
                List<String> tail = rest.subList(2, rest.size());
                if (tail.isEmpty()) {
                    return new ConanPath(Kind.PACKAGE_REVISION, ref, rrev, pid, prev, null, path);
                }
                if (!tail.get(0).equals("files")) {
                    return unknown(path);
                }
                if (tail.size() == 1) {
                    return new ConanPath(Kind.PACKAGE_FILES, ref, rrev, pid, prev, null, path);
                }
                String file = file(tail.subList(1, tail.size()));
                return file == null ? simple(Kind.INVALID, path)
                        : new ConanPath(Kind.PACKAGE_FILE, ref, rrev, pid, prev, file, path);
            }
            default -> {
                return unknown(path);
            }
        }
    }

    /**
     * The longest file name accepted, sub-folders included ({@code metadata/logs/build.log}). Together with
     * {@link ConanReference#MAX_SEGMENT_LENGTH} it keeps every storage path below the 2000 characters the
     * database stores.
     */
    public static final int MAX_FILE_LENGTH = 1024;

    /** Joins the file segments, or {@code null} when any of them is unsafe or the name is too long. */
    static String file(List<String> segments) {
        List<String> clean = new ArrayList<>(segments.size());
        for (String segment : segments) {
            if (!isSafeFileSegment(segment)) {
                return null;
            }
            clean.add(segment);
        }
        String file = String.join("/", clean);
        return file.length() > MAX_FILE_LENGTH ? null : file;
    }

    /** File names may start with a dot ({@code metadata/.cache}) but never are {@code .} or {@code ..}. */
    static boolean isSafeFileSegment(String segment) {
        if (segment == null || segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' || c == '+';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static ConanPath simple(Kind kind, String path) {
        return new ConanPath(kind, null, null, null, null, null, path);
    }

    private static ConanPath unknown(String path) {
        return simple(Kind.UNKNOWN, path);
    }

    /** Index documents the server derives or re-validates, as opposed to immutable files. */
    public boolean isMetadata() {
        return switch (kind) {
            case SEARCH, RECIPE_LATEST, RECIPE_REVISIONS, RECIPE_FILES, PACKAGE_SEARCH, PACKAGE_LATEST,
                 PACKAGE_REVISIONS, PACKAGE_FILES -> true;
            default -> false;
        };
    }

    public boolean isFile() {
        return kind == Kind.RECIPE_FILE || kind == Kind.PACKAGE_FILE;
    }

    public boolean isPackageRoute() {
        return packageId != null;
    }
}
