package dev.granary.format.swift;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Semantic Versioning 2.0 version number, the only kind of release version the registry protocol knows,
 * ordered by SemVer precedence. Versions with build metadata that differ only in it have equal precedence;
 * they are ordered by the build metadata text so the order is total and stable.
 */
public final class SwiftVersion implements Comparable<SwiftVersion> {

    static final int MAX_LENGTH = 128;

    private static final String NUMERIC = "0|[1-9]\\d*";
    private static final String PRERELEASE_ID = "0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*";
    private static final Pattern SEMVER = Pattern.compile(
            "(" + NUMERIC + ")\\.(" + NUMERIC + ")\\.(" + NUMERIC + ")"
                    + "(?:-((?:" + PRERELEASE_ID + ")(?:\\.(?:" + PRERELEASE_ID + "))*))?"
                    + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?");

    /** Valid versions by precedence, highest last; anything unparsable sorts after them in plain text order. */
    public static final Comparator<String> ORDER = (a, b) -> {
        Optional<SwiftVersion> va = parse(a);
        Optional<SwiftVersion> vb = parse(b);
        if (va.isPresent() && vb.isPresent()) {
            return va.get().compareTo(vb.get());
        }
        if (va.isPresent() != vb.isPresent()) {
            return va.isPresent() ? -1 : 1;
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    };

    private final String text;
    private final String major;
    private final String minor;
    private final String patch;
    private final List<String> prerelease;
    private final String build;

    private SwiftVersion(String text, String major, String minor, String patch, List<String> prerelease, String build) {
        this.text = text;
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
        this.build = build;
    }

    public static Optional<SwiftVersion> parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        Matcher m = SEMVER.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        List<String> pre = m.group(4) == null ? List.of() : List.of(m.group(4).split("\\."));
        return Optional.of(new SwiftVersion(text, m.group(1), m.group(2), m.group(3), pre, m.group(5)));
    }

    public static boolean isValid(String text) {
        return parse(text).isPresent();
    }

    /**
     * Whether the version text ends in {@code .zip} or {@code .json} (a pre-release or build identifier named
     * so). Such a version cannot be told apart from the archive and {@code .json} endpoints of a shorter
     * version, so hosted repositories refuse to publish it.
     */
    public static boolean isAmbiguousInPaths(String text) {
        return text != null && (text.endsWith(".zip") || text.endsWith(".json"));
    }

    public String text() {
        return text;
    }

    @Override
    public int compareTo(SwiftVersion o) {
        int c = compareNumeric(major, o.major);
        if (c == 0) {
            c = compareNumeric(minor, o.minor);
        }
        if (c == 0) {
            c = compareNumeric(patch, o.patch);
        }
        if (c == 0) {
            c = comparePrerelease(prerelease, o.prerelease);
        }
        if (c == 0) {
            c = build == null ? (o.build == null ? 0 : -1) : (o.build == null ? 1 : build.compareTo(o.build));
        }
        return c;
    }

    private static int comparePrerelease(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.isEmpty() == b.isEmpty() ? 0 : (a.isEmpty() ? 1 : -1);
        }
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            String x = a.get(i);
            String y = b.get(i);
            boolean xNum = isNumeric(x);
            boolean yNum = isNumeric(y);
            int c;
            if (xNum && yNum) {
                c = compareNumeric(x, y);
            } else if (xNum != yNum) {
                c = xNum ? -1 : 1;
            } else {
                c = x.compareTo(y);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static boolean isNumeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /** Numeric identifiers without leading zeros: the longer one is larger, equal lengths compare as text. */
    private static int compareNumeric(String a, String b) {
        return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SwiftVersion v && v.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
