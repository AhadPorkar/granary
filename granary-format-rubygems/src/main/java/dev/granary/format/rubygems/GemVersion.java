package dev.granary.format.rubygems;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A RubyGems version with the exact semantics of {@code Gem::Version}: a {@code -} becomes {@code .pre.}, a
 * version with any letter is a prerelease, and comparison works on canonical segments (trailing zeros dropped,
 * letters sorting before numbers), so {@code 1.0 == 1.0.0} and {@code 1.0.a < 1.0}.
 */
public final class GemVersion implements Comparable<GemVersion> {

    /** {@code Gem::Version::ANCHORED_VERSION_PATTERN}. */
    private static final Pattern VALID = Pattern.compile(
            "\\A\\s*([0-9]+(?>\\.[0-9a-zA-Z]+)*(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?)?\\s*\\z");
    private static final Pattern SEGMENT = Pattern.compile("\\d+|[a-zA-Z]+");
    private static final Pattern TRAILING_ZEROS = Pattern.compile("(?<=[a-zA-Z.])[.0]+\\z");
    private static final Pattern ZEROS_BEFORE_LETTER = Pattern.compile("(?<=\\.|\\A)[0.]+(?=[a-zA-Z])");
    private static final int MAX_LENGTH = 128;

    private final String version;
    private final List<Object> canonical;

    private GemVersion(String version) {
        this.version = version;
        this.canonical = canonicalSegments(version, isPrerelease(version));
    }

    /** Parses like {@code Gem::Version.new}; empty means {@code 0}. */
    public static Optional<GemVersion> parse(String raw) {
        if (raw == null || raw.length() > MAX_LENGTH || !VALID.matcher(raw).matches()) {
            return Optional.empty();
        }
        String v = raw.strip();
        if (v.isEmpty()) {
            v = "0";
        }
        return Optional.of(new GemVersion(v.replace("-", ".pre.")));
    }

    public static GemVersion of(String raw) {
        return parse(raw).orElseThrow(() -> new IllegalArgumentException("Malformed version number string " + raw));
    }

    public static boolean isValid(String raw) {
        return parse(raw).isPresent();
    }

    /** {@code Gem::Version#to_s}. */
    @Override
    public String toString() {
        return version;
    }

    public boolean isPrerelease() {
        return isPrerelease(version);
    }

    static boolean isPrerelease(String version) {
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }

    private static List<Object> canonicalSegments(String version, boolean prerelease) {
        String canonical = TRAILING_ZEROS.matcher(version).replaceFirst("");
        if (prerelease) {
            canonical = ZEROS_BEFORE_LETTER.matcher(canonical).replaceFirst("");
        }
        List<Object> segments = new ArrayList<>();
        Matcher m = SEGMENT.matcher(canonical);
        while (m.find()) {
            String s = m.group();
            segments.add(Character.isDigit(s.charAt(0)) ? new BigInteger(s) : s);
        }
        return segments;
    }

    /** {@code Gem::Version#<=>}. */
    @Override
    public int compareTo(GemVersion other) {
        if (version.equals(other.version) || canonical.equals(other.canonical)) {
            return 0;
        }
        int limit = Math.max(canonical.size(), other.canonical.size());
        for (int i = 0; i < limit; i++) {
            Object lhs = i < canonical.size() ? canonical.get(i) : BigInteger.ZERO;
            Object rhs = i < other.canonical.size() ? other.canonical.get(i) : BigInteger.ZERO;
            if (lhs.equals(rhs)) {
                continue;
            }
            if (lhs instanceof String && rhs instanceof BigInteger) {
                return -1;
            }
            if (lhs instanceof BigInteger && rhs instanceof String) {
                return 1;
            }
            if (lhs instanceof BigInteger a && rhs instanceof BigInteger b) {
                return Integer.signum(a.compareTo(b));
            }
            return Integer.signum(compareBytes((String) lhs, (String) rhs));
        }
        return 0;
    }

    /** Ruby compares strings byte by byte; segments are ASCII letters, so char order is the same. */
    private static int compareBytes(String a, String b) {
        return a.compareTo(b);
    }

    /** Equality as {@code Gem::Version#==}: same canonical value, so {@code 1.0} equals {@code 1.0.0}. */
    @Override
    public boolean equals(Object other) {
        return other instanceof GemVersion v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        return canonical.hashCode();
    }

    /** Orders version strings that may not parse: valid ones by {@link GemVersion}, the rest after them as text. */
    public static int compareStrings(String a, String b) {
        Optional<GemVersion> va = parse(a);
        Optional<GemVersion> vb = parse(b);
        if (va.isPresent() && vb.isPresent()) {
            int c = va.get().compareTo(vb.get());
            return c != 0 ? c : a.compareTo(b);
        }
        if (va.isPresent()) {
            return -1;
        }
        if (vb.isPresent()) {
            return 1;
        }
        return a.compareTo(b);
    }
}
