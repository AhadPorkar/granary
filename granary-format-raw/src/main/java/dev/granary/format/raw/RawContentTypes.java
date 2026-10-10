package dev.granary.format.raw;

import dev.granary.core.storage.ContentTypes;
import java.util.Locale;
import java.util.Map;

/**
 * Media types for the kinds of files raw repositories hold (web assets, installers, archives, source
 * distributions), on top of the package-manager table in {@link ContentTypes}.
 */
public final class RawContentTypes {

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("js", "text/javascript"),
            Map.entry("mjs", "text/javascript"),
            Map.entry("cjs", "text/javascript"),
            Map.entry("css", "text/css"),
            Map.entry("map", "application/json"),
            Map.entry("htm", "text/html"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("eot", "application/vnd.ms-fontobject"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("csv", "text/csv"),
            Map.entry("yaml", "application/yaml"),
            Map.entry("yml", "application/yaml"),
            Map.entry("toml", "application/toml"),
            Map.entry("jsonl", "application/jsonl"),
            Map.entry("ndjson", "application/x-ndjson"),
            Map.entry("sh", "application/x-sh"),
            Map.entry("ps1", "text/plain"),
            Map.entry("bat", "text/plain"),
            Map.entry("properties", "text/plain"),
            Map.entry("cabal", "text/plain"),
            Map.entry("pm", "text/x-perl"),
            Map.entry("pl", "text/x-perl"),
            Map.entry("r", "text/plain"),
            Map.entry("xz", "application/x-xz"),
            Map.entry("bz2", "application/x-bzip2"),
            Map.entry("zst", "application/zstd"),
            Map.entry("7z", "application/x-7z-compressed"),
            Map.entry("rar", "application/vnd.rar"),
            Map.entry("iso", "application/x-iso9660-image"),
            Map.entry("exe", "application/vnd.microsoft.portable-executable"),
            Map.entry("msi", "application/x-msi"),
            Map.entry("dmg", "application/x-apple-diskimage"),
            Map.entry("apk", "application/vnd.android.package-archive"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("wav", "audio/wav"));

    private RawContentTypes() {
    }

    /** The media type for a path: the core table first, then this one, then {@code application/octet-stream}. */
    public static String guess(String path) {
        String core = ContentTypes.guess(path);
        if (!ContentTypes.OCTET_STREAM.equals(core)) {
            return core;
        }
        if (path == null) {
            return ContentTypes.OCTET_STREAM;
        }
        String file = RawPaths.fileName(path);
        int dot = file.lastIndexOf('.');
        if (dot < 0 || dot == file.length() - 1) {
            return ContentTypes.OCTET_STREAM;
        }
        return BY_EXTENSION.getOrDefault(file.substring(dot + 1).toLowerCase(Locale.ROOT), ContentTypes.OCTET_STREAM);
    }
}
