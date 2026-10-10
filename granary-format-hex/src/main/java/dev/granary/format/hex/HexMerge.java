package dev.granary.format.hex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges the registry documents of a group's members into one, the way a client would see them if every
 * member were one repository: the union of packages and versions, the first member (in group order, hosted
 * repositories conventionally first) that has a version wins for that version, and everything comes out in a
 * stable order (packages by name, versions ascending). The result carries the group's repository name.
 */
final class HexMerge {

    private HexMerge() {
    }

    static HexRegistry.Names names(String repository, List<HexRegistry.Names> members) {
        Map<String, HexRegistry.Timestamp> updated = new HashMap<>();
        for (HexRegistry.Names names : members) {
            for (HexRegistry.NamesEntry entry : names.packages()) {
                if (entry.name().isEmpty()) {
                    continue;
                }
                updated.merge(entry.name(), nullSafe(entry.updatedAt()), HexMerge::later);
            }
        }
        List<HexRegistry.NamesEntry> entries = updated.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new HexRegistry.NamesEntry(e.getKey(), e.getValue() == NONE ? null : e.getValue()))
                .toList();
        return new HexRegistry.Names(repository, entries);
    }

    static HexRegistry.Versions versions(String repository, List<HexRegistry.Versions> members) {
        Map<String, Map<String, int[]>> byPackage = new HashMap<>(); // version -> {retired, advisory}
        for (HexRegistry.Versions versions : members) {
            for (HexRegistry.VersionsEntry entry : versions.packages()) {
                if (entry.name().isEmpty()) {
                    continue;
                }
                Map<String, int[]> known = byPackage.computeIfAbsent(entry.name(), k -> new HashMap<>());
                Set<Integer> retired = new HashSet<>(entry.retired());
                Set<Integer> advisories = new HashSet<>(entry.withAdvisories());
                for (int i = 0; i < entry.versions().size(); i++) {
                    known.putIfAbsent(entry.versions().get(i),
                            new int[] {retired.contains(i) ? 1 : 0, advisories.contains(i) ? 1 : 0});
                }
            }
        }
        List<HexRegistry.VersionsEntry> entries = new ArrayList<>();
        byPackage.keySet().stream().sorted().forEach(name -> {
            Map<String, int[]> known = byPackage.get(name);
            List<String> sorted = HexVersion.sorted(known.keySet());
            List<Integer> retired = new ArrayList<>();
            List<Integer> advisories = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                int[] flags = known.get(sorted.get(i));
                if (flags[0] == 1) {
                    retired.add(i);
                }
                if (flags[1] == 1) {
                    advisories.add(i);
                }
            }
            entries.add(new HexRegistry.VersionsEntry(name, sorted, retired, advisories));
        });
        return new HexRegistry.Versions(repository, entries);
    }

    /**
     * Merges one package across members. Each release keeps the advisories of the member it came from; the
     * advisories themselves are pooled (identical ones once) and the release's indexes renumbered.
     */
    static HexRegistry.Package pkg(String name, String repository, List<HexRegistry.Package> members) {
        Map<String, HexRegistry.Release> releases = new LinkedHashMap<>();
        List<byte[]> advisories = new ArrayList<>();
        for (HexRegistry.Package member : members) {
            for (HexRegistry.Release release : member.releases()) {
                if (release.version().isEmpty() || releases.containsKey(release.version())) {
                    continue;
                }
                List<Integer> indexes = new ArrayList<>();
                for (int index : release.advisoryIndexes()) {
                    if (index < 0 || index >= member.advisories().size()) {
                        continue; // a dangling index in the member document: drop it rather than point at the wrong advisory
                    }
                    indexes.add(pool(advisories, member.advisories().get(index)));
                }
                releases.put(release.version(), release.withAdvisoryIndexes(indexes));
            }
        }
        List<HexRegistry.Release> sorted = releases.values().stream()
                .sorted(Comparator.comparing(HexRegistry.Release::version, HexVersion.ORDER))
                .toList();
        return new HexRegistry.Package(name, repository, sorted, advisories);
    }

    private static int pool(List<byte[]> pool, byte[] advisory) {
        for (int i = 0; i < pool.size(); i++) {
            if (Arrays.equals(pool.get(i), advisory)) {
                return i;
            }
        }
        pool.add(advisory);
        return pool.size() - 1;
    }

    private static final HexRegistry.Timestamp NONE = new HexRegistry.Timestamp(Long.MIN_VALUE, 0);

    private static HexRegistry.Timestamp nullSafe(HexRegistry.Timestamp t) {
        return t == null ? NONE : t;
    }

    private static HexRegistry.Timestamp later(HexRegistry.Timestamp a, HexRegistry.Timestamp b) {
        if (a.seconds() != b.seconds()) {
            return a.seconds() > b.seconds() ? a : b;
        }
        return a.nanos() >= b.nanos() ? a : b;
    }
}
