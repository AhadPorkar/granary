package dev.granary.format.helm;

import java.time.Instant;

/**
 * The index of one repository at one point in time, with repository-relative download paths in every entry's
 * {@code urls}. The key identifies exactly what the index was computed from (the stored entries of a hosted
 * repository, the cached upstream document of a proxy, the members' keys of a group), so equal keys mean equal
 * indexes and the key doubles as the base of the index's ETag.
 *
 * @param key        identity of the source the index was computed from
 * @param index      the index, download paths relative to the repository
 * @param computedAt when the index was computed; written as {@code generated}
 * @param mapping    for a proxy: where each path is fetched from upstream; otherwise {@code null}
 */
record IndexSnapshot(String key, HelmIndex index, Instant computedAt, UpstreamUrls.Mapping mapping) {

    long weight() {
        return Math.max(1, index.size());
    }
}
