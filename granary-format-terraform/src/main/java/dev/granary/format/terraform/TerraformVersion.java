package dev.granary.format.terraform;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A semantic version the way the Terraform registries use them: {@code MAJOR.MINOR.PATCH} with an optional
 * pre-release ({@code 1.0.0-beta.2}) and build metadata ({@code 1.0.0+build.7}). A leading {@code v} is
 * tolerated on input and dropped on output. Ordering follows semver 2.0: pre-releases sort before the
 * release, identifiers are compared numerically when both are numbers, and build metadata is ignored.
 */
public final class TerraformVersion implements Comparable<TerraformVersion> {

    private static final Pattern SEMVER = Pattern.compile(
            "^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");

    /** Versions that parse come first in natural order; anything else is ordered lexically after them. */
    public static final Comparator<String> STRING_ORDER = (a, b) -> {
        Optional<TerraformVersion> va = tryParse(a);
        Optional<TerraformVersion> vb = tryParse(b);
        if (va.isPresent() && vb.isPresent()) {
            int c = va.get().compareTo(vb.get());
            return c != 0 ? c : a.compareTo(b);
        }
        if (va.isPresent()) {
            return -1;
        }
        if (vb.isPresent()) {
            return 1;
        }
        return a.compareTo(b);
    };

    private final long major;
    private final long minor;
    private final long patch;
    private final String prerelease;
    private final String build;

    private TerraformVersion(long major, long minor, long patch, String prerelease, String build) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
        this.build = build;
    }

    public static Optional<TerraformVersion> tryParse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = SEMVER.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TerraformVersion(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)),
                    Long.parseLong(m.group(3)), m.group(4), m.group(5)));
        } catch (NumberFormatException tooLong) {
            return Optional.empty();
        }
    }

    public static boolean isValid(String text) {
        return tryParse(text).isPresent();
    }

    public long major() {
        return major;
    }

    public long minor() {
        return minor;
    }

    public long patch() {
        return patch;
    }

    public boolean isPrerelease() {
        return prerelease != null;
    }

    public Optional<String> prerelease() {
        return Optional.ofNullable(prerelease);
    }

    @Override
    public int compareTo(TerraformVersion o) {
        int c = Long.compare(major, o.major);
        if (c != 0) {
            return c;
        }
        c = Long.compare(minor, o.minor);
        if (c != 0) {
            return c;
        }
        c = Long.compare(patch, o.patch);
        if (c != 0) {
            return c;
        }
        if (prerelease == null || o.prerelease == null) {
            return prerelease == null ? (o.prerelease == null ? 0 : 1) : -1;
        }
        return comparePrerelease(prerelease, o.prerelease);
    }

    private static int comparePrerelease(String a, String b) {
        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        int n = Math.min(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            boolean an = isNumeric(as[i]);
            boolean bn = isNumeric(bs[i]);
            int c;
            if (an && bn) {
                c = Long.compare(Long.parseLong(as[i]), Long.parseLong(bs[i]));
            } else if (an) {
                c = -1;
            } else if (bn) {
                c = 1;
            } else {
                c = as[i].compareTo(bs[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(as.length, bs.length);
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty() || s.length() > 18) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** The canonical form: no leading {@code v}, pre-release and build metadata kept. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append(major).append('.').append(minor).append('.').append(patch);
        if (prerelease != null) {
            sb.append('-').append(prerelease);
        }
        if (build != null) {
            sb.append('+').append(build);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TerraformVersion v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        return (prerelease == null ? "" : prerelease.toLowerCase(Locale.ROOT)).hashCode()
                ^ Long.hashCode(major * 31 + minor * 17 + patch);
    }
}
