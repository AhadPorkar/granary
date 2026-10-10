package dev.granary.format.rubygems;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The compact index Bundler (and {@code gem install} since RubyGems 3.0) resolves against:
 * <ul>
 *   <li>{@code versions}: {@code created_at: …}, {@code ---}, then {@code <name> <v1,v2,…> <md5 of info/<name>>} per
 *       line. The file is append-only on rubygems.org: a later line adds versions (or removes them with a {@code -}
 *       prefix) and its checksum replaces the earlier one.</li>
 *   <li>{@code info/<name>}: {@code ---}, then one line per version:
 *       {@code <version>[-<platform>] <dep>:<req>&<req>,…|checksum:<sha256>,ruby:<req>,rubygems:<req>}.</li>
 *   <li>{@code names}: {@code ---}, then every gem name.</li>
 * </ul>
 */
public final class CompactIndex {

    public static final String VERSIONS = "versions";
    public static final String NAMES = "names";
    public static final String INFO_PREFIX = "info/";
    public static final String SEPARATOR = "---";

    private CompactIndex() {
    }

    // --- info ---------------------------------------------------------------------------------------

    /** The info line of one version, in the exact shape rubygems.org writes it. */
    public static String infoLine(GemRecord r) {
        StringBuilder line = new StringBuilder(GemNames.versionKey(r.version(), r.platform())).append(' ');
        TreeMap<String, List<String>> deps = new TreeMap<>();
        for (GemRecord.Dependency d : r.dependencies()) {
            if (d.runtime()) {
                deps.computeIfAbsent(d.name(), k -> new ArrayList<>()).add(requirement(d.requirement()));
            }
        }
        boolean first = true;
        for (Map.Entry<String, List<String>> e : deps.entrySet()) {
            if (!first) {
                line.append(',');
            }
            first = false;
            line.append(e.getKey()).append(':').append(String.join("&", e.getValue()));
        }
        line.append("|checksum:").append(r.sha256());
        String ruby = requirement(r.requiredRubyVersion());
        if (!ruby.equals(">= 0")) {
            line.append(",ruby:").append(ruby);
        }
        String rubygems = requirement(r.requiredRubygemsVersion());
        if (!rubygems.equals(">= 0")) {
            line.append(",rubygems:").append(rubygems);
        }
        if (r.createdAt() != null) {
            line.append(",created_at:").append(timestamp(r.createdAt()));
        }
        return line.toString();
    }

    private static String requirement(String requirement) {
        try {
            return GemRequirement.parse(requirement == null ? "" : requirement).toCompactIndex();
        } catch (IllegalArgumentException e) {
            return ">= 0";
        }
    }

    /** {@code info/<name>} of the given versions, in the order given. */
    public static String info(Collection<GemRecord> versions) {
        StringBuilder doc = new StringBuilder(SEPARATOR).append('\n');
        for (GemRecord r : versions) {
            doc.append(infoLine(r)).append('\n');
        }
        return doc.toString();
    }

    /**
     * Joins several {@code info/<name>} documents of one gem: every version line of the first document, then the
     * lines of later documents for versions (and platforms) the earlier ones lack.
     */
    public static String mergeInfo(List<String> documents) {
        Map<String, String> lines = new LinkedHashMap<>();
        for (String doc : documents) {
            for (String line : lines(doc)) {
                lines.putIfAbsent(lineKey(line), line);
            }
        }
        StringBuilder merged = new StringBuilder(SEPARATOR).append('\n');
        for (String line : lines.values()) {
            merged.append(line).append('\n');
        }
        return merged.toString();
    }

    /** The {@code version[-platform]} an info line describes. */
    static String lineKey(String line) {
        int space = line.indexOf(' ');
        int bar = line.indexOf('|');
        int end = space < 0 ? (bar < 0 ? line.length() : bar) : bar < 0 ? space : Math.min(space, bar);
        return line.substring(0, end);
    }

    /** The non-empty lines after the {@code ---} separator (all lines when there is none). */
    public static List<String> lines(String document) {
        List<String> all = document.lines().toList();
        int separator = all.indexOf(SEPARATOR);
        List<String> body = separator >= 0 ? all.subList(separator + 1, all.size()) : all;
        List<String> result = new ArrayList<>(body.size());
        for (String line : body) {
            if (!line.isBlank()) {
                result.add(line);
            }
        }
        return result;
    }

    // --- names --------------------------------------------------------------------------------------

    public static String names(Collection<String> names) {
        StringBuilder doc = new StringBuilder(SEPARATOR).append('\n');
        for (String name : new TreeSet<>(names)) {
            doc.append(name).append('\n');
        }
        return doc.toString();
    }

    /** The gem names of a {@code names} document. */
    public static Set<String> parseNames(String document) {
        return new LinkedHashSet<>(lines(document));
    }

    // --- versions -----------------------------------------------------------------------------------

    /**
     * What a {@code versions} file says about one gem once all its lines are applied.
     *
     * @param name     gem name
     * @param versions the current {@code version[-platform]} keys, oldest first
     * @param md5      the checksum of the gem's info file (from the last line naming the gem)
     */
    public record Entry(String name, LinkedHashSet<String> versions, String md5) {
    }

    /** A complete {@code versions} file: the header, then one line per entry. */
    public static String versions(Instant createdAt, Collection<Entry> entries) {
        StringBuilder doc = new StringBuilder("created_at: ").append(timestamp(createdAt)).append('\n')
                .append(SEPARATOR).append('\n');
        for (Entry e : entries) {
            if (!e.versions().isEmpty()) {
                doc.append(e.name()).append(' ').append(String.join(",", e.versions())).append(' ').append(e.md5()).append('\n');
            }
        }
        return doc.toString();
    }

