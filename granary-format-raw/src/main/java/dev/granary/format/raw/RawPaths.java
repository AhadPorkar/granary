package dev.granary.format.raw;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Repository-relative paths the way raw repositories see them. Every request path is validated once at the
 * entry of the handler so that nothing odd (traversal segments, backslashes, control characters, empty
 * segments) ever reaches the blob store with a different meaning than the client intended.
 */
public final class RawPaths {

    /** Longer paths are refused; the asset table allows 2000 characters, this keeps URLs sane. */
    public static final int MAX_LENGTH = 1024;

    private RawPaths() {
    }

    /**
     * Returns the path unchanged when it is acceptable and throws {@link IllegalArgumentException} naming the
     * problem otherwise. The empty path (the repository root) and paths ending in {@code /} (directories) are
     * accepted.
     */
    public static String validate(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        if (path.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("path is longer than " + MAX_LENGTH + " characters");
        }
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("path must not start with a slash");
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\') {
                throw new IllegalArgumentException("backslashes are not allowed");
            }
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("control characters are not allowed");
            }
        }
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean last = i == segments.length - 1;
            if (segment.isEmpty() && !last) {
                throw new IllegalArgumentException("empty path segment");
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("'.' and '..' segments are not allowed");
            }
        }
        return path;
    }

    /**
     * Whether the still percent-encoded request URI hides a path separator ({@code %2F}) or a backslash
     * ({@code %5C}). The server decodes the path before the handler sees it, so {@code a%2Fb.txt} would
     * otherwise silently become the file {@code b.txt} in the directory {@code a/}.
     */
    public static boolean hasEncodedSeparator(String rawUri) {
        if (rawUri == null) {
            return false;
        }
        String lower = rawUri.toLowerCase(Locale.ROOT);
        return lower.contains("%2f") || lower.contains("%5c");
    }

    /** Whether the path addresses a directory: the root or anything ending in a slash. */
    public static boolean isDirectory(String path) {
        return path.isEmpty() || path.endsWith("/");
    }

    public static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** The directory part including its trailing slash, or {@code ""} for a root-level file. */
    public static String directory(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    /** The non-empty segments of a path. */
    public static List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                result.add(segment);
            }
        }
        return result;
    }

    public static String withoutTrailingSlash(String path) {
        String p = path;
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** Joins a directory (with or without trailing slash) and a file name. */
    public static String join(String directory, String name) {
        if (directory.isEmpty()) {
            return name;
        }
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }
}
