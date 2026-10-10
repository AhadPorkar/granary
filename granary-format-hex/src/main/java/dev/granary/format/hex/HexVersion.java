package dev.granary.format.hex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic versions as Elixir's {@code Version} module parses and orders them: {@code major.minor.patch},
 * an optional pre-release ({@code -rc.1}, numeric identifiers compare numerically and sort before
 * alphanumeric ones) and build metadata ({@code +build}) that never takes part in ordering.
 */
public record HexVersion(long major, long minor, long patch, List<String> preRelease, String build)
        implements Comparable<HexVersion> {

    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");
    private static final Pattern NUMERIC = Pattern.compile("^\\d+$");

    /** The characters a version may contain anywhere in a path or file name. */
    static final Pattern LENIENT = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z.+-]*$");

    public HexVersion {
        preRelease = List.copyOf(preRelease);
    }

    public static Optional<HexVersion> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = SEMVER.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            List<String> pre = m.group(4) == null ? List.of() : List.of(m.group(4).split("\\."));
            return Optional.of(new HexVersion(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)),
                    Long.parseLong(m.group(3)), pre, m.group(5)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static boolean isValid(String text) {
        return parse(text).isPresent();
    }

    public boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    @Override
    public int compareTo(HexVersion o) {
        int c = Long.compare(major, o.major);
        if (c == 0) {
            c = Long.compare(minor, o.minor);
        }
        if (c == 0) {
            c = Long.compare(patch, o.patch);
        }
        if (c == 0) {
            c = comparePreRelease(preRelease, o.preRelease);
        }
        return c;
    }

    private static int comparePreRelease(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        if (a.isEmpty()) {
            return 1; // a release is higher than any of its pre-releases
        }
        if (b.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
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
            return new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y));
        }
        if (xn) {
            return -1;
        }
        if (yn) {
            return 1;
        }
        return x.compareTo(y);
    }

    /** Orders version strings ascending; strings that are not semantic versions sort last, alphabetically. */
    public static final Comparator<String> ORDER = (a, b) -> {
        Optional<HexVersion> va = parse(a);
        Optional<HexVersion> vb = parse(b);
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
    };

    public static List<String> sorted(java.util.Collection<String> versions) {
        List<String> list = new ArrayList<>(versions);
        list.sort(ORDER);
        return list;
    }

    /** The highest version, preferring releases over pre-releases the way hex.pm's {@code latest_stable_version} does. */
    public static Optional<String> latestStable(java.util.Collection<String> versions) {
        return sorted(versions).reversed().stream()
                .filter(v -> parse(v).map(p -> !p.isPreRelease()).orElse(false))
                .findFirst();
    }

    public static Optional<String> latest(java.util.Collection<String> versions) {
        List<String> sorted = sorted(versions);
        return sorted.isEmpty() ? Optional.empty() : Optional.of(sorted.getLast());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append(major).append('.').append(minor).append('.').append(patch);
        if (!preRelease.isEmpty()) {
            sb.append('-').append(String.join(".", preRelease));
        }
        if (build != null) {
            sb.append('+').append(build);
        }
        return sb.toString();
    }
}
