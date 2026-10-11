package dev.granary.format.raw;

import dev.granary.core.storage.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CRAN's {@code PACKAGES} index: Debian control format records (DCF) with one record per package, built from
 * the {@code DESCRIPTION} file inside each source tarball.
 */
public final class CranPackages {

    /** The fields {@code tools::write_PACKAGES} copies, in its order; others in a DESCRIPTION are dropped. */
    static final List<String> FIELDS = List.of("Package", "Version", "Priority", "Depends", "Imports", "LinkingTo",
            "Suggests", "Enhances", "License", "License_is_FOSS", "License_restricts_use", "OS_type", "Archs",
            "MD5sum", "NeedsCompilation", "Path");

    private static final Pattern SOURCE = Pattern.compile("^src/contrib/([A-Za-z][A-Za-z0-9.]*)_(\\d+(?:[.-]\\d+)*)\\.tar\\.gz$");
    private static final Pattern BINARY = Pattern.compile("^bin/.+/([A-Za-z][A-Za-z0-9.]*)_(\\d+(?:[.-]\\d+)*)\\.(?:zip|tgz)$");
    private static final Pattern ARCHIVED = Pattern.compile("^src/contrib/Archive/([^/]+)/([^/]+)$");
    /** An older source package under its package's archive directory, as CRAN keeps it. */
    private static final Pattern ARCHIVED_SOURCE =
            Pattern.compile("^src/contrib/Archive/([A-Za-z][A-Za-z0-9.]*)/\\1_(\\d+(?:[.-]\\d+)*)\\.tar\\.gz$");
    private static final Pattern FIELD = Pattern.compile("^([A-Za-z0-9_.@-]+):[ \\t]?(.*)$");
    static final int MAX_DESCRIPTION = 1024 * 1024;

    private CranPackages() {
    }

    public static boolean isSourcePackage(String path) {
        return SOURCE.matcher(path).matches();
    }

    /** {@code src/contrib/<pkg>_<ver>.tar.gz} and {@code bin/<os>/contrib/<r>/<pkg>_<ver>.zip|tgz}. */
    public static Optional<StorageService.ComponentCoordinates> coordinates(String path) {
        Matcher m = SOURCE.matcher(path);
        if (!m.matches()) {
            m = BINARY.matcher(path);
        }
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new StorageService.ComponentCoordinates("", m.group(1), m.group(2)));
    }

    /**
     * {@code src/contrib/Archive/<pkg>/<pkg>_<ver>.tar.gz}, where CRAN keeps every version but the current one: the
     * same coordinates as {@code src/contrib/<pkg>_<ver>.tar.gz}, so one package version is one component whether it
     * was downloaded while current or from the archive.
     */
    public static Optional<StorageService.ComponentCoordinates> archivedCoordinates(String path) {
        Matcher m = ARCHIVED_SOURCE.matcher(path);
        return m.matches()
                ? Optional.of(new StorageService.ComponentCoordinates("", m.group(1), m.group(2)))
                : Optional.empty();
    }

    /** For {@code src/contrib/Archive/<pkg>/<file>}: the file name, which the live tarball is stored under. */
    public static Optional<String> archivedFile(String path) {
        Matcher m = ARCHIVED.matcher(path);
        return m.matches() ? Optional.of(m.group(2)) : Optional.empty();
    }

    /** The first DCF record of a text (a DESCRIPTION file). Continuation lines are joined with newlines. */
    public static Map<String, String> parseDcf(String text) {
        List<Map<String, String>> records = parseRecords(text);
        return records.isEmpty() ? new LinkedHashMap<>() : records.get(0);
    }

    /** Every record of a DCF text (a PACKAGES file); records are separated by blank lines. */
    public static List<Map<String, String>> parseRecords(String text) {
        List<Map<String, String>> records = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();
        String lastKey = null;
        for (String raw : text.split("\r?\n")) {
            if (raw.isBlank()) {
                if (!current.isEmpty()) {
                    records.add(current);
                    current = new LinkedHashMap<>();
                }
                lastKey = null;
                continue;
            }
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lastKey != null) {
                current.put(lastKey, current.get(lastKey) + "\n" + raw.trim());
                continue;
            }
            Matcher m = FIELD.matcher(raw);
            if (m.matches()) {
                lastKey = m.group(1);
                current.put(lastKey, m.group(2).trim());
            }
        }
        if (!current.isEmpty()) {
            records.add(current);
        }
        return records;
    }

    /** Renders records as a PACKAGES file, keeping only the fields CRAN publishes and R reads. */
    public static String write(List<Map<String, String>> records) {
        StringBuilder sb = new StringBuilder(records.size() * 256);
        for (Map<String, String> record : records) {
            boolean any = false;
            for (String field : FIELDS) {
                String value = record.get(field);
                if (value == null || value.isBlank()) {
                    continue;
                }
                any = true;
                sb.append(field).append(": ").append(value.replace("\n", "\n        ")).append('\n');
            }
            if (any) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Renders records with every field they carry, in their own order (merged upstream indexes). */
    public static String writeAll(List<Map<String, String>> records) {
        StringBuilder sb = new StringBuilder(records.size() * 320);
        for (Map<String, String> record : records) {
            if (record.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String> field : record.entrySet()) {
                sb.append(field.getKey()).append(": ").append(field.getValue().replace("\n", "\n        ")).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Merges the {@code PACKAGES} records of a group's members in member order: the union of package versions,
     * the first member that lists a version providing its record. R keeps the highest version of a package.
     */
    public static List<Map<String, String>> merge(List<List<Map<String, String>>> indexes) {
        Map<String, Map<String, String>> byVersion = new LinkedHashMap<>();
        for (List<Map<String, String>> index : indexes) {
            for (Map<String, String> record : index) {
                String name = record.get("Package");
                if (name == null || name.isBlank()) {
                    continue;
                }
                byVersion.putIfAbsent(name + "\u0000" + record.getOrDefault("Version", ""), record);
            }
        }
        return new ArrayList<>(byVersion.values());
    }

    /** Reads {@code <pkg>/DESCRIPTION} from a source tarball; empty when the tarball has none. */
    public static Optional<Map<String, String>> description(InputStream tarGz) throws IOException {
        try (TarReader tar = TarReader.gzip(tarGz)) {
            TarReader.Entry entry;
            while ((entry = tar.next()) != null) {
                if (entry.isFile() && entry.nameBelowRoot().equals("DESCRIPTION") && entry.size() <= MAX_DESCRIPTION) {
                    return Optional.of(parseDcf(new String(tar.readAll(MAX_DESCRIPTION), StandardCharsets.UTF_8)));
                }
            }
        }
        return Optional.empty();
    }

    /** R package versions: numeric components separated by dots or dashes. */
    public static int compareVersions(String a, String b) {
        String[] left = a.split("[.-]");
        String[] right = b.split("[.-]");
        int n = Math.max(left.length, right.length);
        for (int i = 0; i < n; i++) {
            long l = i < left.length ? parse(left[i]) : 0;
            long r = i < right.length ? parse(right[i]) : 0;
            if (l != r) {
                return Long.compare(l, r);
            }
        }
        return 0;
    }

    private static long parse(String component) {
        try {
            return Long.parseLong(component);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
