package dev.granary.format.rubygems;

import dev.granary.format.rubygems.marshal.Ruby;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One entry of {@code specs.4.8}, {@code latest_specs.4.8} or {@code prerelease_specs.4.8}: the marshalled
 * {@code [name, Gem::Version, platform]} triple that {@code Gem::NameTuple#to_a} produces.
 *
 * @param name     gem name
 * @param version  version string
 * @param platform {@code ruby} or a platform string
 */
public record SpecTuple(String name, String version, String platform) implements Comparable<SpecTuple> {

    public SpecTuple {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        platform = platform == null || platform.isEmpty() ? GemPlatform.RUBY : platform;
    }

    /** The tuple of a marshalled array element, or {@code null} when the element is not a usable tuple. */
    public static SpecTuple fromMarshal(Object element) {
        if (!(element instanceof List<?> list) || list.size() < 3 || !(list.get(0) instanceof String name)) {
            return null;
        }
        String version = Ruby.gemVersionString(list.get(1));
        if (version == null || version.isEmpty()) {
            return null;
        }
        // platform strings are kept exactly as written, so the same tuple read from two indexes compares equal
        Object platform = list.get(2);
        String p = platform instanceof String s ? s : platform == null ? GemPlatform.RUBY : GemPlatform.fromMarshal(platform);
        return new SpecTuple(name, version, p == null || p.isEmpty() ? GemPlatform.RUBY : p);
    }

    /** {@code [name, Gem::Version, platform]} as {@code Gem::NameTuple#to_a} marshals it. */
    public List<Object> toMarshal() {
        List<Object> tuple = new ArrayList<>(3);
        tuple.add(name);
        tuple.add(Ruby.gemVersion(version));
        tuple.add(platform);
        return tuple;
    }

    /** Identity of the tuple: name, version and platform. */
    public String key() {
        return name + ' ' + version + ' ' + platform;
    }

    /** Identity of the "latest" slot the tuple competes for: name and platform. */
    public String latestKey() {
        return name + ' ' + platform;
    }

    public boolean isPrerelease() {
        return GemVersion.isPrerelease(version);
    }

    public String fullName() {
        return GemNames.fullName(name, version, platform);
    }

    /** By name, then version (RubyGems ordering), then platform with {@code ruby} first. */
    @Override
    public int compareTo(SpecTuple other) {
        int c = name.compareTo(other.name);
        if (c != 0) {
            return c;
        }
        c = GemVersion.compareStrings(version, other.version);
        if (c != 0) {
            return c;
        }
        boolean ruby = GemPlatform.isRuby(platform);
        boolean otherRuby = GemPlatform.isRuby(other.platform);
        if (ruby != otherRuby) {
            return ruby ? -1 : 1;
        }
        return platform.compareTo(other.platform);
    }
}
