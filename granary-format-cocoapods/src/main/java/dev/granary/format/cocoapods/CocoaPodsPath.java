package dev.granary.format.cocoapods;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A repository-relative path of a CDN-style spec repository, classified.
 *
 * <ul>
 *   <li>{@code CocoaPods-version.yml}, {@code all_pods.txt}, {@code deprecated_podspecs.txt} and the shard index
 *       {@code all_pods_versions_a_7_5.txt}: mutable index documents;</li>
 *   <li>{@code Specs/a/7/5/<Name>/<version>/<Name>.podspec.json}: a podspec (the short form without the shard
 *       directories is accepted too and mapped to the sharded one);</li>
 *   <li>{@code Specs/[a/7/5/]<Name>/<version>}: a version, the target of {@code DELETE};</li>
 *   <li>{@code sources/<Name>/<version>/<file>}: a source archive cached or uploaded here;</li>
 *   <li>{@code api/pods}: the multipart push endpoint.</li>
 * </ul>
 * Anything that could change its meaning on the way to the blob store (backslashes, dot segments, control
 * characters, empty segments) is {@link Kind#INVALID}.
 *
 * @param kind     what the path addresses
 * @param fragment the shard directories or the shard index fragment ({@code [a, 7, 5]}), else empty
 * @param name     the pod name for podspecs, versions and sources, else {@code null}
 * @param version  the version for podspecs, versions and sources, else {@code null}
 * @param filename the archive file name for sources, else {@code null}
 */
public record CocoaPodsPath(Kind kind, List<String> fragment, String name, String version, String filename) {

    public static final String ALL_PODS = "all_pods.txt";
    public static final String DEPRECATED = "deprecated_podspecs.txt";
    public static final String PODSPEC_SUFFIX = ".podspec.json";
    public static final String SOURCES = "sources/";
    public static final String API_PODS = "api/pods";

    private static final Pattern SHARD = Pattern.compile("all_pods_versions((?:_[0-9a-f]+)*)\\.txt");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]+");

    public enum Kind {
        ROOT, VERSION_FILE, SHARD, ALL_PODS, DEPRECATED, PODSPEC, VERSION, SOURCE, API_PODS, INVALID, UNKNOWN
    }

    public CocoaPodsPath {
        fragment = fragment == null ? List.of() : List.copyOf(fragment);
    }

    private static CocoaPodsPath of(Kind kind) {
        return new CocoaPodsPath(kind, List.of(), null, null, null);
    }

    /** A path refused before parsing, e.g. because the raw request carried an encoded separator. */
    public static CocoaPodsPath invalid() {
        return of(Kind.INVALID);
    }

    public static CocoaPodsPath parse(String path) {
        if (path == null || path.isEmpty()) {
            return of(Kind.ROOT);
        }
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (p.isEmpty() || p.startsWith("/")) {
            return of(Kind.INVALID);
        }
        String[] segments = p.split("/", -1);
        for (String segment : segments) {
            if (!PodNames.isSafeSegment(segment)) {
                return of(Kind.INVALID);
            }
        }
        if (segments.length == 1) {
            return switch (p) {
                case VersionFile.PATH -> of(Kind.VERSION_FILE);
                case ALL_PODS -> of(Kind.ALL_PODS);
                case DEPRECATED -> of(Kind.DEPRECATED);
                default -> shard(p);
            };
        }
        if (p.equals(API_PODS)) {
            return of(Kind.API_PODS);
        }
        if (segments[0].equals("Specs")) {
            return specs(Arrays.copyOfRange(segments, 1, segments.length));
        }
        if (segments[0].equals("sources") && segments.length == 4) {
            return new CocoaPodsPath(Kind.SOURCE, List.of(), segments[1], segments[2], segments[3]);
        }
        return of(Kind.UNKNOWN);
    }

    private static CocoaPodsPath shard(String file) {
        var m = SHARD.matcher(file);
        if (!m.matches()) {
            return of(Kind.UNKNOWN);
        }
        String group = m.group(1);
        List<String> fragment = group.isEmpty() ? List.of() : List.of(group.substring(1).split("_"));
        return new CocoaPodsPath(Kind.SHARD, fragment, null, null, null);
    }

    /** {@code [fragment…, Name, version, Name.podspec.json]} or {@code [fragment…, Name, version]}. */
    private static CocoaPodsPath specs(String[] s) {
        int n = s.length;
        if (n >= 3 && s[n - 1].endsWith(PODSPEC_SUFFIX)) {
            String name = s[n - 3];
            if (!s[n - 1].equals(name + PODSPEC_SUFFIX)) {
                return of(Kind.UNKNOWN);
            }
            List<String> fragment = hexFragment(s, n - 3);
            return fragment == null ? of(Kind.UNKNOWN) : new CocoaPodsPath(Kind.PODSPEC, fragment, name, s[n - 2], null);
        }
        if (n >= 2 && PodVersion.isValid(s[n - 1])) {
            List<String> fragment = hexFragment(s, n - 2);
            return fragment == null ? of(Kind.UNKNOWN) : new CocoaPodsPath(Kind.VERSION, fragment, s[n - 2], s[n - 1], null);
        }
        return of(Kind.UNKNOWN);
    }

    private static List<String> hexFragment(String[] s, int end) {
        List<String> fragment = new ArrayList<>(end);
        for (int i = 0; i < end; i++) {
            if (!HEX.matcher(s[i]).matches()) {
                return null;
            }
            fragment.add(s[i]);
        }
        return fragment;
    }

    /** Index documents change with every push upstream or here; podspecs and source archives do not. */
    public boolean isMetadata() {
        return switch (kind) {
            case VERSION_FILE, SHARD, ALL_PODS, DEPRECATED -> true;
            default -> false;
        };
    }

    /** {@code sources/<Name>/<version>/<file>}. */
    public static String sourcePath(String name, String version, String filename) {
        return SOURCES + name + "/" + version + "/" + filename;
    }

    /** {@code sources/<Name>/<version>/} – every archive of a version. */
    public static String sourceDirectory(String name, String version) {
        return SOURCES + name + "/" + version + "/";
    }
}
