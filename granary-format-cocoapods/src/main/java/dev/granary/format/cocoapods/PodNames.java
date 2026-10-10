package dev.granary.format.cocoapods;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Pod names and path segments. The public spec repository holds names such as {@code !ProtoCompiler},
 * {@code +verify}, {@code ID.me WebVerify} or emoji, so anything that is a single, harmless path segment
 * is accepted when reading; names pushed to a hosted repository follow the CocoaPods linter (no slash,
 * no whitespace, no leading period) and additionally avoid the characters URLs and HTTP firewalls trip over.
 */
public final class PodNames {

    /** Longest pod name, version or file name accepted in a path; far beyond anything real. */
    public static final int MAX_SEGMENT_LENGTH = 255;

    /** Orders by Unicode code point, which equals the byte order of UTF-8 (the order of the CDN's lists). */
    public static final Comparator<String> CODE_POINT_ORDER = PodNames::compareCodePoints;

    private PodNames() {
    }

    /** One path segment that cannot change the meaning of a path: no separators, no dot segments, no control characters. */
    public static boolean isSafeSegment(String segment) {
        if (segment == null || segment.isEmpty() || segment.length() > MAX_SEGMENT_LENGTH
                || segment.equals(".") || segment.equals("..")) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Why {@code name} cannot be pushed to a hosted repository, or empty when it can. The CocoaPods linter rejects
     * slashes, whitespace and a leading period; {@code % ? # ;} and backslashes are refused too because clients
     * would mangle them in URLs.
     */
    public static Optional<String> pushProblem(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.of("The podspec has no name");
        }
        if (name.length() > 200) {
            return Optional.of("The pod name is longer than 200 characters");
        }
        if (name.startsWith(".")) {
            return Optional.of("The name of a pod must not begin with a period");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                return Optional.of("The name of a pod must not contain whitespace");
            }
            if (c == '/' || c == '\\') {
                return Optional.of("The name of a pod must not contain a slash");
            }
            if (Character.isISOControl(c) || "%?#;".indexOf(c) >= 0) {
                return Optional.of("The name of a pod must not contain control characters or any of % ? # ;");
            }
        }
        return Optional.empty();
    }

    /** Why {@code version} cannot be pushed, or empty when it is a version CocoaPods accepts. */
    public static Optional<String> versionProblem(String version) {
        if (version == null || version.isEmpty()) {
            return Optional.of("The podspec has no version");
        }
        if (version.length() > 100) {
            return Optional.of("The version is longer than 100 characters");
        }
        if (!version.equals(version.strip()) || !PodVersion.isValid(version)) {
            return Optional.of("'" + version + "' is not a valid CocoaPods version");
        }
        return Optional.empty();
    }

    /** {@code Digest::MD5.hexdigest(name)}: the lower-case hex MD5 of the UTF-8 name, which picks the shard. */
    public static String md5Hex(String name) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md5.digest(name.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i);
            int y = b.codePointAt(j);
            if (x != y) {
                return Integer.compare(x, y);
            }
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
