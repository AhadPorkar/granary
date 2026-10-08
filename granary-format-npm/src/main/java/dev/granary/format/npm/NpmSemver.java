package dev.granary.format.npm;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic-version ordering as specified at semver.org, implemented by hand so the module needs no
 * third-party dependency.
 *
 * <p>Precedence is major, minor, patch numerically; a pre-release version is lower than the release it
 * precedes; pre-release identifiers are compared one by one, numeric ones numerically, numeric before
 * alphanumeric, alphanumeric ones in ASCII order, and a shorter list of identifiers is lower when it
 * is a prefix of the longer one. Build metadata is ignored. Strings that are not valid versions sort
 * below every valid one and among themselves alphabetically, so a packument with a stray tag never
 * breaks the "highest version" logic.
 */
public final class NpmSemver {

    public static final Comparator<String> COMPARATOR = NpmSemver::compare;

    private static final Pattern SEMVER = Pattern.compile(
            "^[v=]?\\s*(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");
    private static final Pattern NUMERIC = Pattern.compile("^\\d+$");

    private NpmSemver() {
    }

    /** The parts of a version that take part in ordering. */
    public record Version(long major, long minor, long patch, List<String> preRelease) implements Comparable<Version> {

        public boolean isPreRelease() {
            return !preRelease.isEmpty();
        }

        @Override
        public int compareTo(Version o) {
            int c = Long.compare(major, o.major);
            if (c != 0) {
                return c;
            }
            c = Long.compare(minor, o.minor);
            if (c != 0) {
                return c;
            }
            c = Long.compare(patch, o.patch);
            if (c != 0) {
                return c;
            }
            return comparePreRelease(preRelease, o.preRelease);
        }

        private static int comparePreRelease(List<String> a, List<String> b) {
            if (a.isEmpty() && b.isEmpty()) {
                return 0;
            }
            if (a.isEmpty() || b.isEmpty()) {
                return a.isEmpty() ? 1 : -1; // a release outranks any pre-release of the same version
            }
            int n = Math.min(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                int c = compareIdentifier(a.get(i), b.get(i));
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(a.size(), b.size());
        }

        private static int compareIdentifier(String x, String y) {
            boolean xn = NUMERIC.matcher(x).matches();
            boolean yn = NUMERIC.matcher(y).matches();
            if (xn && yn) {
                int c = Integer.compare(x.length(), y.length()); // no leading zeros per spec, so length decides first
                return c != 0 ? c : x.compareTo(y);
            }
            if (xn != yn) {
                return xn ? -1 : 1;
            }
            return x.compareTo(y);
        }
    }

    public static Optional<Version> parse(String version) {
        if (version == null) {
            return Optional.empty();
        }
        Matcher m = SEMVER.matcher(version.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            List<String> pre = m.group(4) == null ? List.of() : List.of(m.group(4).split("\\."));
            return Optional.of(new Version(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)),
                    Long.parseLong(m.group(3)), pre));
        } catch (NumberFormatException e) {
            return Optional.empty(); // a component too large for a long is not a version anyone publishes
        }
    }

    public static boolean isValid(String version) {
        return parse(version).isPresent();
    }

    public static boolean isPreRelease(String version) {
        return parse(version).map(Version::isPreRelease).orElse(false);
    }

    public static int compare(String a, String b) {
        Optional<Version> va = parse(a);
        Optional<Version> vb = parse(b);
        if (va.isPresent() && vb.isPresent()) {
            return va.get().compareTo(vb.get());
        }
        if (va.isPresent() != vb.isPresent()) {
            return va.isPresent() ? 1 : -1;
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /** The highest of the given versions, pre-releases included. */
    public static Optional<String> highest(Collection<String> versions) {
        return versions.stream().filter(v -> v != null).max(COMPARATOR);
    }

    /**
     * The version a {@code latest} tag should point at: the highest release, or the highest pre-release
     * when nothing else exists, as npm itself does when a tag is re-computed after an unpublish.
     */
    public static Optional<String> highestRelease(Collection<String> versions) {
        Optional<String> release = versions.stream()
                .filter(v -> isValid(v) && !isPreRelease(v))
                .max(COMPARATOR);
        return release.isPresent() ? release : highest(versions);
    }
}
