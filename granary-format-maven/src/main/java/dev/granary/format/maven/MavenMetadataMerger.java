package dev.granary.format.maven;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges the {@code maven-metadata.xml} documents of several group members into one, the way a client
 * would see them if all artifacts lived in a single repository.
 */
public final class MavenMetadataMerger {

    private MavenMetadataMerger() {
    }

    public static MavenMetadata merge(List<MavenMetadata> sources) {
        MavenMetadata result = new MavenMetadata();
        Set<String> versions = new LinkedHashSet<>();
        Map<String, MavenMetadata.SnapshotVersion> snapshotVersions = new LinkedHashMap<>();
        Map<String, MavenMetadata.Plugin> plugins = new LinkedHashMap<>();
        String lastUpdated = null;
        String snapshotTimestamp = null;
        String snapshotBuildNumber = null;

        for (MavenMetadata md : sources) {
            if (result.getGroupId() == null) {
                result.setGroupId(md.getGroupId());
            }
            if (result.getArtifactId() == null) {
                result.setArtifactId(md.getArtifactId());
            }
            if (result.getVersion() == null) {
                result.setVersion(md.getVersion());
            }
            versions.addAll(md.getVersions());
            if (md.getLastUpdated() != null && (lastUpdated == null || md.getLastUpdated().compareTo(lastUpdated) > 0)) {
                lastUpdated = md.getLastUpdated();
            }
            // the newest snapshot build wins the <snapshot> block
            if (md.getSnapshotTimestamp() != null
                    && (snapshotTimestamp == null || md.getSnapshotTimestamp().compareTo(snapshotTimestamp) > 0)) {
                snapshotTimestamp = md.getSnapshotTimestamp();
                snapshotBuildNumber = md.getSnapshotBuildNumber();
            }
            for (MavenMetadata.SnapshotVersion sv : md.getSnapshotVersions()) {
                MavenMetadata.SnapshotVersion existing = snapshotVersions.get(sv.key());
                if (existing == null || isNewer(sv.updated(), existing.updated())) {
                    snapshotVersions.put(sv.key(), sv);
                }
            }
            for (MavenMetadata.Plugin p : md.getPlugins()) {
                plugins.putIfAbsent(p.artifactId() + "|" + p.prefix(), p);
            }
        }

        List<String> sorted = versions.stream().sorted(MavenVersions.COMPARATOR).toList();
        result.getVersions().addAll(sorted);
        if (!sorted.isEmpty()) {
            result.setLatest(sorted.get(sorted.size() - 1));
            sorted.stream().filter(v -> !MavenPath.isSnapshot(v)).reduce((a, b) -> b).ifPresent(result::setRelease);
        }
        result.setLastUpdated(lastUpdated);
        result.setSnapshotTimestamp(snapshotTimestamp);
        result.setSnapshotBuildNumber(snapshotBuildNumber);
        result.getSnapshotVersions().addAll(snapshotVersions.values());
        result.getPlugins().addAll(plugins.values());
        return result;
    }

    private static boolean isNewer(String a, String b) {
        if (a == null) {
            return false;
        }
        return b == null || a.compareTo(b) > 0;
    }
}
