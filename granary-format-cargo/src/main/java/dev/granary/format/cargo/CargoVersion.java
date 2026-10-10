package dev.granary.format.cargo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A Semantic Versioning 2.0 version as cargo and crates.io use it: {@code MAJOR.MINOR.PATCH}, an optional
 * pre-release after {@code -} and optional build metadata after {@code +}. Precedence follows the semver
 * specification; build metadata only breaks ties so that sorting is total and stable.
 */
record CargoVersion(long major, long minor, long patch, List<String> pre, String build) implements Comparable<CargoVersion> {

    /** Orders version strings by precedence; strings that are not semver sort after every valid version. */
    static final Comparator<String> STRING_ORDER = (a, b) -> {
        Optional<CargoVersion> va = parse(a);
        Optional<CargoVersion> vb = parse(b);
        if (va.isPresent() && vb.isPresent()) {
            return va.get().compareTo(vb.get());
        }
        if (va.isPresent()) {
            return -1;
        }
        if (vb.isPresent()) {
            return 1;
        }
        return 0;
    };

    CargoVersion {
        pre = List.copyOf(pre);
        build = build == null ? "" : build;
    }

    static Optional<CargoVersion> parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 256) {
            return Optional.empty();
        }
        String rest = text;
        String build = "";
        int plus = rest.indexOf('+');
        if (plus >= 0) {
            build = rest.substring(plus + 1);
            rest = rest.substring(0, plus);
            if (!validIdentifiers(build, false)) {
                return Optional.empty();
            }
        }
        List<String> pre = List.of();
        int dash = rest.indexOf('-');
        if (dash >= 0) {
            String preText = rest.substring(dash + 1);
            rest = rest.substring(0, dash);
            if (!validIdentifiers(preText, true)) {
                return Optional.empty();
            }
            pre = List.of(preText.split("\\.", -1));
        }
        String[] core = rest.split("\\.", -1);
        if (core.length != 3) {
            return Optional.empty();
        }
        long[] numbers = new long[3];
        for (int i = 0; i < 3; i++) {
            Long n = numeric(core[i]);
            if (n == null) {
                return Optional.empty();
            }
            numbers[i] = n;
        }
        return Optional.of(new CargoVersion(numbers[0], numbers[1], numbers[2], pre, build));
    }

    static boolean isValid(String text) {
        return parse(text).isPresent();
    }

    boolean isPrerelease() {
        return !pre.isEmpty();
    }

    /** The version without build metadata; crates.io refuses two versions that differ only there. */
    String withoutBuild() {
        StringBuilder sb = new StringBuilder().append(major).append('.').append(minor).append('.').append(patch);
        if (!pre.isEmpty()) {
            sb.append('-').append(String.join(".", pre));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return build.isEmpty() ? withoutBuild() : withoutBuild() + "+" + build;
    }

    @Override
    public int compareTo(CargoVersion o) {
        int c = Long.compare(major, o.major);
        if (c == 0) {
            c = Long.compare(minor, o.minor);
        }
        if (c == 0) {
            c = Long.compare(patch, o.patch);
        }
        if (c == 0) {
            c = comparePre(pre, o.pre);
        }
        if (c == 0) {
            c = build.compareTo(o.build);
        }
        return c;
    }

    /** {@code true} when both are the same version in the semver sense (build metadata ignored). */
    boolean samePrecedence(CargoVersion o) {
        return major == o.major && minor == o.minor && patch == o.patch && comparePre(pre, o.pre) == 0;
    }

    /**
     * The version {@code cargo search} should show: the highest release, else the highest pre-release, of the
     * versions given. Empty when none of them is valid semver.
     */
    static Optional<String> highest(Collection<String> versions) {
        List<CargoVersion> parsed = new ArrayList<>();
        for (String v : versions) {
            parse(v).ifPresent(parsed::add);
        }
        Optional<CargoVersion> release = parsed.stream().filter(v -> !v.isPrerelease()).max(Comparator.naturalOrder());
        if (release.isPresent()) {
            return release.map(CargoVersion::toString);
        }
        return parsed.stream().max(Comparator.naturalOrder()).map(CargoVersion::toString);
    }

    private static int comparePre(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            // a release has higher precedence than any of its pre-releases
            return Boolean.compare(a.isEmpty(), b.isEmpty());
        }
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            String x = a.get(i);
            String y = b.get(i);
            boolean xNum = isDigits(x);
            boolean yNum = isDigits(y);
            int c;
            if (xNum && yNum) {
                c = compareNumeric(x, y);
            } else if (xNum) {
                c = -1;
            } else if (yNum) {
                c = 1;
            } else {
                c = x.compareTo(y);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static int compareNumeric(String x, String y) {
        String a = stripZeros(x);
        String b = stripZeros(y);
        return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
    }

    private static String stripZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }

    private static Long numeric(String s) {
        if (s.isEmpty() || !isDigits(s) || (s.length() > 1 && s.charAt(0) == '0') || s.length() > 19) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean validIdentifiers(String text, boolean prerelease) {
        if (text.isEmpty()) {
            return false;
        }
        for (String id : text.split("\\.", -1)) {
            if (id.isEmpty()) {
                return false;
            }
            for (int i = 0; i < id.length(); i++) {
                char c = id.charAt(i);
                boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-';
                if (!ok) {
                    return false;
                }
            }
            if (prerelease && isDigits(id) && id.length() > 1 && id.charAt(0) == '0') {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
