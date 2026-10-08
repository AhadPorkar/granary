package dev.granary.format.nuget;

import java.util.Locale;

/**
 * Classifies a repository-relative request path into the V3 protocol endpoint it addresses.
 *
 * <pre>
 *   index.json                                            → SERVICE_INDEX
 *   v3/flatcontainer/{id}/index.json                      → FLAT_INDEX
 *   v3/flatcontainer/{id}/{version}/{id}.{version}.nupkg  → PACKAGE
 *   v3/flatcontainer/{id}/{version}/{id}.nuspec           → NUSPEC
 *   v3/flatcontainer/{id}/{version}/{id}.{version}.snupkg → SYMBOLS
 *   v3/registration/{id}/index.json                       → REGISTRATION_INDEX
 *   v3/registration/{id}/{version}.json                   → REGISTRATION_LEAF
 *   v3/query                                              → SEARCH
 *   v2/package                                            → PUBLISH          (PUT)
 *   v2/package/{id}/{version}                             → PACKAGE_VERSION  (DELETE)
 *   v2/symbolpackage                                      → SYMBOL_PUBLISH   (PUT)
 * </pre>
 *
 * Ids are lower-cased here because NuGet ids are case-insensitive and storage paths are lower-case;
 * versions are kept as sent and normalised by the caller, so an invalid version can be reported.
 *
 * @param id      the lower-cased package id, when the endpoint names one
 * @param version the version segment as sent, when the endpoint names one
 */
public record NuGetPath(String path, Kind kind, String id, String version, String fileName) {

    public enum Kind {
        SERVICE_INDEX,
        FLAT_INDEX,
        PACKAGE,
        NUSPEC,
        SYMBOLS,
        REGISTRATION_INDEX,
        REGISTRATION_LEAF,
        SEARCH,
        PUBLISH,
        PACKAGE_VERSION,
        SYMBOL_PUBLISH,
        UNKNOWN
    }

    public static NuGetPath parse(String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty() || path.equals("index.json")) {
            return new NuGetPath(path, Kind.SERVICE_INDEX, null, null, null);
        }
        String[] s = path.split("/", -1);
        if (s.length == 2 && s[0].equals("v3") && s[1].equals("query")) {
            return new NuGetPath(path, Kind.SEARCH, null, null, null);
        }
        if (s[0].equals("v3") && s.length >= 4 && s[1].equals("flatcontainer")) {
            return flatContainer(path, s);
        }
        if (s[0].equals("v3") && s.length == 4 && s[1].equals("registration")) {
            return registration(path, s);
        }
        if (s[0].equals("v2") && s[1].equals("package")) {
            if (s.length == 2 || (s.length == 3 && s[2].isEmpty())) {
                return new NuGetPath(path, Kind.PUBLISH, null, null, null);
            }
            if (s.length == 4 && !s[2].isEmpty() && !s[3].isEmpty()) {
                return new NuGetPath(path, Kind.PACKAGE_VERSION, lower(s[2]), s[3], null);
            }
        }
        if (s[0].equals("v2") && s[1].equals("symbolpackage") && (s.length == 2 || (s.length == 3 && s[2].isEmpty()))) {
            return new NuGetPath(path, Kind.SYMBOL_PUBLISH, null, null, null);
        }
        return new NuGetPath(path, Kind.UNKNOWN, null, null, null);
    }

    private static NuGetPath flatContainer(String path, String[] s) {
        String id = lower(s[2]);
        if (id.isEmpty()) {
            return new NuGetPath(path, Kind.UNKNOWN, null, null, null);
        }
        if (s.length == 4 && s[3].equals("index.json")) {
            return new NuGetPath(path, Kind.FLAT_INDEX, id, null, null);
        }
        if (s.length == 5 && !s[3].isEmpty() && !s[4].isEmpty()) {
            String file = s[4].toLowerCase(Locale.ROOT);
            if (file.endsWith(".nupkg")) {
                return new NuGetPath(path, Kind.PACKAGE, id, s[3], file);
            }
            if (file.endsWith(".snupkg")) {
                return new NuGetPath(path, Kind.SYMBOLS, id, s[3], file);
            }
            if (file.endsWith(".nuspec")) {
                return new NuGetPath(path, Kind.NUSPEC, id, s[3], file);
            }
        }
        return new NuGetPath(path, Kind.UNKNOWN, id, null, null);
    }

    private static NuGetPath registration(String path, String[] s) {
        String id = lower(s[2]);
        if (id.isEmpty()) {
            return new NuGetPath(path, Kind.UNKNOWN, null, null, null);
        }
        if (s[3].equals("index.json")) {
            return new NuGetPath(path, Kind.REGISTRATION_INDEX, id, null, null);
        }
        if (s[3].endsWith(".json") && s[3].length() > ".json".length()) {
            return new NuGetPath(path, Kind.REGISTRATION_LEAF, id, s[3].substring(0, s[3].length() - ".json".length()), null);
        }
        return new NuGetPath(path, Kind.UNKNOWN, id, null, null);
    }

    /** Index documents change whenever a version is published; packages and manifests never do. */
    public boolean isMutable() {
        return switch (kind) {
            case SERVICE_INDEX, FLAT_INDEX, REGISTRATION_INDEX, REGISTRATION_LEAF, SEARCH -> true;
            default -> false;
        };
    }

    public boolean isWrite() {
        return kind == Kind.PUBLISH || kind == Kind.SYMBOL_PUBLISH || kind == Kind.PACKAGE_VERSION;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
