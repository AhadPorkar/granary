package dev.granary.format.pypi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * A project's simple-index page: its files, and under PEP 700 its version list.
 *
 * @param name       the project name as the page states it (normalised for pages this server generates)
 * @param apiVersion the {@code api-version} of the page ({@code 1.0}, {@code 1.1}, …)
 * @param files      the distribution files, in page order
 * @param versions   the PEP 700 version list, or {@code null} when the page did not carry one
 * @param extra      other top-level JSON members, passed through
 */
public record PypiProject(String name, String apiVersion, List<PypiFile> files, List<String> versions,
                          Map<String, JsonNode> extra) {

    /** The highest API version this server generates itself. */
    public static final String API_VERSION = "1.1";

    public PypiProject {
        apiVersion = apiVersion == null || apiVersion.isBlank() ? API_VERSION : apiVersion;
        files = files == null ? List.of() : List.copyOf(files);
        versions = versions == null ? null : List.copyOf(versions);
        extra = extra == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(extra));
    }

    public static PypiProject of(String name, List<PypiFile> files) {
        return new PypiProject(name, API_VERSION, files, null, Map.of());
    }

    public PypiProject withFiles(List<PypiFile> newFiles) {
        return new PypiProject(name, apiVersion, newFiles, versions, extra);
    }

    public PypiProject withName(String newName) {
        return new PypiProject(newName, apiVersion, files, versions, extra);
    }

    public PypiProject withVersions(List<String> newVersions) {
        return new PypiProject(name, apiVersion, files, newVersions, extra);
    }

    /** Applies {@code mapper} to every file, e.g. to rewrite download links. */
    public PypiProject mapFiles(java.util.function.UnaryOperator<PypiFile> mapper) {
        List<PypiFile> mapped = new ArrayList<>(files.size());
        for (PypiFile f : files) {
            mapped.add(mapper.apply(f));
        }
        return withFiles(mapped);
    }

    /** The version list: the one the page carried, otherwise the versions of the files, in PEP 440 order. */
    public List<String> effectiveVersions() {
        if (versions != null) {
            return versions;
        }
        Set<String> found = new LinkedHashSet<>();
        for (PypiFile f : files) {
            f.version().ifPresent(found::add);
        }
        return PypiVersion.sort(found);
    }
}
