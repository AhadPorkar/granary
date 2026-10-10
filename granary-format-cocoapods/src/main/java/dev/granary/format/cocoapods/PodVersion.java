package dev.granary.format.cocoapods;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A CocoaPods version, compared exactly the way {@code Pod::Version} does it: the RubyGems segment rules
 * ({@code 3.10 > 3.2}, letters make a pre-release, {@code 1.0.a10 > 1.0.a9}) plus semantic-versioning
 * pre-releases with a dash ({@code 1.0.0-beta.1 < 1.0.0}) and build metadata after {@code +}, which is
 * ignored for ordering.
 *
 * <p>Any string can be compared, so version lists from an upstream never break sorting; {@link #isValid}
 * tells whether CocoaPods itself would accept the text.
 */
public final class PodVersion implements Comparable<PodVersion> {

    /** {@code Pod::Version::VERSION_PATTERN}. */
    private static final Pattern VALID = Pattern.compile("[0-9]+(\\.[0-9a-zA-Z\\-]+)*(\\+[0-9a-zA-Z\\-.]+)?");
    private static final Pattern METADATA = Pattern.compile("\\+[0-9a-zA-Z\\-.]+$");
    private static final Pattern SEGMENT = Pattern.compile("[0-9]+|[a-zA-Z]+");
    private static final Pattern PRERELEASE = Pattern.compile("[a-zA-Z\\-]");

    /** Orders version strings as CocoaPods does, lowest first. */
    public static final Comparator<String> ORDER = Comparator.comparing(PodVersion::of);

    private final String text;
    private final List<Object> numeric;
    private final List<Object> prerelease;

    private PodVersion(String text) {
        this.text = text;
        String comparable = METADATA.matcher(text).replaceFirst("");
        List<Object> segments = new ArrayList<>();
        Matcher m = SEGMENT.matcher(comparable);
        while (m.find()) {
            String s = m.group();
            segments.add(Character.isDigit(s.charAt(0)) ? new BigInteger(s) : s);
        }
        int firstNonNumeric = 0;
        while (firstNonNumeric < segments.size() && segments.get(firstNonNumeric) instanceof BigInteger) {
            firstNonNumeric++;
        }
        List<Object> numbers = new ArrayList<>(segments.subList(0, firstNonNumeric));
        while (!numbers.isEmpty() && BigInteger.ZERO.equals(numbers.getLast())) {
            numbers.removeLast();
        }
        this.numeric = Collections.unmodifiableList(numbers);
        this.prerelease = List.copyOf(segments.subList(firstNonNumeric, segments.size()));
    }

    /** Any text; surrounding whitespace is ignored like {@code Pod::Version} does. */
    public static PodVersion of(String text) {
        return new PodVersion(text == null ? "" : text.strip());
    }

    /** Whether CocoaPods accepts {@code text} as a version ({@code Pod::Version.correct?}, without the empty case). */
    public static boolean isValid(String text) {
        return text != null && !text.isBlank() && VALID.matcher(text.strip()).matches();
    }

    public String text() {
        return text;
    }

    /** A pre-release has letters or a dash before any build metadata, e.g. {@code 2.0.0-RC1} or {@code 1.0b2}. */
    public boolean isPrerelease() {
        return PRERELEASE.matcher(METADATA.matcher(text).replaceFirst("")).find();
    }

    /** Whether the two denote the same version for CocoaPods ({@code 1.0 == 1.0.0}, {@code 1.0+a == 1.0+b}). */
    public boolean isEquivalent(PodVersion other) {
        return compareSegments(other) == 0;
    }

    /** {@code Pod::Version#<=>}: segment order first, the text itself breaks ties so the order is total. */
    @Override
    public int compareTo(PodVersion other) {
        int c = compareSegments(other);
        return c != 0 ? c : Integer.signum(text.compareTo(other.text));
    }

    private int compareSegments(PodVersion other) {
        if (text.equals(other.text)) {
            return 0;
        }
        int c = compare(numeric, other.numeric, false);
        return c != 0 ? c : compare(prerelease, other.prerelease, true);
    }

    private static int compare(List<Object> a, List<Object> b, boolean prereleasePart) {
        int limit = Math.max(a.size(), b.size());
        for (int i = 0; i < limit; i++) {
            Object lhs = i < a.size() ? a.get(i) : null;
            Object rhs = i < b.size() ? b.get(i) : null;
            if (Objects.equals(lhs, rhs)) {
                continue;
            }
            // a missing first pre-release segment means "not a pre-release", which sorts higher
            if (prereleasePart && i == 0) {
                if (lhs == null) {
                    return 1;
                }
                if (rhs == null) {
                    return -1;
                }
            } else {
                if (lhs == null) {
                    return -1;
                }
                if (rhs == null) {
                    return 1;
                }
            }
            if (lhs instanceof BigInteger x && rhs instanceof BigInteger y) {
                return Integer.signum(x.compareTo(y));
            }
            if (lhs instanceof String x && rhs instanceof String y) {
                return Integer.signum(x.compareTo(y));
            }
            return lhs instanceof BigInteger ? -1 : 1;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PodVersion other && text.equals(other.text);
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
