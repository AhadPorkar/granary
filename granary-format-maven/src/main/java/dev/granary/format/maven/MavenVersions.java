package dev.granary.format.maven;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Orders Maven versions the way Maven itself does, closely following the rules of
 * {@code org.apache.maven.artifact.versioning.ComparableVersion}: versions are split into numeric and
 * textual items on dots, hyphens and digit/letter transitions; numbers compare numerically; the known
 * qualifiers are ordered {@code alpha < beta < milestone < rc < snapshot < (release) < sp}; trailing
 * zeros and empty items are ignored.
 */
public final class MavenVersions {

    public static final Comparator<String> COMPARATOR = MavenVersions::compare;

    private static final List<String> QUALIFIERS = List.of("alpha", "beta", "milestone", "rc", "snapshot", "", "sp");

    private MavenVersions() {
    }

    public static int compare(String a, String b) {
        List<Item> left = parse(a);
        List<Item> right = parse(b);
        int n = Math.max(left.size(), right.size());
        for (int i = 0; i < n; i++) {
            Item l = i < left.size() ? left.get(i) : Item.NULL;
            Item r = i < right.size() ? right.get(i) : Item.NULL;
            int c = l.compareTo(r);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    public static String max(Iterable<String> versions) {
        String best = null;
        for (String v : versions) {
            if (best == null || compare(v, best) > 0) {
                best = v;
            }
        }
        return best;
    }

    static List<Item> parse(String version) {
        String v = version == null ? "" : version.toLowerCase(Locale.ROOT);
        List<Item> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean digits = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '.' || c == '-') {
                flush(items, current, digits);
                continue;
            }
            boolean isDigit = Character.isDigit(c);
            if (current.length() > 0 && isDigit != digits) {
                flush(items, current, digits);
            }
            digits = isDigit;
            current.append(c);
        }
        flush(items, current, digits);
        // trim trailing "null" items (zeros, empty qualifiers) so 1.0 == 1 == 1.0.0 and 1.0-final == 1.0
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).isNull()) {
                items.remove(i);
            } else {
                break;
            }
        }
        return items;
    }

    private static void flush(List<Item> items, StringBuilder current, boolean digits) {
        if (current.length() == 0) {
            return;
        }
        String s = current.toString();
        current.setLength(0);
        if (digits) {
            items.add(new Item(true, s.replaceFirst("^0+(?=\\d)", ""), 0));
        } else {
            items.add(new Item(false, normaliseQualifier(s), 0));
        }
    }

    private static String normaliseQualifier(String q) {
        return switch (q) {
            case "a" -> "alpha";
            case "b" -> "beta";
            case "m" -> "milestone";
            case "cr" -> "rc";
            case "ga", "final", "release" -> "";
            default -> q;
        };
    }

    record Item(boolean numeric, String value, int unused) implements Comparable<Item> {

        static final Item NULL = new Item(false, "", 0);

        boolean isNull() {
            return numeric ? value.isEmpty() || value.equals("0") : value.isEmpty();
        }

        @Override
        public int compareTo(Item other) {
            if (numeric && other.numeric) {
                return compareNumbers(value, other.value);
            }
            if (numeric) {
                return 1; // any number outranks any qualifier: 1.0.1 > 1.0-rc, 1.0.1 > 1.0-sp1
            }
            if (other.numeric) {
                return -1;
            }
            int byRank = Integer.compare(qualifierRank(value), qualifierRank(other.value));
            if (byRank != 0) {
                return byRank;
            }
            return isKnown(value) ? 0 : value.compareTo(other.value);
        }

        private static int compareNumbers(String a, String b) {
            if (a.isEmpty()) {
                a = "0";
            }
            if (b.isEmpty()) {
                b = "0";
            }
            return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
        }

        /** Known qualifiers rank below (negative) or above (positive) the release; unknown ones sort after "sp". */
        private static int qualifierRank(String q) {
            int idx = QUALIFIERS.indexOf(q);
            int release = QUALIFIERS.indexOf("");
            if (idx < 0) {
                return QUALIFIERS.size() - release;
            }
            return idx - release;
        }

        private static boolean isKnown(String q) {
            return QUALIFIERS.contains(q);
        }
    }
}
