package dev.granary.format.composer;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composer's version rules, ported from {@code composer/semver}'s {@code VersionParser} and PHP's
 * {@code version_compare}: {@code 1.0} normalises to {@code 1.0.0.0}, {@code v2.1-beta.3} to
 * {@code 2.1.0.0-beta3}, {@code 1.x-dev} to {@code 1.9999999.9999999.9999999-dev} and {@code main} or
 * {@code dev-main} to {@code dev-main}. The normalised form is what {@code version_normalized} carries and what
 * decides whether two version entries describe the same release.
 */
public final class ComposerVersions {

    public static final String STABLE = "stable";
    public static final String DEV = "dev";

    private static final String MODIFIER =
            "[._-]?(?:(stable|beta|b|RC|alpha|a|patch|pl|p)((?:[.-]?\\d+)*+)?)?([.-]?dev)?";
    private static final Pattern ALIAS = Pattern.compile("^([^,\\s]++) ++as ++([^,\\s]++)$");
    private static final Pattern STABILITY_FLAG = Pattern.compile("@(?:stable|RC|beta|alpha|dev)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUILD_METADATA = Pattern.compile("^([^,\\s+]++)\\+[^\\s]++$");
    private static final Pattern CLASSICAL = Pattern.compile(
            "^v?(\\d{1,5}+)(\\.\\d++)?(\\.\\d++)?(\\.\\d++)?" + MODIFIER + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile(
            "^v?(\\d{4}(?:[.:-]?\\d{2}){1,6}(?:[.:-]?\\d{1,3}){0,2})" + MODIFIER + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEV_SUFFIX = Pattern.compile("(.*?)[.-]?dev$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRANCH = Pattern.compile(
            "^v?(\\d++)(\\.(?:\\d++|[xX*]))?(\\.(?:\\d++|[xX*]))?(\\.(?:\\d++|[xX*]))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STABILITY = Pattern.compile(MODIFIER + "(?:\\+.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");

    /** Newest first; branches ({@code dev-*}) after every numbered version, by name. */
    public static final Comparator<String> NEWEST_FIRST = (a, b) -> {
        boolean branchA = a.startsWith("dev-");
        boolean branchB = b.startsWith("dev-");
        if (branchA || branchB) {
            if (branchA && branchB) {
                return a.compareTo(b);
            }
            return branchA ? 1 : -1;
        }
        return compare(b, a);
    };

    private ComposerVersions() {
    }

    /** {@code VersionParser::normalize}; throws {@link IllegalArgumentException} for anything Composer rejects. */
    public static String normalize(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Invalid version string \"\"");
        }
        String version = input.trim();
        String original = version;
        Matcher m = ALIAS.matcher(version);
        if (m.find()) {
            version = m.group(1);
        }
        m = STABILITY_FLAG.matcher(version);
        if (m.find()) {
            version = version.substring(0, version.length() - m.group().length());
        }
        if (version.equals("master") || version.equals("trunk") || version.equals("default")) {
            version = "dev-" + version;
        }
        if (version.regionMatches(true, 0, "dev-", 0, 4)) {
            return "dev-" + version.substring(4);
        }
        m = BUILD_METADATA.matcher(version);
        if (m.find()) {
            version = m.group(1);
        }
        Matcher matched = null;
        int index = 0;
        m = CLASSICAL.matcher(version);
        if (m.find()) {
            version = m.group(1) + orDefault(m.group(2), ".0") + orDefault(m.group(3), ".0") + orDefault(m.group(4), ".0");
            matched = m;
            index = 5;
        } else {
            m = DATE.matcher(version);
            if (m.find()) {
                version = NON_DIGIT.matcher(m.group(1)).replaceAll(".");
                matched = m;
                index = 2;
            }
        }
        if (matched != null) {
            String stability = matched.group(index);
            if (notEmpty(stability)) {
                if (STABLE.equals(stability)) {
                    return version;
                }
                String number = matched.group(index + 1);
                version += "-" + expandStability(stability) + (number != null && !number.isEmpty() ? ltrim(number) : "");
            }
            if (notEmpty(matched.group(index + 2))) {
                version += "-dev";
            }
            return version;
        }
        m = DEV_SUFFIX.matcher(version);
        if (m.find()) {
            String normalized = normalizeBranch(m.group(1));
            if (!normalized.contains("dev-")) {
                return normalized;
            }
        }
        throw new IllegalArgumentException("Invalid version string \"" + original + "\"");
    }

    /** {@link #normalize} without the exception. */
    public static Optional<String> tryNormalize(String version) {
        try {
            return Optional.of(normalize(version));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code VersionParser::normalizeBranch}: {@code 2.1.x} becomes {@code 2.1.9999999.9999999-dev}, a name {@code dev-<name>}. */
    public static String normalizeBranch(String input) {
        String name = input.trim();
        Matcher m = BRANCH.matcher(name);
        if (m.find()) {
            StringBuilder version = new StringBuilder();
            for (int i = 1; i < 5; i++) {
                String group = m.group(i);
                version.append(group != null ? group.replace('*', 'x').replace('X', 'x') : ".x");
            }
            return version.toString().replace("x", "9999999") + "-dev";
        }
        return "dev-" + name;
    }

    /** {@code VersionParser::parseStability}: {@code stable}, {@code RC}, {@code beta}, {@code alpha} or {@code dev}. */
    public static String parseStability(String input) {
        String version = input == null ? "" : input.replaceFirst("#.+$", "");
        if (version.startsWith("dev-") || version.endsWith("-dev")) {
            return DEV;
        }
        Matcher m = STABILITY.matcher(version.toLowerCase(Locale.ROOT));
        if (m.find()) {
            if (notEmpty(m.group(3))) {
                return DEV;
            }
            String modifier = m.group(1);
            if (notEmpty(modifier)) {
                switch (modifier) {
                    case "beta", "b" -> {
                        return "beta";
                    }
                    case "alpha", "a" -> {
                        return "alpha";
                    }
                    case "rc" -> {
                        return "RC";
                    }
                    default -> {
                        // stable, patch, pl and p are stable releases
                    }
                }
            }
        }
        return STABLE;
    }

    /** Whether a version is a development version: a branch, served from the {@code ~dev} metadata file. */
    public static boolean isDev(String version) {
        return DEV.equals(parseStability(version));
    }

    /** PHP's {@code version_compare} without an operator: negative, zero or positive. */
    public static int compare(String v1, String v2) {
        if (v1.isEmpty() || v2.isEmpty()) {
            if (v1.isEmpty() && v2.isEmpty()) {
                return 0;
            }
            return v1.isEmpty() ? -1 : 1;
        }
        String a = v1.charAt(0) == '#' ? v1 : canonicalize(v1);
        String b = v2.charAt(0) == '#' ? v2 : canonicalize(v2);
        int p1 = 0;
        int p2 = 0;
        int n1 = 0;
        int n2 = 0;
        int compare = 0;
        while (p1 < a.length() && p2 < b.length() && n1 >= 0 && n2 >= 0) {
            n1 = a.indexOf('.', p1);
            n2 = b.indexOf('.', p2);
            String s1 = n1 >= 0 ? a.substring(p1, n1) : a.substring(p1);
            String s2 = n2 >= 0 ? b.substring(p2, n2) : b.substring(p2);
            boolean d1 = startsWithDigit(s1);
            boolean d2 = startsWithDigit(s2);
            if (d1 && d2) {
                compare = compareNumbers(s1, s2);
            } else if (!d1 && !d2) {
                compare = compareSpecial(s1, s2);
            } else if (d1) {
                compare = compareSpecial("#N#", s2);
            } else {
                compare = compareSpecial(s1, "#N#");
            }
            if (compare != 0) {
                break;
            }
            if (n1 >= 0) {
                p1 = n1 + 1;
            }
            if (n2 >= 0) {
                p2 = n2 + 1;
            }
        }
        if (compare == 0) {
            if (n1 >= 0) {
                String rest = a.substring(Math.min(p1, a.length()));
                compare = startsWithDigit(rest) ? 1 : compare(rest, "#N#");
            } else if (n2 >= 0) {
                String rest = b.substring(Math.min(p2, b.length()));
                compare = startsWithDigit(rest) ? -1 : compare("#N#", rest);
            }
        }
        return Integer.signum(compare);
    }

    /** {@code php_canonicalize_version}: separators become dots and digit/letter transitions get one. */
    static String canonicalize(String version) {
        StringBuilder out = new StringBuilder(version.length() * 2);
        char lp = version.charAt(0);
        out.append(lp);
        for (int i = 1; i < version.length(); i++) {
            char c = version.charAt(i);
            char last = out.charAt(out.length() - 1);
            if (c == '-' || c == '_' || c == '+') {
                if (last != '.') {
                    out.append('.');
                }
            } else if ((isNonDigit(lp) && isDigit(c)) || (isDigit(lp) && isNonDigit(c))) {
                if (last != '.') {
                    out.append('.');
                }
                out.append(c);
            } else if (!Character.isLetterOrDigit(c) || c > 127) {
                if (last != '.') {
                    out.append('.');
                }
            } else {
                out.append(c);
            }
            lp = c;
        }
        return out.toString();
    }

    private static final String[][] SPECIAL_FORMS = {
            {"dev", "0"}, {"alpha", "1"}, {"a", "1"}, {"beta", "2"}, {"b", "2"},
            {"RC", "3"}, {"rc", "3"}, {"#", "4"}, {"pl", "5"}, {"p", "5"}};

    private static int compareSpecial(String form1, String form2) {
        return Integer.signum(specialOrder(form1) - specialOrder(form2));
    }

    private static int specialOrder(String form) {
        for (String[] special : SPECIAL_FORMS) {
            if (form.startsWith(special[0])) {
                return Integer.parseInt(special[1]);
            }
        }
        return -6;
    }

    private static int compareNumbers(String a, String b) {
        String x = stripZeros(a);
        String y = stripZeros(b);
        if (x.length() != y.length()) {
            return Integer.compare(x.length(), y.length());
        }
        return Integer.signum(x.compareTo(y));
    }

    private static String stripZeros(String digits) {
        int end = 0;
        while (end < digits.length() && isDigit(digits.charAt(end))) {
            end++;
        }
        String number = digits.substring(0, end);
        int i = 0;
        while (i < number.length() - 1 && number.charAt(i) == '0') {
            i++;
        }
        return number.substring(i);
    }

    private static boolean startsWithDigit(String s) {
        return !s.isEmpty() && isDigit(s.charAt(0));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isNonDigit(char c) {
        return !isDigit(c) && c != '.';
    }

    private static String expandStability(String stability) {
        return switch (stability.toLowerCase(Locale.ROOT)) {
            case "a" -> "alpha";
            case "b" -> "beta";
            case "p", "pl" -> "patch";
            case "rc" -> "RC";
            default -> stability.toLowerCase(Locale.ROOT);
        };
    }

    private static String orDefault(String group, String fallback) {
        return notEmpty(group) ? group : fallback;
    }

    /** PHP's {@code !empty()} for a regex group: unset, {@code ""} and {@code "0"} are empty. */
    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty() && !s.equals("0");
    }

    private static String ltrim(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '.' || s.charAt(i) == '-')) {
            i++;
        }
        return s.substring(i);
    }
}
