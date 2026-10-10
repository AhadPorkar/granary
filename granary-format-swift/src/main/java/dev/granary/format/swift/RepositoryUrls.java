package dev.granary.format.swift;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalises source repository URLs for the {@code identifiers?url=} lookup, so the many spellings of one
 * repository find the same packages: {@code https://github.com/mona/LinkedList},
 * {@code https://github.com/mona/LinkedList.git}, {@code git@github.com:mona/LinkedList.git} and
 * {@code ssh://git@github.com/mona/LinkedList} all become {@code github.com/mona/linkedlist}. Scheme, user
 * information, query, fragment, a trailing {@code .git} and trailing slashes are dropped and the result is
 * lower-cased, the way SwiftPM compares package locations.
 */
public final class RepositoryUrls {

    static final int MAX_LENGTH = 2048;

    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*://");
    private static final Pattern PORT_THEN_PATH = Pattern.compile("\\d+(/.*)?");

    private RepositoryUrls() {
    }

    public static Optional<String> normalise(String url) {
        if (url == null) {
            return Optional.empty();
        }
        String s = url.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        var scheme = SCHEME.matcher(s);
        boolean hasScheme = scheme.lookingAt();
        if (hasScheme) {
            s = s.substring(scheme.end());
        }
        int cut = indexOfAny(s, '?', '#');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        int at = s.indexOf('@');
        int slash = s.indexOf('/');
        if (at >= 0 && (slash < 0 || at < slash)) {
            s = s.substring(at + 1);
        }
        if (!hasScheme) {
            // scp-like syntax: host:path
            int colon = s.indexOf(':');
            slash = s.indexOf('/');
            if (colon > 0 && (slash < 0 || colon < slash) && !PORT_THEN_PATH.matcher(s.substring(colon + 1)).matches()) {
                s = s.substring(0, colon) + "/" + s.substring(colon + 1);
            }
        }
        while (s.contains("//")) {
            s = s.replace("//", "/");
        }
        s = stripTrailingSlashes(s);
        if (s.endsWith(".git")) {
            s = stripTrailingSlashes(s.substring(0, s.length() - 4));
        }
        return s.isEmpty() || s.startsWith("/") ? Optional.empty() : Optional.of(s);
    }

    private static String stripTrailingSlashes(String s) {
        String r = s;
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) {
            return j;
        }
        return j < 0 ? i : Math.min(i, j);
    }
}
