package dev.granary.format.nuget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A package version with NuGet's semantics, which are SemVer 2 plus a legacy fourth numeric part.
 *
 * <p>The NuGet client never sends the version string a package author wrote: every URL carries the
 * <em>normalised</em> form ({@code 1.0} becomes {@code 1.0.0}, {@code 1.0.0.0} becomes {@code 1.0.0},
 * {@code 1.0.01} becomes {@code 1.0.1}, build metadata is dropped), lower-cased. The server therefore has
 * to normalise what it stores and what it compares, or {@code 1.0} pushed by {@code nuget pack} and
 * {@code 1.0.0} requested by {@code dotnet restore} would be two different packages.
 *
 * <p>Ordering follows SemVer 2: numeric parts first, a release is newer than any of its pre-releases, and
 * pre-release labels compare identifier by identifier (numeric ones numerically, the others
 * case-insensitively, a shorter list first). Build metadata never influences ordering or equality.
 */
public final class NuGetVersion implements Comparable<NuGetVersion> {

    private static final Pattern SYNTAX = Pattern.compile(
            "^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    /** Orders raw version strings; strings that are not NuGet versions sort after valid ones, alphabetically. */
    public static final Comparator<String> STRING_ORDER = (a, b) -> {
        Optional<NuGetVersion> va = tryParse(a);
        Optional<NuGetVersion> vb = tryParse(b);
        if (va.isPresent() && vb.isPresent()) {
            return va.get().compareTo(vb.get());
        }
        if (va.isPresent()) {
            return -1;
        }
        if (vb.isPresent()) {
            return 1;
        }
        return a.compareToIgnoreCase(b);
    };

    private final int major;
    private final int minor;
    private final int patch;
    private final int revision;
    private final List<String> releaseLabels;
    private final String metadata;
    private final String original;

    private NuGetVersion(int major, int minor, int patch, int revision, List<String> releaseLabels,
                         String metadata, String original) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.revision = revision;
        this.releaseLabels = List.copyOf(releaseLabels);
        this.metadata = metadata;
        this.original = original;
    }

    /** @throws IllegalArgumentException when {@code text} is not a NuGet version */
    public static NuGetVersion parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("version is null");
        }
        String trimmed = text.trim();
        Matcher m = SYNTAX.matcher(trimmed);
        if (!m.matches()) {
            throw new IllegalArgumentException("not a NuGet version: '" + text + "'");
        }
        try {
            int major = Integer.parseInt(m.group(1));
            int minor = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            int revision = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            List<String> labels = m.group(5) == null ? List.of() : List.of(m.group(5).split("\\."));
            return new NuGetVersion(major, minor, patch, revision, labels, m.group(6), trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("numeric part out of range in version '" + text + "'", e);
        }
    }

    public static Optional<NuGetVersion> tryParse(String text) {
        try {
            return Optional.of(parse(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isValid(String text) {
        return tryParse(text).isPresent();
    }

    /** Normalises a version string, or returns it unchanged when it is not a NuGet version. */
    public static String normalise(String text) {
        return tryParse(text).map(NuGetVersion::toNormalizedString).orElse(text);
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    public int revision() {
        return revision;
    }

    public List<String> releaseLabels() {
        return releaseLabels;
    }

    public Optional<String> metadata() {
        return Optional.ofNullable(metadata);
    }

    /** The string as it was given, before normalisation. */
    public String original() {
        return original;
    }

    public boolean isPrerelease() {
        return !releaseLabels.isEmpty();
    }

    /** Whether the version needs SemVer 2 to be expressed (dotted labels or build metadata). */
    public boolean isSemVer2() {
        return metadata != null || releaseLabels.size() > 1;
    }

    /**
     * The canonical form NuGet uses in URLs and documents: three numeric parts, a fourth only when it is
     * non-zero, the pre-release labels as written and no build metadata.
     */
    public String toNormalizedString() {
        StringBuilder sb = new StringBuilder(24);
        sb.append(major).append('.').append(minor).append('.').append(patch);
        if (revision != 0) {
            sb.append('.').append(revision);
        }
        if (!releaseLabels.isEmpty()) {
            sb.append('-').append(String.join(".", releaseLabels));
        }
        return sb.toString();
    }

    /** The normalised form lower-cased, which is how the NuGet client spells versions in flat-container URLs. */
    public String toLowerCaseString() {
        return toNormalizedString().toLowerCase(Locale.ROOT);
    }

    /** Like {@link #toNormalizedString()} but with the build metadata kept, as the catalog shows it. */
    public String toFullString() {
        return metadata == null ? toNormalizedString() : toNormalizedString() + "+" + metadata;
    }

    @Override
    public int compareTo(NuGetVersion other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(patch, other.patch);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(revision, other.revision);
        if (c != 0) {
            return c;
        }
        if (releaseLabels.isEmpty() && other.releaseLabels.isEmpty()) {
            return 0;
        }
        if (releaseLabels.isEmpty()) {
            return 1; // a release is newer than every one of its pre-releases
        }
        if (other.releaseLabels.isEmpty()) {
            return -1;
        }
        return compareLabels(releaseLabels, other.releaseLabels);
    }

    private static int compareLabels(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int c = compareIdentifier(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static int compareIdentifier(String a, String b) {
        boolean na = DIGITS.matcher(a).matches();
        boolean nb = DIGITS.matcher(b).matches();
        if (na && nb) {
            return new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
        }
        if (na) {
            return -1; // numeric identifiers have lower precedence than alphanumeric ones
        }
        if (nb) {
            return 1;
        }
        return a.compareToIgnoreCase(b);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NuGetVersion other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        List<String> lower = new ArrayList<>(releaseLabels.size());
        for (String label : releaseLabels) {
            lower.add(DIGITS.matcher(label).matches() ? new java.math.BigInteger(label).toString() : label.toLowerCase(Locale.ROOT));
        }
        return Objects.hash(major, minor, patch, revision, lower);
    }

    @Override
    public String toString() {
        return toNormalizedString();
    }
}
