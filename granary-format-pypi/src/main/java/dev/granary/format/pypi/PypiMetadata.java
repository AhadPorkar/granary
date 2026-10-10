package dev.granary.format.pypi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Core metadata (the {@code METADATA} file of a wheel, the {@code PKG-INFO} of an sdist): RFC 822 style
 * headers, repeatable for some fields, followed after a blank line by the long description. Served as the
 * PEP 658 {@code .metadata} sidecar and used to generate the JSON API of hosted projects.
 */
public final class PypiMetadata {

    /** Largest metadata file that is extracted from an upload; real ones are a few kilobytes. */
    static final long MAX_METADATA_BYTES = 16L * 1024 * 1024;

    private static final Pattern DIST_INFO_METADATA = Pattern.compile("^[^/]+\\.dist-info/METADATA$");
    private static final Pattern PKG_INFO = Pattern.compile("^(?:\\./)?[^/]+/PKG-INFO$");

    /** Upload form field → core metadata header, in the order PyPI writes them. */
    private static final Map<String, String> FIELD_TO_HEADER = new LinkedHashMap<>();

    static {
        FIELD_TO_HEADER.put("metadata_version", "Metadata-Version");
        FIELD_TO_HEADER.put("name", "Name");
        FIELD_TO_HEADER.put("version", "Version");
        FIELD_TO_HEADER.put("dynamic", "Dynamic");
        FIELD_TO_HEADER.put("platform", "Platform");
        FIELD_TO_HEADER.put("supported_platform", "Supported-Platform");
        FIELD_TO_HEADER.put("summary", "Summary");
        FIELD_TO_HEADER.put("description_content_type", "Description-Content-Type");
        FIELD_TO_HEADER.put("keywords", "Keywords");
        FIELD_TO_HEADER.put("home_page", "Home-page");
        FIELD_TO_HEADER.put("download_url", "Download-URL");
        FIELD_TO_HEADER.put("author", "Author");
        FIELD_TO_HEADER.put("author_email", "Author-email");
        FIELD_TO_HEADER.put("maintainer", "Maintainer");
        FIELD_TO_HEADER.put("maintainer_email", "Maintainer-email");
        FIELD_TO_HEADER.put("license", "License");
        FIELD_TO_HEADER.put("license_expression", "License-Expression");
        FIELD_TO_HEADER.put("license_file", "License-File");
        FIELD_TO_HEADER.put("classifiers", "Classifier");
        FIELD_TO_HEADER.put("requires_dist", "Requires-Dist");
        FIELD_TO_HEADER.put("requires_python", "Requires-Python");
        FIELD_TO_HEADER.put("requires_external", "Requires-External");
        FIELD_TO_HEADER.put("project_urls", "Project-URL");
        FIELD_TO_HEADER.put("provides_extra", "Provides-Extra");
        FIELD_TO_HEADER.put("provides_dist", "Provides-Dist");
        FIELD_TO_HEADER.put("obsoletes_dist", "Obsoletes-Dist");
    }

    private final List<Map.Entry<String, String>> headers;
    private final String description;

    private PypiMetadata(List<Map.Entry<String, String>> headers, String description) {
        this.headers = List.copyOf(headers);
        this.description = description;
    }

    // --- reading -----------------------------------------------------------------------------

