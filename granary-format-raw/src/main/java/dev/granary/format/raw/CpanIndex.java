package dev.granary.format.raw;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parts of the CPAN mirror layout a hosted repository must produce itself: which distribution a path under
 * {@code authors/id/} is, which modules a distribution provides, and the {@code 02packages.details.txt} index
 * {@code cpanm --mirror-only} resolves module names with.
 */
public final class CpanIndex {

    /** A module name with the version the distribution declares for it ({@code undef} when unknown). */
    public record Module(String name, String version) {
    }

    /** A distribution archive under {@code authors/id/}: {@code file} is its path relative to that directory. */
    public record Dist(String author, String name, String version, String file) {
    }

    /** One line of the index: a module, its version and the distribution file that provides it. */
    public record Provided(String module, String version, String file) {
    }

    public static final String UNDEF = "undef";
    /** Source files larger than this are not scanned for {@code package} statements. */
    static final int MAX_SOURCE = 2 * 1024 * 1024;

    private static final Pattern DIST_PATH = Pattern.compile("^authors/id/[^/]/[^/]{2}/([^/]+)/(.+)$");
    private static final Pattern ARCHIVE = Pattern.compile("^(.+?)\\.(tar\\.gz|tgz|tar\\.bz2|tbz|tar\\.xz|txz|zip)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_VERSION = Pattern.compile("^(.+)-(v?\\d[\\w.]*(?:-TRIAL)?)$");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w:']*)(?:\\s+(v?[\\d._]+))?\\s*[;{]");
    private static final Pattern VERSION = Pattern.compile(
            "(?m)^\\s*(?:our\\s+)?\\$(?:[\\w:]+::)?VERSION\\s*=\\s*(?:'([^']*)'|\"([^\"]*)\"|([\\w.]+))");
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CpanIndex() {
    }

    /** Parses {@code authors/id/A/AB/ABCDE/Some-Dist-1.23.tar.gz}; anything else (CHECKSUMS, READMEs) is empty. */
    public static Optional<Dist> dist(String path) {
        Matcher m = DIST_PATH.matcher(path);
        if (!m.matches()) {
            return Optional.empty();
        }
        Matcher archive = ARCHIVE.matcher(RawPaths.fileName(path));
        if (!archive.matches()) {
            return Optional.empty();
        }
        Matcher nameVersion = NAME_VERSION.matcher(archive.group(1));
        if (!nameVersion.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Dist(m.group(1), nameVersion.group(1), nameVersion.group(2), path.substring("authors/id/".length())));
    }

