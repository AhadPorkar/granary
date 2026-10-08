package dev.granary.format.nuget;

import java.util.Locale;

/**
 * The two address spaces of a NuGet repository: the URLs the V3 protocol exposes under the repository's
 * base URL, and the repository-relative paths under which the files live in storage.
 *
 * <p>Storage paths are shared by hosted and proxy repositories ({@code {id}/{version}/{id}.{version}.nupkg},
 * always lower-case) so group lookups, the UI's component browser and cache eviction treat both alike.
 * Ids and versions in NuGet are case-insensitive, which is why everything is lower-cased before it becomes
 * a path.
 */
public final class NuGetLayout {

    /** Where a proxy caches the upstream service index. */
    public static final String SERVICE_INDEX_CACHE_PATH = "index.json";

    private NuGetLayout() {
    }

    // --- public URLs -------------------------------------------------------------------------

    public static String flatContainerBase(String baseUrl) {
        return baseUrl + "/v3/flatcontainer/";
    }

    public static String registrationBase(String baseUrl) {
        return baseUrl + "/v3/registration/";
    }

    public static String searchUrl(String baseUrl) {
        return baseUrl + "/v3/query";
    }

    public static String publishUrl(String baseUrl) {
        return baseUrl + "/v2/package";
    }

    public static String symbolPublishUrl(String baseUrl) {
        return baseUrl + "/v2/symbolpackage";
    }

    public static String flatIndexUrl(String baseUrl, String id) {
        return flatContainerBase(baseUrl) + lower(id) + "/index.json";
    }

    public static String packageContentUrl(String baseUrl, String id, String version) {
        String i = lower(id);
        String v = lower(version);
        return flatContainerBase(baseUrl) + i + "/" + v + "/" + i + "." + v + ".nupkg";
    }

    public static String nuspecUrl(String baseUrl, String id, String version) {
        String i = lower(id);
        return flatContainerBase(baseUrl) + i + "/" + lower(version) + "/" + i + ".nuspec";
    }

    public static String registrationIndexUrl(String baseUrl, String id) {
        return registrationBase(baseUrl) + lower(id) + "/index.json";
    }

    public static String registrationLeafUrl(String baseUrl, String id, String version) {
        return registrationBase(baseUrl) + lower(id) + "/" + lower(version) + ".json";
    }

    /** Pages are always inlined, so their id is a fragment of the index, the way nuget.org does it. */
    public static String registrationPageUrl(String baseUrl, String id, String lower, String upper) {
        return registrationIndexUrl(baseUrl, id) + "#page/" + lower(lower) + "/" + lower(upper);
    }

    // --- storage paths -----------------------------------------------------------------------

    /** {@code {id}/{version}/}: everything belonging to one package version lives under this prefix. */
    public static String versionPrefix(String id, String version) {
        return lower(id) + "/" + lower(version) + "/";
    }

    public static String nupkgPath(String id, String version) {
        return versionPrefix(id, version) + lower(id) + "." + lower(version) + ".nupkg";
    }

    public static String snupkgPath(String id, String version) {
        return versionPrefix(id, version) + lower(id) + "." + lower(version) + ".snupkg";
    }

    public static String nuspecPath(String id, String version) {
        return versionPrefix(id, version) + lower(id) + ".nuspec";
    }

    /** Where a proxy caches the upstream flat-container version list of a package. */
    public static String flatIndexCachePath(String id) {
        return lower(id) + "/index.json";
    }

    /** Where a proxy caches the upstream registration index of a package. */
    public static String registrationCachePath(String id) {
        return "v3/registration/" + lower(id) + "/index.json";
    }

    /** Where a proxy caches the {@code n}-th non-inlined registration page of a package. */
    public static String registrationPageCachePath(String id, int page) {
        return "v3/registration/" + lower(id) + "/page-" + page + ".json";
    }

    public static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
