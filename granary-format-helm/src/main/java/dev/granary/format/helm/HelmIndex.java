package dev.granary.format.helm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * A chart repository index ({@code index.yaml}): chart names in alphabetical order, each with its versions newest
 * first (Helm's own order), one entry per version.
 */
public final class HelmIndex {

    public static final String API_VERSION = "v1";
    public static final String CONTENT_TYPE = "application/x-yaml";

    private final Map<String, List<ChartEntry>> entries;

    private HelmIndex(Map<String, List<ChartEntry>> entries) {
        this.entries = entries;
    }

    public static HelmIndex empty() {
        return new HelmIndex(new TreeMap<>());
    }

    /** An index of the given entries; for a repeated chart version the first entry wins. */
    public static HelmIndex of(Collection<ChartEntry> entries) {
        Map<String, Map<String, ChartEntry>> byName = new TreeMap<>();
        for (ChartEntry entry : entries) {
            if (entry.name() == null || entry.version() == null) {
                continue;
            }
            byName.computeIfAbsent(entry.name(), k -> new LinkedHashMap<>()).putIfAbsent(entry.version(), entry);
        }
        return sorted(byName);
    }

    /**
     * Parses an index as an upstream repository serves it. Entries that are not mappings or lack a version are
     * dropped (Helm skips them as well); an entry's name is taken from the key it is listed under, which is the
     * name {@code helm pull <repo>/<name>} looks it up by.
     */
    @SuppressWarnings("unchecked")
    public static HelmIndex parse(byte[] yaml) {
        Map<String, Object> root = HelmYaml.loadMap(yaml, HelmYaml.INDEX_LIMIT);
        Object rawEntries = root.get("entries");
        Map<String, Map<String, ChartEntry>> byName = new TreeMap<>();
        if (rawEntries instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String name = String.valueOf(e.getKey());
                if (name.isBlank() || !(e.getValue() instanceof List<?> list)) {
                    continue;
                }
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> fields)) {
                        continue;
                    }
                    ChartEntry entry = ChartEntry.of((Map<String, Object>) fields);
                    if (entry.version() == null || entry.version().isBlank()) {
                        continue;
                    }
                    if (!name.equals(entry.name())) {
                        entry = entry.withName(name);
                    }
                    byName.computeIfAbsent(name, k -> new LinkedHashMap<>()).putIfAbsent(entry.version(), entry);
                }
            }
        } else if (rawEntries != null) {
            throw new HelmYaml.InvalidYamlException("entries must be a mapping", null);
        }
        return sorted(byName);
    }

    private static HelmIndex sorted(Map<String, Map<String, ChartEntry>> byName) {
        Map<String, List<ChartEntry>> result = new TreeMap<>();
        byName.forEach((name, versions) -> {
            List<ChartEntry> list = new ArrayList<>(versions.values());
            list.sort((a, b) -> HelmVersion.NEWEST_FIRST.compare(a.version(), b.version()));
            result.put(name, List.copyOf(list));
        });
        return new HelmIndex(result);
    }

    /**
     * Joins member indexes in member order: every chart version of every member, the first member that has a
     * version wins it. Entries carry repository-relative download paths; when an entry's path is already taken
     * by another chart version, the entry is addressed by {@code charts/<name>/<version>/<file>} instead.
     */
    public static HelmIndex merge(List<HelmIndex> members) {
        Map<String, Map<String, ChartEntry>> byName = new TreeMap<>();
        Map<String, String> claimed = new HashMap<>();
        for (HelmIndex member : members) {
            for (ChartEntry entry : member.all()) {
                Map<String, ChartEntry> versions = byName.computeIfAbsent(entry.name(), k -> new LinkedHashMap<>());
                if (versions.containsKey(entry.version())) {
                    continue;
                }
                String id = entry.name() + "\n" + entry.version();
                List<String> urls = entry.urls();
                if (!urls.isEmpty()) {
                    String owner = claimed.putIfAbsent(urls.getFirst(), id);
                    if (owner != null && !owner.equals(id)) {
                        Optional<String> alias = alias(entry, urls.getFirst());
                        if (alias.isEmpty()) {
                            continue;
                        }
                        entry = entry.withUrls(List.of(alias.get()));
                        claimed.put(alias.get(), id);
                    }
                }
                versions.put(entry.version(), entry);
            }
        }
        byName.values().removeIf(Map::isEmpty);
        return sorted(byName);
    }

    private static Optional<String> alias(ChartEntry entry, String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        if (!HelmPath.isValidName(entry.name()) || !HelmPath.isSafeFilename(entry.version()) || !HelmPath.isSafeFilename(file)) {
            return Optional.empty();
        }
        return Optional.of(HelmPath.aliasPath(entry.name(), entry.version(), file));
    }

    public Map<String, List<ChartEntry>> entries() {
        return entries;
    }

    public List<ChartEntry> all() {
        List<ChartEntry> all = new ArrayList<>();
        entries.values().forEach(all::addAll);
        return all;
    }

    public int size() {
        int n = 0;
        for (List<ChartEntry> versions : entries.values()) {
            n += versions.size();
        }
        return n;
    }

    public List<ChartEntry> versions(String name) {
        return entries.getOrDefault(name, List.of());
    }

    public Optional<ChartEntry> find(String name, String version) {
        return versions(name).stream().filter(e -> version.equals(e.version())).findFirst();
    }

    /** The newest release of a chart, or its newest pre-release when it has no release (Helm's "latest"). */
    public Optional<ChartEntry> latest(String name) {
        List<ChartEntry> versions = versions(name);
        for (ChartEntry entry : versions) {
            Optional<HelmVersion> v = HelmVersion.parse(entry.version());
            if (v.isPresent() && !v.get().isPrerelease()) {
                return Optional.of(entry);
            }
        }
        return versions.stream().findFirst();
    }

    /** The same index with every entry transformed; entries mapped to {@code null} are dropped. */
    public HelmIndex map(Function<ChartEntry, ChartEntry> mapper) {
        Map<String, List<ChartEntry>> result = new TreeMap<>();
        entries.forEach((name, versions) -> {
            List<ChartEntry> list = new ArrayList<>(versions.size());
            for (ChartEntry entry : versions) {
                ChartEntry mapped = mapper.apply(entry);
                if (mapped != null) {
                    list.add(mapped);
                }
            }
            if (!list.isEmpty()) {
                result.put(name, List.copyOf(list));
            }
        });
        return new HelmIndex(result);
    }

    /** {@code index.yaml} as {@code helm repo index} writes it, plus ChartMuseum's {@code serverInfo}. */
    public byte[] toYaml(String generated, Map<String, Object> serverInfo) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("apiVersion", API_VERSION);
        doc.put("entries", toMap());
        doc.put("generated", generated);
        if (serverInfo != null && !serverInfo.isEmpty()) {
            doc.put("serverInfo", serverInfo);
        }
        return HelmYaml.dump(doc);
    }

    /** {@code {name: [entry, …]}}, the shape of both the index's {@code entries} and ChartMuseum's {@code GET /api/charts}. */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        entries.forEach((name, versions) -> result.put(name, versions.stream().map(e -> (Object) e.sortedCopy()).toList()));
        return result;
    }
}
