package dev.granary.format.cargo;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Crate names and where everything about a crate lives, in the index and in storage.
 *
 * <p>Index files follow cargo's layout ({@code 1/a}, {@code 2/ab}, {@code 3/a/abc}, {@code se/rd/serde}) on
 * the lower-cased name, because that is the path cargo asks for. Crate files are stored under
 * {@code crates/<name>/<name>-<version>.crate}, also lower-cased, so a crate is found whatever case the
 * client used.
 */
final class CrateNames {

    /** What crates.io accepts for a new crate: an ASCII letter, then letters, digits, {@code -} and {@code _}. */
    static final int MAX_LENGTH = 64;
    private static final Pattern PUBLISHABLE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0," + (MAX_LENGTH - 1) + "}");
    /** Lookups are a little more lenient so crates of older or other registries still resolve. */
    private static final Pattern LOOKUP = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_-]{0,99}");
    /** Versions as they may appear in a URL: semver characters only, nothing that could form a path. */
    private static final Pattern VERSION_IN_URL = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+-]{0,127}");

    static final String INDEX_PREFIX = "index/";
    static final String CRATES_PREFIX = "crates/";
    static final String META_PREFIX = "meta/";

    private CrateNames() {
    }

    static boolean isValidLookup(String name) {
        return name != null && LOOKUP.matcher(name).matches();
    }

    static boolean isPublishable(String name) {
        return name != null && PUBLISHABLE.matcher(name).matches();
    }

    static boolean isValidUrlVersion(String version) {
        return version != null && VERSION_IN_URL.matcher(version).matches() && !version.contains("..");
    }

    /** Why a name cannot be published, or {@code null} when it can. */
    static String publishProblem(String name) {
        if (name == null || name.isEmpty()) {
            return "crate name must not be empty";
        }
        if (name.length() > MAX_LENGTH) {
            return "crate name `" + name + "` is longer than " + MAX_LENGTH + " characters";
        }
        char first = name.charAt(0);
        if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z'))) {
            return "crate name `" + name + "` must start with an ASCII letter";
        }
        if (!isPublishable(name)) {
            return "crate name `" + name + "` may only contain ASCII letters, digits, `-` and `_`";
        }
        return null;
    }

    static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** crates.io treats names differing only in case or in {@code -} versus {@code _} as the same crate. */
    static String canonical(String name) {
        return lower(name).replace('_', '-');
    }

    /** The directory part of the index path, e.g. {@code se/rd} for serde (cargo's {@code make_dep_path}). */
    static String indexDir(String name) {
        return dirOf(lower(name));
    }

    /** The directory part with the name's own case, which is what the {@code {prefix}} download marker means. */
    static String prefixOf(String name) {
        return dirOf(name);
    }

    private static String dirOf(String name) {
        return switch (name.length()) {
            case 1 -> "1";
            case 2 -> "2";
            case 3 -> "3/" + name.charAt(0);
            default -> name.substring(0, 2) + "/" + name.substring(2, 4);
        };
    }

    /** {@code <dir>/<name>}, relative to the index root: what cargo requests below {@code index/} and below a sparse upstream. */
    static String indexRelative(String name) {
        return indexDir(name) + "/" + lower(name);
    }

    /** Storage path of the index file. */
    static String indexPath(String name) {
        return INDEX_PREFIX + indexRelative(name);
    }

    /** Storage path of a crate file. */
    static String cratePath(String name, String version) {
        String lower = lower(name);
        return CRATES_PREFIX + lower + "/" + lower + "-" + version + ".crate";
    }

    /** The folder holding every version of a crate, for listing. */
    static String crateFolder(String name) {
        return CRATES_PREFIX + lower(name) + "/";
    }

    /** Storage path of the per-crate document of a hosted repository (description, owners, publish times). */
    static String metaPath(String name) {
        return META_PREFIX + lower(name) + ".json";
    }

    /** The crate name an index storage path belongs to ({@code index/se/rd/serde} is serde), or {@code null}. */
    static String nameOfIndexPath(String path) {
        if (path == null || !path.startsWith(INDEX_PREFIX)) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String name = path.substring(slash + 1);
        if (!isValidLookup(name) || !path.equals(indexPath(name))) {
            return null;
        }
        return name;
    }

    /**
     * Every index directory a crate whose canonical name equals {@code name}'s could live in: the names differ
     * only in {@code -} versus {@code _}, and only the first four characters decide the directory.
     */
    static Set<String> similarIndexDirs(String name) {
        String lower = lower(name);
        Set<String> dirs = new LinkedHashSet<>();
        if (lower.length() <= 3) {
            dirs.add(dirOf(lower));
            for (String variant : separatorVariants(lower)) {
                dirs.add(dirOf(variant));
            }
            return dirs;
        }
        String head = lower.substring(0, 4);
        for (String variant : separatorVariants(head)) {
            dirs.add(dirOf(variant + lower.substring(4)));
        }
        return dirs;
    }

    private static Set<String> separatorVariants(String s) {
        Set<String> out = new LinkedHashSet<>();
        out.add(s);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '-' && c != '_') {
                continue;
            }
            char other = c == '-' ? '_' : '-';
            Set<String> next = new LinkedHashSet<>();
            for (String v : out) {
                next.add(v);
                next.add(v.substring(0, i) + other + v.substring(i + 1));
            }
            out = next;
        }
        return out;
    }
}
