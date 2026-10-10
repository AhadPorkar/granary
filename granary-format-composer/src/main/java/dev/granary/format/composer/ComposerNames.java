package dev.granary.format.composer;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Composer package names ({@code vendor/name}). The rule is the one Composer's {@code ValidatingArrayLoader}
 * applies and Packagist enforces; names are compared and stored in lower case because Composer always asks for
 * {@code p2/<lower-case name>.json}.
 */
public final class ComposerNames {

    /** {@code ValidatingArrayLoader}: {@code ^[a-z0-9]([_.-]?[a-z0-9]+)*\/[a-z0-9](([_.]|-{1,2})?[a-z0-9]+)*$}, case-insensitive. */
    private static final Pattern NAME = Pattern.compile(
            "^[a-z0-9]([_.-]?[a-z0-9]+)*+/[a-z0-9](([_.]|-{1,2})?[a-z0-9]+)*+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VENDOR = Pattern.compile("^[a-z0-9]([_.-]?[a-z0-9]+)*+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROJECT = Pattern.compile("^[a-z0-9](([_.]|-{1,2})?[a-z0-9]+)*+$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_LENGTH = 214;

    private ComposerNames() {
    }

    public static boolean isValid(String name) {
        return name != null && name.length() <= MAX_LENGTH && NAME.matcher(name).matches();
    }

    public static boolean isValidVendor(String vendor) {
        return vendor != null && vendor.length() <= MAX_LENGTH && VENDOR.matcher(vendor).matches();
    }

    public static boolean isValidProject(String project) {
        return project != null && project.length() <= MAX_LENGTH && PROJECT.matcher(project).matches();
    }

    /** The lower-case form of a valid name, or empty. */
    public static Optional<String> normalise(String name) {
        if (!isValid(name)) {
            return Optional.empty();
        }
        return Optional.of(name.toLowerCase(Locale.ROOT));
    }

    /** {@code vendor} and {@code name} of a valid, lower-case package name. */
    public static String vendor(String name) {
        return name.substring(0, name.indexOf('/'));
    }

    public static String project(String name) {
        return name.substring(name.indexOf('/') + 1);
    }
}