    /** Reads a {@code versions} file, applying additions and {@code -} removals in order. */
    public static Map<String, Entry> parseVersions(InputStream in) throws IOException {
        Map<String, Entry> entries = new LinkedHashMap<>();
        forEachVersionsLine(in, null, (name, versions, md5) -> apply(entries, name, versions, md5));
        return entries;
    }

    private static void apply(Map<String, Entry> entries, String name, String versions, String md5) {
        Entry entry = entries.computeIfAbsent(name, n -> new Entry(n, new LinkedHashSet<>(), ""));
        for (String v : versions.split(",")) {
            if (v.isEmpty()) {
                continue;
            }
            if (v.startsWith("-")) {
                entry.versions().remove(v.substring(1));
            } else {
                entry.versions().add(v);
            }
        }
        if (md5 != null) {
            entries.put(name, new Entry(name, entry.versions(), md5));
        }
    }

    @FunctionalInterface
    interface LineConsumer {
        void accept(String name, String versions, String md5) throws IOException;
    }

    /**
     * Calls {@code consumer} for every gem line of a {@code versions} file, copying every byte read to {@code copy}
     * when it is not {@code null}. Returns whether the input ended with a newline.
     */
    static boolean forEachVersionsLine(InputStream in, OutputStream copy, LineConsumer consumer) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(in, 64 * 1024);
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        boolean body = false;
        boolean endsWithNewline = true;
        int b;
        while (true) {
            b = buffered.read();
            if (b < 0 || b == '\n') {
                if (b < 0 && line.size() == 0) {
                    break;
                }
                if (copy != null) {
                    line.writeTo(copy);
                    if (b == '\n') {
                        copy.write('\n');
                    }
                }
                endsWithNewline = b == '\n';
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                if (text.endsWith("\r")) {
                    text = text.substring(0, text.length() - 1);
                }
                if (!body) {
                    body = text.equals(SEPARATOR);
                } else if (!text.isBlank()) {
                    String[] parts = text.trim().split(" +");
                    if (parts.length >= 2) {
                        consumer.accept(parts[0], parts[1], parts.length >= 3 ? parts[2] : null);
                    }
                }
                if (b < 0) {
                    break;
                }
            } else {
                line.write(b);
            }
        }
        return endsWithNewline;
    }

    /**
     * Writes the union of a base {@code versions} file and the entries of other repositories: the base is copied
     * through unchanged (it may be the 25 MB file of rubygems.org, append-only, with its own history) and a line is
     * appended for every gem whose versions or info checksum the others change.
     *
     * @param base   the biggest member's {@code versions} file
     * @param others the entries of the other members, already merged among themselves
     * @param merged for a gem present in the base and elsewhere, the md5 of the merged info file; may return
     *               {@code null} when it cannot be computed, which keeps the base checksum
     */
    public static void mergeVersions(InputStream base, Map<String, Entry> others, Function<String, String> merged,
                                     OutputStream out) throws IOException {
        Map<String, Entry> inBase = new LinkedHashMap<>();
        boolean newline = forEachVersionsLine(base, out, (name, versions, md5) -> {
            if (others.containsKey(name)) {
                apply(inBase, name, versions, md5);
            }
        });
        if (!newline) {
            out.write('\n');
        }
        for (Entry other : new TreeMap<>(others).values()) {
            Entry b = inBase.get(other.name());
            if (other.versions().isEmpty()) {
                continue;
            }
            if (b == null) {
                writeLine(out, other.name(), other.versions(), other.md5());
                continue;
            }
            LinkedHashSet<String> added = new LinkedHashSet<>(other.versions());
            added.removeAll(b.versions());
            String md5 = merged.apply(other.name());
            if (md5 == null) {
                md5 = b.md5();
            }
            if (added.isEmpty()) {
                if (md5.equals(b.md5())) {
                    continue;
                }
                // a line must name a version; repeating a present one only updates the checksum
                added.add(b.versions().isEmpty() ? other.versions().iterator().next() : last(b.versions()));
            }
            writeLine(out, other.name(), added, md5);
        }
    }

    private static String last(LinkedHashSet<String> set) {
        String last = null;
        for (String s : set) {
            last = s;
        }
        return last;
    }

    private static void writeLine(OutputStream out, String name, Collection<String> versions, String md5) throws IOException {
        out.write((name + " " + String.join(",", versions) + " " + md5 + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Merges the entries of several members (first member first): versions are joined, and a gem known to more
     * than one member gets its checksum from {@code merged}.
     */
    public static Map<String, Entry> union(List<Map<String, Entry>> members, Function<String, String> merged) {
        Map<String, Entry> result = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Entry> member : members) {
            for (Entry e : member.values()) {
                if (e.versions().isEmpty()) {
                    continue;
                }
                counts.merge(e.name(), 1, Integer::sum);
                Entry existing = result.get(e.name());
                if (existing == null) {
                    result.put(e.name(), new Entry(e.name(), new LinkedHashSet<>(e.versions()), e.md5()));
                } else {
                    existing.versions().addAll(e.versions());
                }
            }
        }
        for (Map.Entry<String, Integer> c : counts.entrySet()) {
            if (c.getValue() > 1) {
                String md5 = merged.apply(c.getKey());
                if (md5 != null) {
                    Entry e = result.get(c.getKey());
                    result.put(c.getKey(), new Entry(e.name(), e.versions(), md5));
                }
            }
        }
        return result;
    }

    // --- helpers ------------------------------------------------------------------------------------

    public static String md5(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String md5(String text) {
        return md5(text.getBytes(StandardCharsets.UTF_8));
    }

    /** RFC 3339 in UTC with second precision, as rubygems.org writes it. */
    static String timestamp(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }
}
