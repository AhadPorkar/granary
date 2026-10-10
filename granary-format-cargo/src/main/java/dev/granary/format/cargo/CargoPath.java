package dev.granary.format.cargo;

/**
 * What a repository-relative request path addresses.
 *
 * <ul>
 *   <li>{@code index/config.json} – the registry configuration (generated);</li>
 *   <li>{@code index/<dir>/<name>} – a sparse index file;</li>
 *   <li>{@code api/v1/crates} – search; {@code api/v1/crates/new} – publish;</li>
 *   <li>{@code api/v1/crates/<name>/<version>/download|yank|unyank};</li>
 *   <li>{@code api/v1/crates/<name>/owners};</li>
 *   <li>{@code me} – where {@code cargo login} sends people for a token; the empty path – a landing page.</li>
 * </ul>
 *
 * @param kind    what the path is
 * @param name    the crate name as written in the path, for crate-specific kinds
 * @param version the version, for download, yank and unyank
 */
record CargoPath(Kind kind, String name, String version) {

    enum Kind { ROOT, ME, CONFIG, INDEX, SEARCH, PUBLISH, DOWNLOAD, YANK, UNYANK, OWNERS, INVALID, UNKNOWN }

    private static final String API = "api/v1/crates";

    static CargoPath parse(String path) {
        if (path == null || path.isEmpty()) {
            return of(Kind.ROOT);
        }
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0 || path.contains("//")) {
            return of(Kind.INVALID);
        }
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (p.equals("me")) {
            return of(Kind.ME);
        }
        if (p.equals("index/config.json")) {
            return of(Kind.CONFIG);
        }
        if (p.startsWith(CrateNames.INDEX_PREFIX)) {
            return index(p);
        }
        if (p.equals(API)) {
            return of(Kind.SEARCH);
        }
        if (p.equals(API + "/new")) {
            return of(Kind.PUBLISH);
        }
        if (p.startsWith(API + "/")) {
            return api(p.substring(API.length() + 1).split("/", -1));
        }
        return of(Kind.UNKNOWN);
    }

    private static CargoPath index(String p) {
        String[] segments = p.substring(CrateNames.INDEX_PREFIX.length()).split("/", -1);
        if (segments.length < 2 || segments.length > 3) {
            return of(Kind.UNKNOWN);
        }
        String name = segments[segments.length - 1];
        if (!CrateNames.isValidLookup(name)) {
            return of(Kind.INVALID);
        }
        // the directories must be the ones cargo derives from the name, so one crate has one URL
        String dirs = p.substring(CrateNames.INDEX_PREFIX.length(), p.length() - name.length() - 1);
        if (!dirs.equalsIgnoreCase(CrateNames.indexDir(name))) {
            return of(Kind.UNKNOWN);
        }
        return new CargoPath(Kind.INDEX, name, null);
    }

    private static CargoPath api(String[] s) {
        if (s.length == 2 && s[1].equals("owners")) {
            return crate(Kind.OWNERS, s[0], null);
        }
        if (s.length == 3) {
            Kind kind = switch (s[2]) {
                case "download" -> Kind.DOWNLOAD;
                case "yank" -> Kind.YANK;
                case "unyank" -> Kind.UNYANK;
                default -> null;
            };
            if (kind != null) {
                return crate(kind, s[0], s[1]);
            }
        }
        return of(Kind.UNKNOWN);
    }

    private static CargoPath crate(Kind kind, String name, String version) {
        if (!CrateNames.isValidLookup(name) || (version != null && !CrateNames.isValidUrlVersion(version))) {
            return of(Kind.INVALID);
        }
        return new CargoPath(kind, name, version);
    }

    private static CargoPath of(Kind kind) {
        return new CargoPath(kind, null, null);
    }

    /** Index documents change with every publish and yank; crate files never do. */
    boolean isMetadata() {
        return kind == Kind.CONFIG || kind == Kind.INDEX || kind == Kind.SEARCH || kind == Kind.OWNERS;
    }
}
