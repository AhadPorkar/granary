package dev.granary.format.raw;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A list of path globs, the notation {@code raw.metadataPatterns} uses: {@code *} matches within one path
 * segment, {@code ?} one character of a segment, {@code **} any number of segments ({@code **&#47;PACKAGES*}
 * matches at the root and at any depth). Patterns are anchored to the whole repository-relative path and
 * compared case-sensitively.
 */
public final class GlobMatcher {

    public static final GlobMatcher NONE = new GlobMatcher(List.of());

    private final List<String> globs;
    private final List<Pattern> patterns;

    private GlobMatcher(List<String> globs) {
        this.globs = List.copyOf(globs);
        List<Pattern> compiled = new ArrayList<>(globs.size());
        for (String glob : globs) {
            compiled.add(Pattern.compile(toRegex(glob)));
        }
        this.patterns = List.copyOf(compiled);
    }

    /** Parses a comma-separated list; blank entries are ignored, surrounding whitespace is trimmed. */
    public static GlobMatcher of(String commaSeparated) {
        List<String> globs = new ArrayList<>();
        if (commaSeparated != null) {
            for (String part : commaSeparated.split(",")) {
                String glob = part.trim();
                if (!glob.isEmpty()) {
                    globs.add(glob);
                }
            }
        }
        return globs.isEmpty() ? NONE : new GlobMatcher(globs);
    }

    public static GlobMatcher of(List<String> globs) {
        List<String> cleaned = new ArrayList<>();
        for (String glob : globs) {
            if (glob != null && !glob.isBlank()) {
                cleaned.add(glob.trim());
            }
        }
        return cleaned.isEmpty() ? NONE : new GlobMatcher(cleaned);
    }

    public boolean matches(String path) {
        if (path == null) {
            return false;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    public List<String> globs() {
        return globs;
    }

    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    /** Translates one glob into an anchored regular expression. */
    static String toRegex(String glob) {
        String g = glob;
        while (g.startsWith("/")) {
            g = g.substring(1);
        }
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        int n = g.length();
        while (i < n) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < n && g.charAt(i + 1) == '*') {
                    if (i + 2 < n && g.charAt(i + 2) == '/') {
                        sb.append("(?:.*/)?");
                        i += 3;
                    } else {
                        sb.append(".*");
                        i += 2;
                    }
                } else {
                    sb.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                    sb.append('\\');
                }
                sb.append(c);
                i++;
            }
        }
        return sb.append('$').toString();
    }

    @Override
    public String toString() {
        return String.join(",", globs);
    }
}
