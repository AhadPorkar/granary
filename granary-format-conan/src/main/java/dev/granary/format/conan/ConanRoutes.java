package dev.granary.format.conan;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Builds the v2 API paths (repository-relative, no leading slash) a proxy forwards to its upstream. */
public final class ConanRoutes {

    private ConanRoutes() {
    }

    public static String search() {
        return "v2/conans/search";
    }

    /**
     * The search route with its query, spelled the way the client does: {@code q} only for a pattern, and
     * {@code ignorecase=False} only for a case-sensitive search. The result doubles as the proxy's cache path.
     */
    public static String search(String pattern, boolean ignoreCase) {
        if (pattern == null || pattern.isBlank()) {
            return search();
        }
        String query = "?q=" + URLEncoder.encode(pattern.trim(), StandardCharsets.UTF_8);
        return search() + query + (ignoreCase ? "" : "&ignorecase=False");
    }

    public static String recipe(ConanReference ref) {
        return "v2/conans/" + ref.name() + "/" + ref.version() + "/" + ref.userSegment() + "/" + ref.channelSegment();
    }

    public static String recipeRevisions(ConanReference ref) {
        return recipe(ref) + "/revisions";
    }

    public static String recipeLatest(ConanReference ref) {
        return recipe(ref) + "/latest";
    }

    public static String recipeRevision(ConanReference ref, String rrev) {
        return recipeRevisions(ref) + "/" + rrev;
    }

    public static String recipeFiles(ConanReference ref, String rrev) {
        return recipeRevision(ref, rrev) + "/files";
    }

    public static String recipeFile(ConanReference ref, String rrev, String file) {
        return recipeFiles(ref, rrev) + "/" + file;
    }

    public static String packageSearch(ConanReference ref, String rrev) {
        return recipeRevision(ref, rrev) + "/search";
    }

    public static String packages(ConanReference ref, String rrev) {
        return recipeRevision(ref, rrev) + "/packages";
    }

    public static String packageRevisions(ConanReference ref, String rrev, String packageId) {
        return packages(ref, rrev) + "/" + packageId + "/revisions";
    }

    public static String packageLatest(ConanReference ref, String rrev, String packageId) {
        return packages(ref, rrev) + "/" + packageId + "/latest";
    }

    public static String packageFiles(ConanReference ref, String rrev, String packageId, String prev) {
        return packageRevisions(ref, rrev, packageId) + "/" + prev + "/files";
    }

    public static String packageFile(ConanReference ref, String rrev, String packageId, String prev, String file) {
        return packageFiles(ref, rrev, packageId, prev) + "/" + file;
    }
}
