package dev.granary.format.rubygems;

/**
 * What a repository-relative path means to a RubyGems repository.
 *
 * @param kind     the kind of resource
 * @param path     the path as requested
 * @param name     the gem name for {@code info/…} and the per-gem API documents, otherwise {@code null}
 * @param fullName {@code name-version[-platform]} for {@code .gem} files and quick specs, otherwise {@code null}
 */
public record GemPath(Kind kind, String path, String name, String fullName) {

    public enum Kind {
        ROOT,
        VERSIONS,
        NAMES,
        INFO,
        SPECS,
        LATEST_SPECS,
        PRERELEASE_SPECS,
        QUICK_SPEC,
        GEM,
        /** {@code api/v1/gems}: {@code gem push}. */
        API_GEMS,
        /** {@code api/v1/gems/yank}: {@code gem yank}. */
        API_YANK,
        /** {@code api/v1/gems/unyank}. */
        API_UNYANK,
        /** {@code api/v1/api_key}: {@code gem signin}. */
        API_KEY,
        /** {@code api/v1/gems/<name>.json}. */
        API_GEM,
        /** {@code api/v1/versions/<name>.json}. */
        API_VERSIONS,
        /** {@code api/v1/versions/<name>/latest.json}. */
        API_LATEST,
        /** {@code api/v1/dependencies}: the dependency API rubygems.org retired; answered with 404. */
        API_DEPENDENCIES,
        /** A path that cannot be meant seriously (backslashes, dot segments, control characters). */
        INVALID,
        UNKNOWN;

        /** Mutable index documents, as opposed to immutable {@code .gem} files and quick specs. */
        public boolean isMetadata() {
            return switch (this) {
                case VERSIONS, NAMES, INFO, SPECS, LATEST_SPECS, PRERELEASE_SPECS, API_GEM, API_VERSIONS, API_LATEST -> true;
                default -> false;
            };
        }
    }

    private static final String API = "api/v1/";

    public static GemPath parse(String path) {
        String p = path == null ? "" : path;
        if (!isPlausible(p)) {
            return new GemPath(Kind.INVALID, p, null, null);
        }
        switch (p) {
            case "" -> {
                return of(Kind.ROOT, p);
            }
            case CompactIndex.VERSIONS -> {
                return of(Kind.VERSIONS, p);
            }
            case CompactIndex.NAMES -> {
                return of(Kind.NAMES, p);
            }
            case SpecsIndex.SPECS -> {
                return of(Kind.SPECS, p);
            }
            case SpecsIndex.LATEST_SPECS -> {
                return of(Kind.LATEST_SPECS, p);
            }
            case SpecsIndex.PRERELEASE_SPECS -> {
                return of(Kind.PRERELEASE_SPECS, p);
            }
            case API + "gems", API + "gems/" -> {
                return of(Kind.API_GEMS, p);
            }
            case API + "gems/yank" -> {
                return of(Kind.API_YANK, p);
            }
            case API + "gems/unyank" -> {
                return of(Kind.API_UNYANK, p);
            }
            case API + "api_key", API + "api_key.json", API + "api_key.yaml" -> {
                return of(Kind.API_KEY, p);
            }
            case API + "dependencies", API + "dependencies.json" -> {
                return of(Kind.API_DEPENDENCIES, p);
            }
            default -> {
                // handled below
            }
        }
        if (p.startsWith(CompactIndex.INFO_PREFIX)) {
            return named(Kind.INFO, p, p.substring(CompactIndex.INFO_PREFIX.length()));
        }
        if (p.startsWith(GemNames.GEMS_PREFIX) && p.endsWith(GemNames.GEM_SUFFIX)) {
            return file(Kind.GEM, p, p.substring(GemNames.GEMS_PREFIX.length(), p.length() - GemNames.GEM_SUFFIX.length()));
        }
        if (p.startsWith(GemNames.QUICK_PREFIX) && p.endsWith(GemNames.QUICK_SUFFIX)) {
            return file(Kind.QUICK_SPEC, p,
                    p.substring(GemNames.QUICK_PREFIX.length(), p.length() - GemNames.QUICK_SUFFIX.length()));
        }
        if (p.startsWith(API + "gems/") && p.endsWith(".json")) {
            return named(Kind.API_GEM, p, p.substring((API + "gems/").length(), p.length() - ".json".length()));
        }
        if (p.startsWith(API + "versions/") && p.endsWith("/latest.json")) {
            return named(Kind.API_LATEST, p, p.substring((API + "versions/").length(), p.length() - "/latest.json".length()));
        }
        if (p.startsWith(API + "versions/") && p.endsWith(".json")) {
            return named(Kind.API_VERSIONS, p, p.substring((API + "versions/").length(), p.length() - ".json".length()));
        }
        return of(Kind.UNKNOWN, p);
    }

    public boolean isMetadata() {
        return kind.isMetadata();
    }

    private static GemPath of(Kind kind, String path) {
        return new GemPath(kind, path, null, null);
    }

    /** A per-gem document; a name RubyGems would never accept cannot exist, so it is simply unknown. */
    private static GemPath named(Kind kind, String path, String name) {
        return GemNames.isValidName(name) ? new GemPath(kind, path, name, null) : of(Kind.UNKNOWN, path);
    }

    private static GemPath file(Kind kind, String path, String fullName) {
        return GemNames.isSafeFullName(fullName) && !fullName.contains("/")
                ? new GemPath(kind, path, null, fullName)
                : of(Kind.UNKNOWN, path);
    }

    /** No backslashes, no empty or dot segments, no control characters. */
    private static boolean isPlausible(String p) {
        if (p.indexOf('\\') >= 0 || p.startsWith("/") || p.contains("//")) {
            return false;
        }
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        for (String segment : p.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