    public static PypiMetadata parse(byte[] bytes) {
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    public static PypiMetadata parse(String text) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        int i = 0;
        String name = null;
        StringBuilder value = null;
        for (; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                i++;
                break;
            }
            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && value != null) {
                value.append('\n').append(line.stripLeading());
                continue;
            }
            if (name != null) {
                headers.add(Map.entry(name, value.toString()));
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                name = null;
                value = null;
                continue;
            }
            name = line.substring(0, colon).trim();
            value = new StringBuilder(line.substring(colon + 1).trim());
        }
        if (name != null) {
            headers.add(Map.entry(name, value.toString()));
        }
        String description = i < lines.length ? String.join("\n", List.of(lines).subList(i, lines.length)) : "";
        if (description.isEmpty()) {
            // metadata 1.x put the description in a header with indented continuation lines
            for (Map.Entry<String, String> h : headers) {
                if (h.getKey().equalsIgnoreCase("Description")) {
                    description = h.getValue();
                }
            }
        }
        return new PypiMetadata(headers, description);
    }

    /** The first value of a header, case-insensitively; empty when absent or {@code UNKNOWN}. */
    public Optional<String> first(String header) {
        for (Map.Entry<String, String> h : headers) {
            if (h.getKey().equalsIgnoreCase(header)) {
                return meaningful(h.getValue());
            }
        }
        return Optional.empty();
    }

    /** Every value of a repeatable header, in order. */
    public List<String> all(String header) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> h : headers) {
            if (h.getKey().equalsIgnoreCase(header)) {
                meaningful(h.getValue()).ifPresent(values::add);
            }
        }
        return values;
    }

    public List<Map.Entry<String, String>> headers() {
        return headers;
    }

    public String description() {
        return description;
    }

    public Optional<String> name() {
        return first("Name");
    }

    public Optional<String> version() {
        return first("Version");
    }

    public Optional<String> requiresPython() {
        return first("Requires-Python");
    }

    private static Optional<String> meaningful(String value) {
        return value == null || value.isBlank() || value.equals("UNKNOWN") ? Optional.empty() : Optional.of(value);
    }

    // --- writing -----------------------------------------------------------------------------

    public byte[] toBytes() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> h : headers) {
            sb.append(h.getKey()).append(": ").append(h.getValue().replace("\n", "\n        ")).append('\n');
        }
        if (!description.isEmpty()) {
            sb.append('\n').append(description);
            if (!description.endsWith("\n")) {
                sb.append('\n');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds metadata from the fields of an upload form, for files whose metadata cannot be read from the
     * archive. Repeatable fields ({@code classifiers}, {@code requires_dist}, {@code project_urls}, …) keep all
     * their values; {@code description} becomes the body.
     */
    public static PypiMetadata fromUploadFields(Map<String, List<String>> fields) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        if (!fields.containsKey("metadata_version")) {
            headers.add(Map.entry("Metadata-Version", "2.1"));
        }
        for (Map.Entry<String, String> mapping : FIELD_TO_HEADER.entrySet()) {
            for (String value : fields.getOrDefault(mapping.getKey(), List.of())) {
                if (value != null && !value.isBlank() && !value.equals("UNKNOWN")) {
                    headers.add(Map.entry(mapping.getValue(), value.trim()));
                }
            }
        }
        String description = fields.getOrDefault("description", List.of()).stream().findFirst().orElse("");
        return new PypiMetadata(headers, description == null ? "" : description.replace("\r\n", "\n"));
    }

    // --- extracting from archives --------------------------------------------------------------

    /** The raw {@code *.dist-info/METADATA} member of a wheel on disk, preferring the one that matches name and version. */
    public static Optional<byte[]> fromWheel(Path wheel, String name, String version) throws IOException {
        String expectedPrefix = distInfoPrefix(name, version);
        try (ZipFile zip = new ZipFile(wheel.toFile())) {
            ZipEntry fallback = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!DIST_INFO_METADATA.matcher(entry.getName()).matches()) {
                    continue;
                }
                if (entry.getName().toLowerCase(Locale.ROOT).startsWith(expectedPrefix)) {
                    return read(zip, entry);
                }
                if (fallback == null) {
                    fallback = entry;
                }
            }
            return fallback == null ? Optional.empty() : read(zip, fallback);
        } catch (java.util.zip.ZipException e) {
            throw new IOException("not a valid wheel (zip) file: " + e.getMessage(), e);
        }
    }

    private static Optional<byte[]> read(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > MAX_METADATA_BYTES) {
            return Optional.empty();
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return Optional.of(readBounded(in));
        }
    }

    /** The {@code PKG-INFO} of an sdist ({@code .tar.gz} or {@code .zip}), if the archive has one at the top level. */
    public static Optional<byte[]> fromSdist(Path sdist, String filename) throws IOException {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            try (ZipFile zip = new ZipFile(sdist.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (PKG_INFO.matcher(entry.getName()).matches()) {
                        return read(zip, entry);
                    }
                }
                return Optional.empty();
            } catch (java.util.zip.ZipException e) {
                throw new IOException("not a valid zip file: " + e.getMessage(), e);
            }
        }
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(sdist))) {
                return pkgInfoFromTar(in);
            } catch (java.util.zip.ZipException e) {
                throw new IOException("not a valid gzip file: " + e.getMessage(), e);
            }
        }
        if (lower.endsWith(".tar")) {
            try (InputStream in = Files.newInputStream(sdist)) {
                return pkgInfoFromTar(in);
            }
        }
        return Optional.empty();
    }

    /** Walks a (ustar or GNU) tar stream and returns the first top-level {@code <dir>/PKG-INFO}. */
    static Optional<byte[]> pkgInfoFromTar(InputStream in) throws IOException {
        byte[] header = new byte[512];
        String longName = null;
        while (readFully(in, header)) {
            if (isZeroBlock(header)) {
                break;
            }
            String name = tarString(header, 0, 100);
            String prefix = tarString(header, 345, 155);
            if (!prefix.isEmpty() && "ustar".equals(tarString(header, 257, 5))) {
                name = prefix + "/" + name;
            }
            long size = tarNumber(header, 124, 12);
            char type = (char) header[156];
            if (longName != null) {
                name = longName;
                longName = null;
            }
            if (type == 'L') {
                byte[] data = new byte[(int) Math.min(size, 4096)];
                readFully(in, data);
                skip(in, size - data.length);
                longName = new String(data, StandardCharsets.UTF_8).trim().replace("\0", "");
            } else if ((type == '0' || type == '\0') && PKG_INFO.matcher(name).matches() && size <= MAX_METADATA_BYTES) {
                byte[] data = new byte[(int) size];
                if (!readFully(in, data)) {
                    return Optional.empty();
                }
                return Optional.of(data);
            } else {
                skip(in, size);
            }
            long padding = (512 - (size % 512)) % 512;
            skip(in, padding);
        }
        return Optional.empty();
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n < 0) {
                return false;
            }
            read += n;
        }
        return true;
    }

    private static void skip(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    return;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String tarString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long tarNumber(byte[] header, int offset, int length) throws IOException {
        if ((header[offset] & 0x80) != 0) {
            long value = 0;
            for (int i = 1; i < length; i++) {
                value = (value << 8) | (header[offset + i] & 0xff);
            }
            return value;
        }
        String text = tarString(header, offset, length).trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException e) {
            throw new IOException("corrupt tar header");
        }
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        byte[] data = in.readNBytes((int) MAX_METADATA_BYTES + 1);
        if (data.length > MAX_METADATA_BYTES) {
            throw new IOException("metadata file is larger than " + MAX_METADATA_BYTES + " bytes");
        }
        return data;
    }

    /** {@code {name}-{version}.dist-info/} as the wheel spec spells it: name with runs of {@code -_.} replaced by {@code _}. */
    static String distInfoPrefix(String name, String version) {
        return (PypiNames.normalise(name).replace('-', '_') + "-" + version + ".dist-info/").toLowerCase(Locale.ROOT);
    }

    /** Reads metadata from an in-memory wheel; used by tests and small uploads. */
    public static Optional<byte[]> fromWheelBytes(byte[] wheel) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(wheel))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (DIST_INFO_METADATA.matcher(entry.getName()).matches()) {
                    return Optional.of(readBounded(zip));
                }
            }
            return Optional.empty();
        }
    }
}
