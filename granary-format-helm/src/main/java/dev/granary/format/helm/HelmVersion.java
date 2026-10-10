package dev.granary.format.helm;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A chart version the way Helm reads it (the "loose" semantic versions of the Masterminds semver library):
 * an optional {@code v}, a major number with optional minor and patch, an optional pre-release and optional
 * build metadata. {@code 1}, {@code 1.2}, {@code v1.2.3-rc.1+build.5} are all valid; build metadata is
 * ignored when comparing, a release sorts above its pre-releases.
 *
 * @param original   the text as written in {@code Chart.yaml}
 * @param major      major number
 * @param minor      minor number (0 when absent)
 * @param patch      patch number (0 when absent)
 * @param prerelease dot-separated pre-release identifiers, empty for a release
 */
public record HelmVersion(String original, long major, long minor, long patch, List<String> prerelease)
        implements Comparable<HelmVersion> {

    private static final Pattern LOOSE = Pattern.compile(
            "v?([0-9]+)(?:\\.([0-9]+))?(?:\\.([0-9]+))?"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");

    /** Descending order of version strings as Helm sorts index entries; unparsable versions go last. */
    public static final Comparator<String> NEWEST_FIRST = (a, b) -> {
        Optional<HelmVersion> va = parse(a);
        Optional<HelmVersion> vb = parse(b);
        if (va.isPresent() && vb.isPresent()) {
            int c = vb.get().compareTo(va.get());
            return c != 0 ? c : b.compareTo(a);
        }
        if (va.isPresent()) {
            return -1;
        }
        if (vb.isPresent()) {
            return 1;
        }
        return b.compareTo(a);
    };

    public HelmVersion {
        prerelease = List.copyOf(prerelease);
    }

    public static Optional<HelmVersion> parse(String text) {
        if (text == null || text.length() > 256) {
            return Optional.empty();
        }
        Matcher m = LOOSE.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            long major = Long.parseLong(m.group(1));
            long minor = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
            long patch = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
            List<String> pre = m.group(4) == null ? List.of() : List.of(m.group(4).split("\\."));
            for (String identifier : pre) {
                if (isNumeric(identifier) && identifier.length() > 1 && identifier.charAt(0) == '0') {
                    return Optional.empty(); // semver forbids leading zeros in numeric pre-release identifiers
                }
            }
            return Optional.of(new HelmVersion(text, major, minor, patch, pre));
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }

    public static boolean isValid(String text) {
        return parse(text).isPresent();
    }

    public boolean isPrerelease() {
        return !prerelease.isEmpty();
    }

    @Override
    public int compareTo(HelmVersion other) {
        int c = Long.compare(major, other.major);
        if (c == 0) {
            c = Long.compare(minor, other.minor);
        }
        if (c == 0) {
            c = Long.compare(patch, other.patch);
        }
        if (c != 0) {
            return c;
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return Boolean.compare(prerelease.isEmpty(), other.prerelease.isEmpty());
        }
        int n = Math.min(prerelease.size(), other.prerelease.size());
        for (int i = 0; i < n; i++) {
            c = compareIdentifier(prerelease.get(i), other.prerelease.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(prerelease.size(), other.prerelease.size());
    }

    private static int compareIdentifier(String a, String b) {
        boolean na = isNumeric(a);
        boolean nb = isNumeric(b);
        if (na && nb) {
            int c = Integer.compare(a.length(), b.length()); // no leading zeros, so longer is larger
            return c != 0 ? c : a.compareTo(b);
        }
        if (na) {
            return -1;
        }
        if (nb) {
            return 1;
        }
        return a.compareTo(b);
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return original;
    }
}