    /**
     * The modules a distribution archive provides: the {@code provides} map of its {@code META.json} when
     * present, otherwise every {@code package} statement in {@code lib/**.pm}. Archives in formats the JDK
     * cannot decompress (bzip2, xz) yield nothing.
     */
    public static List<Module> modules(InputStream archive, String fileName) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        Map<String, byte[]> sources = new LinkedHashMap<>();
        byte[] meta = null;
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            try (TarReader tar = TarReader.gzip(archive)) {
                TarReader.Entry entry;
                while ((entry = tar.next()) != null) {
                    if (!entry.isFile()) {
                        continue;
                    }
                    String below = entry.nameBelowRoot();
                    if (below.equals("META.json") && entry.size() <= MAX_SOURCE) {
                        meta = tar.readAll(MAX_SOURCE);
                    } else if (isModuleSource(below) && entry.size() <= MAX_SOURCE) {
                        sources.put(below, tar.readAll(MAX_SOURCE));
                    }
                }
            }
        } else if (lower.endsWith(".zip")) {
            try (ZipInputStream zip = new ZipInputStream(archive)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    int slash = entry.getName().indexOf('/');
                    String below = slash < 0 ? "" : entry.getName().substring(slash + 1);
                    if (below.equals("META.json")) {
                        meta = zip.readNBytes(MAX_SOURCE + 1);
                    } else if (isModuleSource(below)) {
                        sources.put(below, zip.readNBytes(MAX_SOURCE + 1));
                    }
                }
            }
        } else {
            return List.of();
        }
        if (meta != null && meta.length <= MAX_SOURCE) {
            List<Module> provided = provides(meta);
            if (!provided.isEmpty()) {
                return provided;
            }
        }
        Map<String, Module> found = new LinkedHashMap<>();
        for (byte[] source : sources.values()) {
            if (source.length > MAX_SOURCE) {
                continue;
            }
            for (Module module : scan(new String(source, StandardCharsets.UTF_8))) {
                found.putIfAbsent(module.name(), module);
            }
        }
        return new ArrayList<>(found.values());
    }

    private static boolean isModuleSource(String below) {
        return below.startsWith("lib/") && below.endsWith(".pm");
    }

    /** The {@code provides} map of a {@code META.json}; malformed JSON yields nothing. */
    static List<Module> provides(byte[] metaJson) {
        JsonNode root;
        try {
            root = JSON.readTree(metaJson);
        } catch (RuntimeException e) {
            return List.of();
        }
        JsonNode provides = root.path("provides");
        if (!provides.isObject()) {
            return List.of();
        }
        List<Module> modules = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : provides.properties()) {
            JsonNode version = entry.getValue().path("version");
            modules.add(new Module(entry.getKey(), version.isValueNode() && !version.isNull() ? version.asString() : UNDEF));
        }
        return modules;
    }

    /** Every {@code package} statement of a Perl source with the file's {@code $VERSION} (or the statement's own). */
    static List<Module> scan(String source) {
        String fileVersion = null;
        Matcher version = VERSION.matcher(source);
        if (version.find()) {
            fileVersion = first(version.group(1), version.group(2), version.group(3));
        }
        List<Module> modules = new ArrayList<>();
        Matcher m = PACKAGE.matcher(source);
        while (m.find()) {
            String name = m.group(1).replace("'", "::");
            if (name.equals("main")) {
                continue;
            }
            String declared = m.group(2);
            modules.add(new Module(name, declared != null ? declared : fileVersion != null ? fileVersion : UNDEF));
        }
        return modules;
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Renders {@code 02packages.details.txt}: the PAUSE header, a blank line, then one sorted line per module. */
    public static byte[] packagesDetails(Collection<Provided> entries, Instant now) {
        List<Provided> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> a.module().compareTo(b.module()));
        int nameWidth = 1;
        int versionWidth = 1;
        for (Provided p : sorted) {
            nameWidth = Math.max(nameWidth, p.module().length());
            versionWidth = Math.max(versionWidth, versionOrUndef(p.version()).length());
        }
        StringBuilder sb = new StringBuilder(512 + sorted.size() * (nameWidth + versionWidth + 48));
        sb.append("File:         02packages.details.txt\n")
                .append("URL:          http://www.perl.com/CPAN/modules/02packages.details.txt\n")
                .append("Description:  Package names found in directory $CPAN/authors/id/\n")
                .append("Columns:      package name, version, path\n")
                .append("Intended-For: Automated fetch routines, namespace documentation.\n")
                .append("Written-By:   Granary\n")
                .append("Line-Count:   ").append(sorted.size()).append('\n')
                .append("Last-Updated: ").append(RFC_1123.format(now)).append("\n\n");
        // "%-<n>s %<v>s  %s\n" without String.format, which is slow for the 250k lines of a merged CPAN index
        for (Provided p : sorted) {
            String version = versionOrUndef(p.version());
            sb.append(p.module());
            pad(sb, nameWidth - p.module().length() + 1 + versionWidth - version.length());
            sb.append(version).append("  ").append(p.file()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void pad(StringBuilder sb, int spaces) {
        for (int i = 0; i < spaces; i++) {
            sb.append(' ');
        }
    }

    /**
     * Merges the indexes of a group's members in member order: the first index that lists a module provides it,
     * so a hosted repository placed first keeps its own modules even when a later upstream has the same name.
     */
    public static List<Provided> merge(List<List<Provided>> indexes) {
        Map<String, Provided> byModule = new LinkedHashMap<>();
        for (List<Provided> index : indexes) {
            for (Provided provided : index) {
                byModule.putIfAbsent(provided.module(), provided);
            }
        }
        return new ArrayList<>(byModule.values());
    }

    /** The module lines of an index as cpanm reads them: everything after the first blank line. */
    public static List<Provided> parsePackagesDetails(String text) {
        List<Provided> result = new ArrayList<>();
        boolean body = false;
        for (String line : text.split("\n")) {
            if (!body) {
                body = line.isBlank();
                continue;
            }
            String[] columns = line.trim().split("\\s+");
            if (columns.length >= 3) {
                result.add(new Provided(columns[0], columns[1], columns[2]));
            }
        }
        return result;
    }

    private static String versionOrUndef(String version) {
        return version == null || version.isBlank() ? UNDEF : version;
    }

    /**
     * Compares Perl versions the way {@code version.pm} does: decimal versions ({@code 1.23} is {@code v1.230})
     * and dotted-decimal ones ({@code v1.2.3}); {@code undef} is lower than everything.
     */
    public static int compareVersions(String a, String b) {
        List<BigInteger> left = components(a);
        List<BigInteger> right = components(b);
        int n = Math.max(left.size(), right.size());
        for (int i = 0; i < n; i++) {
            BigInteger l = i < left.size() ? left.get(i) : BigInteger.ZERO;
            BigInteger r = i < right.size() ? right.get(i) : BigInteger.ZERO;
            int c = l.compareTo(r);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    static List<BigInteger> components(String version) {
        if (version == null || version.isBlank() || version.equals(UNDEF)) {
            return List.of();
        }
        String v = version.trim().replace("_", "");
        int trial = v.indexOf("-TRIAL");
        if (trial >= 0) {
            v = v.substring(0, trial);
        }
        boolean dotted = v.startsWith("v") || v.chars().filter(c -> c == '.').count() >= 2;
        if (v.startsWith("v")) {
            v = v.substring(1);
        }
        List<BigInteger> result = new ArrayList<>();
        try {
            if (dotted) {
                for (String part : v.split("\\.")) {
                    result.add(part.isEmpty() ? BigInteger.ZERO : new BigInteger(part));
                }
            } else {
                int dot = v.indexOf('.');
                String whole = dot < 0 ? v : v.substring(0, dot);
                String fraction = dot < 0 ? "" : v.substring(dot + 1);
                result.add(whole.isEmpty() ? BigInteger.ZERO : new BigInteger(whole));
                while (fraction.length() % 3 != 0) {
                    fraction += "0";
                }
                for (int i = 0; i < fraction.length(); i += 3) {
                    result.add(new BigInteger(fraction.substring(i, i + 3)));
                }
            }
        } catch (NumberFormatException e) {
            return List.of();
        }
        return result;
    }
}
