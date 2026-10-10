package dev.granary.format.helm;

import java.util.regex.Pattern;

/**
 * The paths of a Helm chart repository as Granary serves it, plus the ChartMuseum API:
 *
 * <pre>
 * index.yaml                          the repository index (mutable metadata)
 * charts/&lt;name&gt;-&lt;version&gt;.tgz          a chart archive (immutable)
 * charts/&lt;name&gt;-&lt;version&gt;.tgz.prov     its provenance file
 * charts/&lt;name&gt;/&lt;version&gt;/&lt;file&gt;       the same, addressed by chart and version (used when two upstream
 *                                     charts share a file name)
 * &lt;file&gt;.tgz, &lt;file&gt;.tgz.prov         upload target of PUT (Nexus/Artifactory style), alias on GET
 * artifacthub-repo.yml                Artifact Hub repository metadata
 * api/charts, api/charts/&lt;name&gt;, api/charts/&lt;name&gt;/&lt;version&gt;, api/prov, health   ChartMuseum API
 * </pre>
 *
 * Anything with characters a chart file name cannot contain is {@link Kind#INVALID} (answered with 400), so
 * odd input never reaches storage with a different meaning.
 *
 * @param kind     what the path addresses
 * @param path     the normalised path as requested
 * @param name     chart name for API and alias paths, otherwise {@code null}
 * @param version  chart version for API and alias paths, otherwise {@code null}
 * @param filename file name for chart and provenance paths, otherwise {@code null}
 */
public record HelmPath(Kind kind, String path, String name, String version, String filename) {

    public enum Kind {
        ROOT, INDEX, CHART, PROVENANCE, CHART_ALIAS, PROVENANCE_ALIAS, ROOT_FILE, ARTIFACTHUB,
        API_CHARTS, API_CHART, API_CHART_VERSION, API_PROV, HEALTH, INVALID, UNKNOWN
    }

    public static final String INDEX_FILE = "index.yaml";
    public static final String CHARTS = "charts/";
    public static final String ARCHIVE_SUFFIX = ".tgz";
    public static final String PROV_SUFFIX = ".prov";
    public static final String ARTIFACTHUB_FILE = "artifacthub-repo.yml";

    /** Chart names: what {@code helm create} and every public repository use (letters, digits, {@code . _ -}). */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,249}");
    /** One file name segment; never {@code .} or {@code ..}, never a separator. */
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._+~-]{0,254}");

    public static HelmPath parse(String raw) {
        String path = raw == null ? "" : raw;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            return of(Kind.ROOT, path);
        }
        if (path.contains("\\") || path.contains("//") || path.indexOf('\0') >= 0) {
            return of(Kind.INVALID, path);
        }
        String[] s = path.split("/", -1);
        return switch (s[0]) {
            case INDEX_FILE -> s.length == 1 ? of(Kind.INDEX, path) : of(Kind.UNKNOWN, path);
            case ARTIFACTHUB_FILE -> s.length == 1 ? of(Kind.ARTIFACTHUB, path) : of(Kind.UNKNOWN, path);
            case "health" -> s.length == 1 ? of(Kind.HEALTH, path) : of(Kind.UNKNOWN, path);
            case "charts" -> charts(path, s);
            case "api" -> api(path, s);
            default -> s.length == 1 ? rootFile(path, s[0]) : of(Kind.UNKNOWN, path);
        };
    }

    private static HelmPath charts(String path, String[] s) {
        if (s.length == 2) {
            String file = s[1];
            if (file.isEmpty()) {
                return of(Kind.UNKNOWN, path);
            }
            if (!isSafeFilename(file)) {
                return of(Kind.INVALID, path);
            }
            if (isProvenance(file)) {
                return new HelmPath(Kind.PROVENANCE, path, null, null, file);
            }
            return isArchive(file) ? new HelmPath(Kind.CHART, path, null, null, file) : of(Kind.UNKNOWN, path);
        }
        if (s.length == 4) {
            if (!isValidName(s[1]) || !isSafeFilename(s[2]) || !isSafeFilename(s[3])) {
                return of(Kind.INVALID, path);
            }
            if (isProvenance(s[3])) {
                return new HelmPath(Kind.PROVENANCE_ALIAS, path, s[1], s[2], s[3]);
            }
            return isArchive(s[3]) ? new HelmPath(Kind.CHART_ALIAS, path, s[1], s[2], s[3]) : of(Kind.UNKNOWN, path);
        }
        return of(Kind.UNKNOWN, path);
    }

    private static HelmPath api(String path, String[] s) {
        if (s.length == 2 && s[1].equals("prov")) {
            return of(Kind.API_PROV, path);
        }
        if (s.length < 2 || !s[1].equals("charts")) {
            return of(Kind.UNKNOWN, path);
        }
        return switch (s.length) {
            case 2 -> of(Kind.API_CHARTS, path);
            case 3 -> s[2].isEmpty() ? of(Kind.API_CHARTS, path)
                    : isValidName(s[2]) ? new HelmPath(Kind.API_CHART, path, s[2], null, null) : of(Kind.INVALID, path);
            case 4 -> {
                if (s[3].isEmpty()) {
                    yield isValidName(s[2]) ? new HelmPath(Kind.API_CHART, path, s[2], null, null) : of(Kind.INVALID, path);
                }
                yield isValidName(s[2]) && isSafeFilename(s[3])
                        ? new HelmPath(Kind.API_CHART_VERSION, path, s[2], s[3], null)
                        : of(Kind.INVALID, path);
            }
            default -> of(Kind.UNKNOWN, path);
        };
    }

    private static HelmPath rootFile(String path, String file) {
        if (!isSafeFilename(file)) {
            return of(Kind.INVALID, path);
        }
        return isArchive(file) || isProvenance(file) ? new HelmPath(Kind.ROOT_FILE, path, null, null, file) : of(Kind.UNKNOWN, path);
    }

    private static HelmPath of(Kind kind, String path) {
        return new HelmPath(kind, path, null, null, null);
    }

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static boolean isSafeFilename(String file) {
        return file != null && FILE.matcher(file).matches();
    }

    public static boolean isArchive(String file) {
        return file.endsWith(ARCHIVE_SUFFIX) && file.length() > ARCHIVE_SUFFIX.length();
    }

    public static boolean isProvenance(String file) {
        return file.endsWith(ARCHIVE_SUFFIX + PROV_SUFFIX) && file.length() > (ARCHIVE_SUFFIX + PROV_SUFFIX).length();
    }

    /** The conventional archive file name, {@code <name>-<version>.tgz}, as {@code helm package} writes it. */
    public static String archiveName(String name, String version) {
        return name + "-" + version + ARCHIVE_SUFFIX;
    }

    /** Where a hosted repository keeps a chart archive. */
    public static String archivePath(String name, String version) {
        return CHARTS + archiveName(name, version);
    }

    /** The addressing of a chart by name and version, valid in every repository type. */
    public static String aliasPath(String name, String version, String filename) {
        return CHARTS + name + "/" + version + "/" + filename;
    }

    /** Whether this path names a provenance file rather than an archive. */
    public boolean isProvenancePath() {
        return kind == Kind.PROVENANCE || kind == Kind.PROVENANCE_ALIAS
                || (kind == Kind.ROOT_FILE && isProvenance(filename));
    }

    /** Index documents change with every upload or upstream refresh; archives never do. */
    public boolean isMetadata() {
        return kind == Kind.INDEX || kind == Kind.ARTIFACTHUB;
    }
}
