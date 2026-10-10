package dev.granary.format.pypi;

import java.util.List;

/**
 * What a repository-relative path of the PyPI protocol addresses.
 *
 * <pre>
 *   (empty)                                   → ROOT             (GET: landing page, POST: upload)
 *   legacy/                                   → UPLOAD           (POST: upload, the URL twine's docs use)
 *   simple/ , simple/index.html               → SIMPLE_INDEX     (project list)
 *   simple/six/ , simple/six/index.html       → SIMPLE_PROJECT   project=six
 *   simple/Six , simple/zope.interface/       → SIMPLE_PROJECT   with canonicalPath "simple/zope-interface/" (301)
 *   packages/a/b/c/six-1.16.0.tar.gz          → PACKAGE_FILE     (hosted layout: packages/name/version/file)
 *   packages/six/1.16.0                       → PACKAGE_VERSION  project=six version=1.16.0 (DELETE only)
 *   pypi/six/json                             → JSON_PROJECT     project=six
 *   pypi/six/1.16.0/json                      → JSON_RELEASE     project=six version=1.16.0
 *   anything with odd segments                → INVALID          (400)
 *   anything else                             → UNKNOWN          (404)
 * </pre>
 *
 * @param path          the path as given, without a leading slash
 * @param kind          what it addresses
 * @param project       the normalised project name, when the path names one
 * @param version       the version, for PACKAGE_VERSION and JSON_RELEASE
 * @param filename      the file name, for PACKAGE_FILE
 * @param canonicalPath the path the client should have used; equal to {@code path} when it already did
 */
public record PypiPath(String path, Kind kind, String project, String version, String filename, String canonicalPath) {

    public enum Kind { ROOT, UPLOAD, SIMPLE_INDEX, SIMPLE_PROJECT, PACKAGE_FILE, PACKAGE_VERSION, JSON_PROJECT, JSON_RELEASE, INVALID, UNKNOWN }

    public static final String SIMPLE = "simple/";
    public static final String PACKAGES = "packages/";
    public static final String PYPI = "pypi/";
    public static final String LEGACY = "legacy/";

