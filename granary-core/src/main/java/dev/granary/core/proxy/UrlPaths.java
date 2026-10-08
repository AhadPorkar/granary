package dev.granary.core.proxy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Percent-encoding helpers for building upstream URLs from repository paths. */
public final class UrlPaths {

    private static final String UNRESERVED = "-._~";
    private static final String SUB_DELIMS = "!$&'()*+,;=:@";

    private UrlPaths() {
    }

    /** Joins {@code base} and {@code path}, encoding each path segment but keeping {@code /} separators. */
    public static String join(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (path == null || path.isEmpty()) {
            return b + "/";
        }
        StringBuilder sb = new StringBuilder(b);
        for (String segment : path.split("/", -1)) {
            sb.append('/').append(encodeSegment(segment));
        }
        return sb.toString();
    }

    /** Encodes one path segment per RFC 3986: unreserved and sub-delimiters pass through, the rest is percent-encoded. */
    public static String encodeSegment(String segment) {
        StringBuilder sb = new StringBuilder(segment.length() + 8);
        for (byte raw : segment.getBytes(StandardCharsets.UTF_8)) {
            int b = raw & 0xff;
            if (isAlnum(b) || UNRESERVED.indexOf(b) >= 0 || SUB_DELIMS.indexOf(b) >= 0) {
                sb.append((char) b);
            } else {
                sb.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) b));
            }
        }
        return sb.toString();
    }

    private static boolean isAlnum(int b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9');
    }
}
