package dev.granary.format.rubygems;

import java.util.Optional;
import java.util.regex.Pattern;

/** Gem names, full names ({@code name-version[-platform]}) and the file names derived from them. */
public final class GemNames {

    /** {@code Gem::SpecificationPolicy::VALID_NAME_PATTERN}, plus a length limit. */
    private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9._-]{1,128}$");
    private static final Pattern LETTER = Pattern.compile("[a-zA-Z]");
    /** A full name as it appears in a file name: what names, versions and platforms may contain. */
    private static final Pattern FULL_NAME = Pattern.compile("^[a-zA-Z0-9._+-]{3,320}$");
    /** What follows the name in a full name: a version without dashes, then optionally a platform starting with a letter. */
    private static final Pattern VERSION_AND_PLATFORM = Pattern.compile("^([0-9][0-9a-zA-Z.]*)(?:-([A-Za-z_][A-Za-z0-9_.+-]*))?$");

    public static final String GEM_SUFFIX = ".gem";
    public static final String QUICK_PREFIX = "quick/Marshal.4.8/";
    public static final String QUICK_SUFFIX = ".gemspec.rz";
    public static final String GEMS_PREFIX = "gems/";

    private GemNames() {
    }

    /** A name RubyGems accepts: letters, digits, {@code . - _}, at least one letter, not {@code .} or {@code ..}. */
    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches() && LETTER.matcher(name).find()
                && !name.equals(".") && !name.equals("..");
    }

    /**
     * A name a new gem may get: {@link #isValidName} and, as {@code Gem::SpecificationPolicy} requires, not beginning
     * with a period, dash or underscore. Reads stay with {@link #isValidName}, because rubygems.org still serves a few
     * gems published before that rule.
     */
    public static boolean isPublishableName(String name) {
        return isValidName(name) && ".-_".indexOf(name.charAt(0)) < 0;
    }

    /** Whether {@code fullName} is safe to use as (part of) a file name. */
    public static boolean isSafeFullName(String fullName) {
        return fullName != null && FULL_NAME.matcher(fullName).matches() && !fullName.startsWith(".")
                && !fullName.contains("..");
    }

    /** {@code Gem::Specification#full_name}: {@code name-version}, plus {@code -platform} unless it is {@code ruby}. */
    public static String fullName(String name, String version, String platform) {
        return GemPlatform.isRuby(platform) ? name + "-" + version : name + "-" + version + "-" + platform;
    }

    /** {@code version} or {@code version-platform}: the key of a version within a gem in the compact index. */
    public static String versionKey(String version, String platform) {
        return GemPlatform.isRuby(platform) ? version : version + "-" + platform;
    }

    public static String gemPath(String fullName) {
        return GEMS_PREFIX + fullName + GEM_SUFFIX;
    }

    public static String quickSpecPath(String fullName) {
        return QUICK_PREFIX + fullName + QUICK_SUFFIX;
    }

    /** A full name split into its parts. */
    public record FullName(String name, String version, String platform) {
        public String fullName() {
            return GemNames.fullName(name, version, platform);
        }
    }

    /**
     * Splits a full name whose gem name is not known. Gem names may contain dashes and digits, so this takes the
     * first dash after which a version (starting with a digit) and optionally a platform (starting with a letter)
     * follow, which is right for every name in practice; use {@link #split(String, String)} when the name is known.
     */
    public static Optional<FullName> parse(String fullName) {
        if (!isSafeFullName(fullName)) {
            return Optional.empty();
        }
        for (int i = fullName.indexOf('-'); i > 0; i = fullName.indexOf('-', i + 1)) {
            Optional<FullName> candidate = split(fullName.substring(0, i), fullName);
            if (candidate.isPresent()) {
                return candidate;
            }
        }
        return Optional.empty();
    }

    /** Splits a full name that must belong to gem {@code name}. */
    public static Optional<FullName> split(String name, String fullName) {
        if (fullName == null || !isValidName(name) || !fullName.startsWith(name + "-")) {
            return Optional.empty();
        }
        var m = VERSION_AND_PLATFORM.matcher(fullName.substring(name.length() + 1));
        if (!m.matches() || !GemVersion.isValid(m.group(1))) {
            return Optional.empty();
        }
        String platform = m.group(2) == null ? GemPlatform.RUBY : m.group(2);
        if (!GemPlatform.isValid(platform)) {
            return Optional.empty();
        }
        return Optional.of(new FullName(name, m.group(1), platform));
    }
}
