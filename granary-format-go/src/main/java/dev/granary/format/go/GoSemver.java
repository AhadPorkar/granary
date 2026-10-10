package dev.granary.format.go;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Semantic version precedence exactly as {@code golang.org/x/mod/semver} implements it: a leading {@code v},
 * short forms ({@code v1}, {@code v1.2}) accepted, pre-release identifiers compared numerically or lexically,
 * build metadata ignored, invalid versions sorting before valid ones and equal to each other.
 */
public final class GoSemver {

    /** {@code module.IsPseudoVersion}'s pattern: a base version, a UTC timestamp and a revision identifier. */
    private static final Pattern PSEUDO = Pattern.compile(
            "^v[0-9]+\\.(0\\.0-|\\d+\\.\\d+-([^+]*\\.)?0\\.)\\d{14}-[A-Za-z0-9]+(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?$");

    /** Precedence first, then the string itself, so that sorting is total and stable ({@code semver.ByVersion}). */
    public static final Comparator<String> ORDER = (a, b) -> {
        int cmp = compare(a, b);
        return cmp != 0 ? cmp : a.compareTo(b);
    };

    private GoSemver() {
    }

    private record Parsed(String major, String minor, String patch, String shortForm, String prerelease, String build) {
    }

    public static boolean isValid(String v) {
        return parse(v) != null;
    }

    /** {@code vMAJOR} of a valid version, otherwise an empty string. */
    public static String major(String v) {
        Parsed p = parse(v);
        return p == null ? "" : "v" + p.major();
    }

    /** The {@code -prerelease} part including its dash, or an empty string. */
    public static String prerelease(String v) {
        Parsed p = parse(v);
        return p == null ? "" : p.prerelease();
    }

    /** The {@code +build} part including its plus, or an empty string. */
    public static String build(String v) {
        Parsed p = parse(v);
        return p == null ? "" : p.build();
    }

    public static boolean isPrerelease(String v) {
        return !prerelease(v).isEmpty();
    }

    public static boolean isPseudoVersion(String v) {
        return v != null && v.chars().filter(c -> c == '-').count() >= 2 && isValid(v) && PSEUDO.matcher(v).matches();
    }

    /**
     * {@code module.CanonicalVersion}: the version with short forms expanded and build metadata dropped,
     * except for {@code +incompatible} which is part of a module version. Empty for an invalid version.
     */
    public static String canonical(String v) {
        Parsed p = parse(v);
        if (p == null) {
            return "";
        }
        String c = "v" + p.major() + "." + p.minor() + "." + p.patch() + p.prerelease();
        return p.build().equals("+incompatible") ? c + "+incompatible" : c;
    }

    public static int compare(String v, String w) {
        Parsed pv = parse(v);
        Parsed pw = parse(w);
        if (pv == null && pw == null) {
            return 0;
        }
        if (pv == null) {
            return -1;
        }
        if (pw == null) {
            return 1;
        }
        int c = compareInt(pv.major(), pw.major());
        if (c != 0) {
            return c;
        }
        c = compareInt(pv.minor(), pw.minor());
        if (c != 0) {
            return c;
        }
        c = compareInt(pv.patch(), pw.patch());
        if (c != 0) {
            return c;
        }
        return comparePrerelease(pv.prerelease(), pw.prerelease());
    }

    /** Distinct versions in precedence order. */
    public static List<String> sorted(Collection<String> versions) {
        TreeSet<String> set = new TreeSet<>(ORDER);
        set.addAll(versions);
        return new ArrayList<>(set);
    }

    /**
     * What the go command treats as "latest": the highest release, else the highest pre-release, else the
     * highest pseudo-version. Invalid strings are ignored.
     */
    public static Optional<String> latest(Collection<String> versions) {
        String bestRelease = null;
        String bestPrerelease = null;
        String bestPseudo = null;
        for (String v : versions) {
            if (!isValid(v)) {
                continue;
            }
            if (isPseudoVersion(v)) {
                bestPseudo = max(bestPseudo, v);
            } else if (isPrerelease(v)) {
                bestPrerelease = max(bestPrerelease, v);
            } else {
                bestRelease = max(bestRelease, v);
            }
        }
        if (bestRelease != null) {
            return Optional.of(bestRelease);
        }
        if (bestPrerelease != null) {
            return Optional.of(bestPrerelease);
        }
        return Optional.ofNullable(bestPseudo);
    }

    private static String max(String current, String candidate) {
        return current == null || ORDER.compare(candidate, current) > 0 ? candidate : current;
    }

    // --- parsing --------------------------------------------------------------------------------------