    public static PypiPath parse(String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            return new PypiPath(path, Kind.ROOT, null, null, null, path);
        }
        if (path.contains("\\") || path.contains("//") || path.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return invalid(path);
        }
        for (String segment : path.split("/", -1)) {
            if (hasDotSegment(segment)) {
                return invalid(path);
            }
        }
        if (path.equals("legacy") || path.equals(LEGACY)) {
            return new PypiPath(path, Kind.UPLOAD, null, null, null, LEGACY);
        }
        if (path.equals("simple") || path.equals(SIMPLE) || path.equals(SIMPLE + "index.html")) {
            return new PypiPath(path, Kind.SIMPLE_INDEX, null, null, null, SIMPLE);
        }
        if (path.startsWith(SIMPLE)) {
            return parseSimpleProject(path);
        }
        if (path.startsWith(PACKAGES)) {
            return parsePackages(path);
        }
        if (path.startsWith(PYPI)) {
            return parseJson(path);
        }
        return unknown(path);
    }

    private static PypiPath parseSimpleProject(String path) {
        String rest = path.substring(SIMPLE.length());
        if (rest.endsWith("/index.html")) {
            rest = rest.substring(0, rest.length() - "index.html".length());
        }
        boolean slash = rest.endsWith("/");
        String name = slash ? rest.substring(0, rest.length() - 1) : rest;
        if (name.isEmpty() || name.contains("/")) {
            return name.isEmpty() ? invalid(path) : unknown(path);
        }
        if (hasDotSegment(name)) {
            return invalid(path);
        }
        String normalised = PypiNames.normalise(name);
        if (!PypiNames.isValidName(normalised)) {
            return invalid(path);
        }
        return new PypiPath(path, Kind.SIMPLE_PROJECT, normalised, null, null, SIMPLE + normalised + "/");
    }

    private static PypiPath parsePackages(String path) {
        if (path.endsWith("/")) {
            return unknown(path);
        }
        List<String> segments = List.of(path.substring(PACKAGES.length()).split("/", -1));
        for (int i = 0; i < segments.size(); i++) {
            String s = segments.get(i);
            boolean marker = i == 0 && segments.size() == 3 && s.equals(PypiProxy.FOREIGN_MARKER);
            if (!marker && !PypiNames.isSafeSegment(s)) {
                return invalid(path);
            }
        }
        if (segments.size() == 2) {
            String normalised = PypiNames.normalise(segments.get(0));
            if (!PypiNames.isValidName(normalised)) {
                return invalid(path);
            }
            return new PypiPath(path, Kind.PACKAGE_VERSION, normalised, segments.get(1), null,
                    PACKAGES + normalised + "/" + segments.get(1));
        }
        if (segments.size() < 3) {
            return unknown(path);
        }
        return new PypiPath(path, Kind.PACKAGE_FILE, null, null, segments.getLast(), path);
    }

    private static PypiPath parseJson(String path) {
        String[] s = path.substring(PYPI.length()).split("/", -1);
        if (s.length < 2 || s.length > 3 || !s[s.length - 1].equals("json")) {
            return unknown(path);
        }
        if (hasDotSegment(s[0]) || (s.length == 3 && hasDotSegment(s[1]))) {
            return invalid(path);
        }
        String normalised = PypiNames.normalise(s[0]);
        if (!PypiNames.isValidName(normalised)) {
            return invalid(path);
        }
        if (s.length == 2) {
            return new PypiPath(path, Kind.JSON_PROJECT, normalised, null, null, PYPI + normalised + "/json");
        }
        if (s[1].isEmpty() || !PypiNames.isSafeSegment(s[1])) {
            return invalid(path);
        }
        return new PypiPath(path, Kind.JSON_RELEASE, normalised, s[1], null, PYPI + normalised + "/" + s[1] + "/json");
    }

    private static boolean hasDotSegment(String segment) {
        return segment.equals(".") || segment.equals("..");
    }

    private static PypiPath invalid(String path) {
        return new PypiPath(path, Kind.INVALID, null, null, null, path);
    }

    private static PypiPath unknown(String path) {
        return new PypiPath(path, Kind.UNKNOWN, null, null, null, path);
    }

    /** Whether the client used the canonical spelling; otherwise it is redirected to {@link #canonicalPath()}. */
    public boolean isCanonical() {
        return path.equals(canonicalPath);
    }

    /** Index documents and JSON API documents change when something is uploaded; files under {@code packages/} never do. */
    public boolean isMetadata() {
        return switch (kind) {
            case PACKAGE_FILE, PACKAGE_VERSION -> false;
            default -> true;
        };
    }

    /** A PEP 658 {@code .metadata} sidecar next to a distribution file. */
    public boolean isMetadataSidecar() {
        return kind == Kind.PACKAGE_FILE && filename.endsWith(PypiHosted.METADATA_SUFFIX);
    }

    /** The project name the hosted layout {@code packages/<name>/<version>/<file>} encodes, if the path has that shape. */
    public String hostedProject() {
        if (kind != Kind.PACKAGE_FILE) {
            return project;
        }
        String[] s = path.substring(PACKAGES.length()).split("/", -1);
        return s.length == 3 && !s[0].equals(PypiProxy.FOREIGN_MARKER) ? s[0] : null;
    }

    /** The version the hosted layout encodes; see {@link #hostedProject()}. */
    public String hostedVersion() {
        if (kind != Kind.PACKAGE_FILE) {
            return version;
        }
        String[] s = path.substring(PACKAGES.length()).split("/", -1);
        return s.length == 3 && !s[0].equals(PypiProxy.FOREIGN_MARKER) ? s[1] : null;
    }
}
