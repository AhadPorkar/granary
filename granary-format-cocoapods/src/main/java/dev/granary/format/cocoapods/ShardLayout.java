package dev.granary.format.cocoapods;

import java.util.ArrayList;
import java.util.List;

/**
 * How a CDN-style spec repository shards its pods, from {@code prefix_lengths} in {@code CocoaPods-version.yml}.
 * The MD5 hex digest of the pod name is cut into prefixes of these lengths; they name the index file
 * ({@code all_pods_versions_a_7_5.txt}) and the directories above the pod ({@code Specs/a/7/5/AFNetworking}).
 * The public CDN, and every hosted repository and group here, uses {@code [1, 1, 1]}.
 *
 * @param prefixLengths lengths of the hex prefixes, possibly empty (no sharding)
 */
public record ShardLayout(List<Integer> prefixLengths) {

    public static final ShardLayout STANDARD = new ShardLayout(List.of(1, 1, 1));

    public static final String SPECS = "Specs/";
    public static final String SHARD_PREFIX = "all_pods_versions";
    public static final String SHARD_SUFFIX = ".txt";

    /** At most this many hex characters in total; an MD5 digest has 32. */
    public static final int MAX_TOTAL_LENGTH = 32;

    public ShardLayout {
        prefixLengths = List.copyOf(prefixLengths);
        int total = 0;
        for (int length : prefixLengths) {
            if (length < 1) {
                throw new IllegalArgumentException("prefix lengths must be positive: " + prefixLengths);
            }
            total += length;
        }
        if (total > MAX_TOTAL_LENGTH) {
            throw new IllegalArgumentException("prefix lengths exceed an MD5 digest: " + prefixLengths);
        }
    }

    /** Total number of hex characters the shards are keyed by. */
    public int totalLength() {
        return prefixLengths.stream().mapToInt(Integer::intValue).sum();
    }

    /** {@code Metadata#path_fragment(name)} without the name: the hex prefixes of the pod's MD5. */
    public List<String> fragment(String podName) {
        return split(PodNames.md5Hex(podName));
    }

    /** Cuts the first {@link #totalLength()} characters of {@code hex} into this layout's prefixes. */
    public List<String> split(String hex) {
        if (hex.length() < totalLength()) {
            throw new IllegalArgumentException("'" + hex + "' is shorter than the layout " + prefixLengths);
        }
        List<String> result = new ArrayList<>(prefixLengths.size());
        int at = 0;
        for (int length : prefixLengths) {
            result.add(hex.substring(at, at + length));
            at += length;
        }
        return result;
    }

    /** Whether {@code fragment} is a shard of this layout: as many parts as prefixes, each of the right length. */
    public boolean isFragment(List<String> fragment) {
        if (fragment.size() != prefixLengths.size()) {
            return false;
        }
        for (int i = 0; i < fragment.size(); i++) {
            if (fragment.get(i).length() != prefixLengths.get(i)) {
                return false;
            }
        }
        return true;
    }

    /** {@code all_pods_versions_a_7_5.txt}, or {@code all_pods_versions.txt} for an unsharded repository. */
    public static String shardFileName(List<String> fragment) {
        return fragment.isEmpty() ? SHARD_PREFIX + SHARD_SUFFIX : SHARD_PREFIX + "_" + String.join("_", fragment) + SHARD_SUFFIX;
    }

    public String shardFileName(String podName) {
        return shardFileName(fragment(podName));
    }

    /** {@code Specs/a/7/5/AFNetworking}. */
    public String podDirectory(String podName) {
        List<String> fragment = fragment(podName);
        return SPECS + (fragment.isEmpty() ? "" : String.join("/", fragment) + "/") + podName;
    }

    /** {@code Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json}. */
    public String podspecPath(String podName, String version) {
        return podDirectory(podName) + "/" + version + "/" + podName + CocoaPodsPath.PODSPEC_SUFFIX;
    }

    /** {@code Specs/a/7/5/} – the directory holding every pod of a shard. */
    public static String shardDirectory(List<String> fragment) {
        return SPECS + (fragment.isEmpty() ? "" : String.join("/", fragment) + "/");
    }
}