    private static Parsed parse(String v) {
        if (v == null || v.isEmpty() || v.charAt(0) != 'v') {
            return null;
        }
        int[] pos = {1};
        String major = parseInt(v, pos);
        if (major == null) {
            return null;
        }
        if (pos[0] == v.length()) {
            return new Parsed(major, "0", "0", ".0.0", "", "");
        }
        if (v.charAt(pos[0]) != '.') {
            return null;
        }
        pos[0]++;
        String minor = parseInt(v, pos);
        if (minor == null) {
            return null;
        }
        if (pos[0] == v.length()) {
            return new Parsed(major, minor, "0", ".0", "", "");
        }
        if (v.charAt(pos[0]) != '.') {
            return null;
        }
        pos[0]++;
        String patch = parseInt(v, pos);
        if (patch == null) {
            return null;
        }
        String prerelease = "";
        String build = "";
        if (pos[0] < v.length() && v.charAt(pos[0]) == '-') {
            prerelease = parsePrerelease(v, pos);
            if (prerelease == null) {
                return null;
            }
        }
        if (pos[0] < v.length() && v.charAt(pos[0]) == '+') {
            build = parseBuild(v, pos);
            if (build == null) {
                return null;
            }
        }
        if (pos[0] != v.length()) {
            return null;
        }
        return new Parsed(major, minor, patch, "", prerelease, build);
    }

    private static String parseInt(String v, int[] pos) {
        int start = pos[0];
        if (start >= v.length() || !isDigit(v.charAt(start))) {
            return null;
        }
        int i = start + 1;
        while (i < v.length() && isDigit(v.charAt(i))) {
            i++;
        }
        if (v.charAt(start) == '0' && i != start + 1) {
            return null;
        }
        pos[0] = i;
        return v.substring(start, i);
    }

    private static String parsePrerelease(String v, int[] pos) {
        int start = pos[0];
        int i = start + 1;
        int identStart = i;
        while (i < v.length() && v.charAt(i) != '+') {
            char c = v.charAt(i);
            if (isIdentChar(c)) {
                i++;
                continue;
            }
            if (c == '.') {
                if (identStart == i || isBadNum(v.substring(identStart, i))) {
                    return null;
                }
                i++;
                identStart = i;
                continue;
            }
            return null;
        }
        if (identStart == i || isBadNum(v.substring(identStart, i))) {
            return null;
        }
        pos[0] = i;
        return v.substring(start, i);
    }

    private static String parseBuild(String v, int[] pos) {
        int start = pos[0];
        int i = start + 1;
        int identStart = i;
        while (i < v.length()) {
            char c = v.charAt(i);
            if (isIdentChar(c)) {
                i++;
                continue;
            }
            if (c == '.') {
                if (identStart == i) {
                    return null;
                }
                i++;
                identStart = i;
                continue;
            }
            return null;
        }
        if (identStart == i) {
            return null;
        }
        pos[0] = i;
        return v.substring(start, i);
    }

    private static boolean isIdentChar(char c) {
        return isDigit(c) || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '-';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isNum(String s) {
        return !s.isEmpty() && s.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** A numeric identifier with a leading zero is invalid in a pre-release. */
    private static boolean isBadNum(String s) {
        return isNum(s) && s.length() > 1 && s.charAt(0) == '0';
    }

    private static int compareInt(String x, String y) {
        if (x.equals(y)) {
            return 0;
        }
        if (x.length() != y.length()) {
            return x.length() < y.length() ? -1 : 1;
        }
        return x.compareTo(y) < 0 ? -1 : 1;
    }

    private static int comparePrerelease(String x, String y) {
        if (x.equals(y)) {
            return 0;
        }
        if (x.isEmpty()) {
            return 1;
        }
        if (y.isEmpty()) {
            return -1;
        }
        String[] xs = x.substring(1).split("\\.", -1);
        String[] ys = y.substring(1).split("\\.", -1);
        int n = Math.min(xs.length, ys.length);
        for (int i = 0; i < n; i++) {
            String dx = xs[i];
            String dy = ys[i];
            if (dx.equals(dy)) {
                continue;
            }
            boolean nx = isNum(dx);
            boolean ny = isNum(dy);
            if (nx != ny) {
                return nx ? -1 : 1;
            }
            if (nx) {
                if (dx.length() != dy.length()) {
                    return dx.length() < dy.length() ? -1 : 1;
                }
            }
            return dx.compareTo(dy) < 0 ? -1 : 1;
        }
        if (xs.length == ys.length) {
            return 0;
        }
        return xs.length < ys.length ? -1 : 1;
    }
}
