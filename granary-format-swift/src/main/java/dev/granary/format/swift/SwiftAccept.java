package dev.granary.format.swift;

import java.util.Locale;
import java.util.Set;

/**
 * API version negotiation through the {@code Accept} header:
 * {@code application/vnd.swift.registry[.v<version>][+json|+zip|+swift]}.
 *
 * <p>Only version 1 exists. A request without any registry media type (no {@code Accept}, {@code *}{@code /*},
 * a browser) is served with version 1, which the specification allows. When every registry media type in the
 * header names another version the request fails: {@code 415} for a well-formed but unsupported version,
 * {@code 400} for a malformed one.
 */
public final class SwiftAccept {

    public static final String MEDIA_TYPE_PREFIX = "application/vnd.swift.registry";
    public static final String JSON = MEDIA_TYPE_PREFIX + ".v1+json";
    public static final String ZIP = MEDIA_TYPE_PREFIX + ".v1+zip";
    public static final String SWIFT = MEDIA_TYPE_PREFIX + ".v1+swift";

    private static final Set<String> MEDIA_TYPES = Set.of("json", "zip", "swift");

    public enum Result { SUPPORTED, UNSUPPORTED_VERSION, INVALID_VERSION }

    private SwiftAccept() {
    }

    public static Result check(String accept) {
        if (accept == null || accept.isBlank()) {
            return Result.SUPPORTED;
        }
        boolean registry = false;
        boolean unsupported = false;
        for (String range : accept.split(",")) {
            String type = range.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!type.startsWith(MEDIA_TYPE_PREFIX)) {
                continue;
            }
            registry = true;
            Result r = checkOne(type.substring(MEDIA_TYPE_PREFIX.length()));
            if (r == Result.SUPPORTED) {
                return Result.SUPPORTED;
            }
            unsupported |= r == Result.UNSUPPORTED_VERSION;
        }
        if (!registry) {
            return Result.SUPPORTED;
        }
        return unsupported ? Result.UNSUPPORTED_VERSION : Result.INVALID_VERSION;
    }

    /** {@code rest} is what follows {@code application/vnd.swift.registry}: {@code ""}, {@code .v1}, {@code +json}, {@code .v1+zip}... */
    private static Result checkOne(String rest) {
        String version = null;
        String media = null;
        int plus = rest.indexOf('+');
        String head = plus >= 0 ? rest.substring(0, plus) : rest;
        if (plus >= 0) {
            media = rest.substring(plus + 1);
        }
        if (!head.isEmpty()) {
            if (!head.startsWith(".v")) {
                return Result.INVALID_VERSION;
            }
            version = head.substring(2);
        }
        if (media != null && !MEDIA_TYPES.contains(media)) {
            return Result.INVALID_VERSION;
        }
        if (version == null || version.equals("1")) {
            return Result.SUPPORTED;
        }
        return !version.isEmpty() && version.chars().allMatch(c -> c >= '0' && c <= '9')
                ? Result.UNSUPPORTED_VERSION : Result.INVALID_VERSION;
    }
}
