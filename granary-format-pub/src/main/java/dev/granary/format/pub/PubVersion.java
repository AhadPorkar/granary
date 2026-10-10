package dev.granary.format.pub;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A package version with the semantics of Dart's {@code pub_semver}: {@code major.minor.patch}, an optional
 * pre-release ({@code -dev.1}) and optional build metadata ({@code +3}). Unlike strict SemVer 2.0, build
 * metadata takes part in ordering ({@code 1.0.0 < 1.0.0+1}), exactly as the pub client and pub.dev order
 * versions; dot-separated parts compare numerically when they are numbers and numbers sort before words.
 *
 * <p>Two versions that compare equal ({@code 1.0.0+01} and {@code 1.0.0+1}) are the same version to the pub
 * client, so a repository must never list both.
 *
 * @param major      the major number
 * @param minor      the minor number
 * @param patch      the patch number
 * @param preRelease the pre-release parts, each a {@link Long} or a {@link String}; empty for a release
 * @param build      the build parts, each a {@link Long} or a {@link String}; empty without build metadata
 * @param text       the version exactly as written
 */
public record PubVersion(long major, long minor, long patch, List<Object> preRelease, List<Object> build, String text)
        implements Comparable<PubVersion> {

    /** The grammar of {@code pub_semver}'s {@code completeVersion}. */
    private static final Pattern GRAMMAR = Pattern.compile(
            "^(\\d+)\\.(\\d+)\\.(\\d+)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");

    /** Longer strings are not versions anyone writes; refusing them keeps paths and documents sane. */
    static final int MAX_LENGTH = 128;

    public PubVersion {
        preRelease = List.copyOf(preRelease);
        build = List.copyOf(build);
    }

    /** Parses {@code text}, or answers empty when it is not a pub version. */
    public static Optional<PubVersion> parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        Matcher m = GRAMMAR.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PubVersion(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)),
                    Long.parseLong(m.group(3)), parts(m.group(4)), parts(m.group(5)), text));
        } catch (NumberFormatException tooLarge) {
            return Optional.empty();
        }
    }

    public static boolean isValid(String text) {
        return parse(text).isPresent();
    }

    /** Splits a pre-release or build string into its dot-separated parts, numbers as {@link Long}. */
    private static List<Object> parts(String text) {
        if (text == null) {
            return List.of();
        }
        List<Object> parts = new ArrayList<>();
        for (String part : text.split("\\.", -1)) {
            parts.add(numeric(part).<Object>map(n -> n).orElse(part));
        }
        return Collections.unmodifiableList(parts);
    }

    /** Dart's {@code int.tryParse} for the characters a version part may hold: digits that fit 64 bits. */
    private static Optional<Long> numeric(String part) {
        if (part.isEmpty()) {
            return Optional.empty();
        }
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < '0' || c > '9') {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(Long.parseLong(part));
        } catch (NumberFormatException tooLarge) {
            return Optional.empty();
        }
    }

    public boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    /** Same version to the pub client, whatever the spelling ({@code 1.0.0+01} and {@code 1.0.0+1}). */
    public boolean sameAs(PubVersion other) {
        return compareTo(other) == 0;
    }

    @Override
    public int compareTo(PubVersion other) {
        if (major != other.major) {
            return Long.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Long.compare(minor, other.minor);
        }
        if (patch != other.patch) {
            return Long.compare(patch, other.patch);
        }
        // a pre-release comes before the release it leads up to
        if (!isPreRelease() && other.isPreRelease()) {
            return 1;
        }
        if (isPreRelease() && !other.isPreRelease()) {
            return -1;
        }
        int byPreRelease = compareParts(preRelease, other.preRelease);
        if (byPreRelease != 0) {
            return byPreRelease;
        }
        // build metadata comes after no build metadata
        if (build.isEmpty() && !other.build.isEmpty()) {
            return -1;
        }
        if (!build.isEmpty() && other.build.isEmpty()) {
            return 1;
        }
        return compareParts(build, other.build);
    }

    private static int compareParts(List<Object> a, List<Object> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            Object x = i < a.size() ? a.get(i) : null;
            Object y = i < b.size() ? b.get(i) : null;
            if (x == null && y == null || x != null && x.equals(y)) {
                continue;
            }
            if (x == null) {
                return -1;
            }
            if (y == null) {
                return 1;
            }
            if (x instanceof Long lx) {
                return y instanceof Long ly ? Long.compare(lx, ly) : -1;
            }
            if (y instanceof Long) {
                return 1;
            }
            return Integer.signum(((String) x).compareTo((String) y));
        }
        return 0;
    }

    /**
     * The version pub.dev calls {@code latest}: the highest release that is not retracted; when every
     * candidate is a pre-release, the highest pre-release. Retracted versions only count when nothing else
     * is left.
     *
     * @param versions  the candidates
     * @param retracted the subset of candidates that are retracted (may be empty)
     */
    public static Optional<PubVersion> latest(Collection<PubVersion> versions, Collection<PubVersion> retracted) {
        List<PubVersion> live = versions.stream().filter(v -> retracted.stream().noneMatch(v::sameAs)).toList();
        List<PubVersion> pool = live.isEmpty() ? List.copyOf(versions) : live;
        Optional<PubVersion> release = pool.stream().filter(v -> !v.isPreRelease()).max(PubVersion::compareTo);
        return release.isPresent() ? release : pool.stream().max(PubVersion::compareTo);
    }

    @Override
    public String toString() {
        return text;
    }
}
