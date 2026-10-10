package dev.granary.format.terraform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code SHA256SUMS} document a provider release ships: one {@code <hex digest>  <file name>} line per
 * archive, in the format {@code sha256sum} writes (two spaces, or {@code  *} for binary mode). Terraform
 * verifies a downloaded archive against the line that names it and the GPG signature against the whole
 * document, so the bytes are stored verbatim and only read here.
 */
public final class Sha256Sums {

    public record Entry(String sha256, String filename) {
    }

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-fA-F]{64}$");

    private final List<Entry> entries;
    private final Map<String, String> byFilename;
    private final int malformedLines;

    private Sha256Sums(List<Entry> entries, int malformedLines) {
        this.entries = Collections.unmodifiableList(entries);
        Map<String, String> map = new LinkedHashMap<>();
        for (Entry e : entries) {
            map.putIfAbsent(e.filename(), e.sha256());
        }
        this.byFilename = Collections.unmodifiableMap(map);
        this.malformedLines = malformedLines;
    }

    /** Parses leniently: blank lines are skipped, malformed lines are counted but do not fail the parse. */
    public static Sha256Sums parse(String text) {
        List<Entry> entries = new ArrayList<>();
        int malformed = 0;
        if (text == null) {
            return new Sha256Sums(entries, 0);
        }
        for (String rawLine : text.split("\r?\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space < 0) {
                malformed++;
                continue;
            }
            String digest = line.substring(0, space);
            String name = line.substring(space + 1).strip();
            if (name.startsWith("*")) {
                name = name.substring(1);
            }
            if (!HEX64.matcher(digest).matches() || name.isEmpty() || name.contains("/") || name.contains("\\")) {
                malformed++;
                continue;
            }
            entries.add(new Entry(digest.toLowerCase(Locale.ROOT), name));
        }
        return new Sha256Sums(entries, malformed);
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int malformedLines() {
        return malformedLines;
    }

    /** The lower-case hex digest listed for {@code filename}, if any. */
    public Optional<String> digestOf(String filename) {
        return Optional.ofNullable(byFilename.get(filename));
    }

    /** Renders entries back into the canonical two-space format. */
    public static String render(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.sha256()).append("  ").append(e.filename()).append('\n');
        }
        return sb.toString();
    }
}
