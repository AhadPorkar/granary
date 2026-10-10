package dev.granary.format.pypi;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project names and distribution file names as the Python packaging standards define them.
 *
 * <p>PEP 503 normalisation ({@code re.sub(r"[-_.]+", "-", name).lower()}) is applied to every name before
 * it is compared, stored or looked up, so {@code Django}, {@code django} and {@code DJANGO} are the same
 * project and {@code zope.interface}, {@code zope_interface} and {@code zope-interface} are too.
 */
public final class PypiNames {

    /** PEP 508 project name: letters, digits, dots, hyphens and underscores, starting and ending with a letter or digit. */
    private static final Pattern PROJECT_NAME = Pattern.compile("(?i)^([A-Z0-9]|[A-Z0-9][A-Z0-9._-]*[A-Z0-9])$");
    private static final Pattern NORMALISE = Pattern.compile("[-_.]+");
    /** Characters that may appear in a distribution file name or a path segment under {@code packages/}. */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._+!~-]*$");
    /** {@code {name}-{version}(-{build})?-{python}-{abi}-{platform}.whl}, per the binary distribution format spec. */
    private static final Pattern WHEEL = Pattern.compile(
            "^(?<name>[^-]+)-(?<version>[^-]+)(?:-(?<build>\\d[^-]*))?-(?<python>[^-]+)-(?<abi>[^-]+)-(?<platform>[^-]+)\\.whl$");
    private static final Pattern SDIST = Pattern.compile("^(?<name>.+)-(?<version>[^-]+)\\.(?<ext>tar\\.gz|zip|tar\\.bz2|tar\\.xz|tar)$");

    private PypiNames() {
    }

    /** PEP 503 normalisation; {@code null} and blank names become the empty string. */
    public static String normalise(String name) {
        if (name == null) {
            return "";
        }
        return NORMALISE.matcher(name.trim()).replaceAll("-").toLowerCase(Locale.ROOT);
    }

    /** Whether {@code name} is a syntactically valid project name (before or after normalisation). */
    public static boolean isValidName(String name) {
        return name != null && name.length() <= 255 && PROJECT_NAME.matcher(name).matches();
    }

    /** Whether a path segment under {@code packages/} or a file name is harmless: no slashes, no dot segments, no controls. */
    public static boolean isSafeSegment(String segment) {
        return segment != null && segment.length() <= 255 && SAFE_SEGMENT.matcher(segment).matches();
    }

    /** The kind of distribution a file name denotes. */
    public enum DistributionType {
        SDIST("sdist"), WHEEL("bdist_wheel");

        private final String uploadName;

        DistributionType(String uploadName) {
            this.uploadName = uploadName;
        }

        /** The {@code filetype} value the upload API uses for this kind of file. */
        public String uploadName() {
            return uploadName;
        }

        public static Optional<DistributionType> fromUploadName(String filetype) {
            for (DistributionType t : values()) {
                if (t.uploadName.equalsIgnoreCase(filetype == null ? "" : filetype.trim())) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * What a distribution file name says about itself.
     *
     * @param filename      the file name as given
     * @param name          the project name as spelled in the file name (not normalised)
     * @param version       the version as spelled in the file name
     * @param type          wheel or sdist
     * @param pythonTag     the wheel's python tag ({@code py3}, {@code cp312}), {@code source} for an sdist
     */
    public record Distribution(String filename, String name, String version, DistributionType type, String pythonTag) {

        public String normalisedName() {
            return normalise(name);
        }
    }

    /** Parses a wheel or sdist file name; empty for anything that is not a distribution file. */
    public static Optional<Distribution> parseFilename(String filename) {
        if (filename == null || !isSafeSegment(filename)) {
            return Optional.empty();
        }
        Matcher wheel = WHEEL.matcher(filename);
        if (wheel.matches()) {
            return Optional.of(new Distribution(filename, wheel.group("name"), wheel.group("version"),
                    DistributionType.WHEEL, wheel.group("python")));
        }
        Matcher sdist = SDIST.matcher(filename);
        if (sdist.matches()) {
            return Optional.of(new Distribution(filename, sdist.group("name"), sdist.group("version"),
                    DistributionType.SDIST, "source"));
        }
        return Optional.empty();
    }

    /** Whether the file name belongs to a distribution rather than to a sidecar such as {@code .metadata} or {@code .asc}. */
    public static boolean isDistributionFile(String filename) {
        return parseFilename(filename).isPresent();
    }

    /** {@code six-1.16.0-py2.py3-none-any.whl.metadata} → {@code six-1.16.0-py2.py3-none-any.whl}; empty for other names. */
    public static Optional<String> distributionOfSidecar(String filename) {
        if (filename != null && filename.endsWith(PypiHosted.METADATA_SUFFIX)) {
            String base = filename.substring(0, filename.length() - PypiHosted.METADATA_SUFFIX.length());
            return isDistributionFile(base) ? Optional.of(base) : Optional.empty();
        }
        return Optional.empty();
    }
}
