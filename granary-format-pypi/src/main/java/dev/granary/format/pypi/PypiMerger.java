package dev.granary.format.pypi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Merges the index documents of a group's members. Members are visited in group order, hosted ones
 * first, so for the same file name the earlier member wins and the group never advertises a file it would
 * then serve from somewhere else.
 */
public final class PypiMerger {

    private PypiMerger() {
    }

    /** The union of project lists, keyed by normalised name (first spelling wins), sorted by normalised name. */
    public static List<String> mergeNames(List<List<String>> lists) {
        TreeMap<String, String> byNormalised = new TreeMap<>();
        for (List<String> names : lists) {
            for (String name : names) {
                byNormalised.putIfAbsent(PypiNames.normalise(name), name);
            }
        }
        return new ArrayList<>(byNormalised.values());
    }

    /**
     * The union of a project's pages: files by name (first member wins, so its yanked flag and digests are
     * kept), versions unioned in PEP 440 order, the highest API version of the members.
     */
    public static PypiProject mergeProjects(String normalisedName, List<PypiProject> pages) {
        Map<String, PypiFile> files = new LinkedHashMap<>();
        Set<String> versions = new LinkedHashSet<>();
        boolean anyVersionList = false;
        String apiVersion = PypiProject.API_VERSION;
        for (PypiProject page : pages) {
            for (PypiFile f : page.files()) {
                files.putIfAbsent(f.filename(), f);
            }
            if (page.versions() != null) {
                anyVersionList = true;
                versions.addAll(page.versions());
            } else {
                versions.addAll(page.effectiveVersions());
            }
            if (compareApiVersion(page.apiVersion(), apiVersion) > 0) {
                apiVersion = page.apiVersion();
            }
        }
        List<String> versionList = anyVersionList || !versions.isEmpty() ? PypiVersion.sort(versions) : null;
        return new PypiProject(normalisedName, apiVersion, new ArrayList<>(files.values()), versionList, Map.of());
    }

    static int compareApiVersion(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0;
            int q = i < y.length ? parse(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
