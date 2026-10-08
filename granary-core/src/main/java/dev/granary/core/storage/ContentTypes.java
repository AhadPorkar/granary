package dev.granary.core.storage;

import java.util.Locale;
import java.util.Map;

/** Minimal extension → media type table for the files package managers exchange. */
public final class ContentTypes {

    public static final String OCTET_STREAM = "application/octet-stream";

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("jar", "application/java-archive"),
            Map.entry("war", "application/java-archive"),
            Map.entry("ear", "application/java-archive"),
            Map.entry("pom", "application/xml"),
            Map.entry("xml", "application/xml"),
            Map.entry("nuspec", "application/xml"),
            Map.entry("json", "application/json"),
            Map.entry("tgz", "application/gzip"),
            Map.entry("gz", "application/gzip"),
            Map.entry("zip", "application/zip"),
            Map.entry("nupkg", "application/zip"),
            Map.entry("snupkg", "application/zip"),
            Map.entry("whl", "application/zip"),
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("sha1", "text/plain"),
            Map.entry("md5", "text/plain"),
            Map.entry("sha256", "text/plain"),
            Map.entry("sha512", "text/plain"),
            Map.entry("asc", "text/plain"),
            Map.entry("html", "text/html"),
            Map.entry("tar", "application/x-tar"),
            Map.entry("deb", "application/vnd.debian.binary-package"),
            Map.entry("rpm", "application/x-rpm"));

    private ContentTypes() {
    }

    public static String guess(String path) {
        if (path == null) {
            return OCTET_STREAM;
        }
        int slash = path.lastIndexOf('/');
        String file = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = file.lastIndexOf('.');
        if (dot < 0 || dot == file.length() - 1) {
            return OCTET_STREAM;
        }
        return BY_EXTENSION.getOrDefault(file.substring(dot + 1).toLowerCase(Locale.ROOT), OCTET_STREAM);
    }
}
