package dev.granary.format.go;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds module zips in memory, the way {@code go mod download} and {@code zip -r} would. */
final class GoTestZips {

    private static final long FIXED_TIME = 1_704_067_200_000L; // 2024-01-01T00:00:00Z

    private GoTestZips() {
    }

    /** A plain module: go.mod declaring {@code module}, one Go file, under the {@code <module>@<version>/} prefix. */
    static byte[] module(String module, String version) {
        return module(module, version, Map.of(
                "go.mod", "module " + module + "\n\ngo 1.21\n",
                "hello.go", "package hello\n\n// Hello greets.\nfunc Hello() string { return \"hello from " + module + " " + version + "\" }\n"));
    }

    /** Files (relative to the module root) wrapped into the {@code <module>@<version>/} prefix. */
    static byte[] module(String module, String version, Map<String, String> files) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            entries.put(module + "@" + version + "/" + e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return zip(entries);
    }

    /** A zip with exactly these entries; names ending in {@code /} become directory entries. */
    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                // a fixed time keeps two zips with the same content byte-identical
                entry.setTime(FIXED_TIME);
                zip.putNextEntry(entry);
                if (!e.getKey().endsWith("/")) {
                    zip.write(e.getValue());
                }
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    static Map<String, byte[]> entries(Object... nameAndContent) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameAndContent.length; i += 2) {
            Object content = nameAndContent[i + 1];
            map.put((String) nameAndContent[i], content instanceof byte[] b ? b : content.toString().getBytes(StandardCharsets.UTF_8));
        }
        return map;
    }

    /**
     * Rewrites the uncompressed size a central-directory header declares for {@code entryName}, producing a zip
     * whose header lies about the entry, which is what a corrupted or malicious upload looks like.
     */
    static byte[] patchDeclaredSize(byte[] zip, String entryName, int declared) {
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        byte[] copy = zip.clone();
        for (int i = 0; i + 46 + name.length <= copy.length; i++) {
            boolean header = copy[i] == 0x50 && copy[i + 1] == 0x4b && copy[i + 2] == 0x01 && copy[i + 3] == 0x02;
            if (!header) {
                continue;
            }
            int nameLength = (copy[i + 28] & 0xff) | ((copy[i + 29] & 0xff) << 8);
            if (nameLength != name.length) {
                continue;
            }
            boolean sameName = true;
            for (int j = 0; j < name.length; j++) {
                if (copy[i + 46 + j] != name[j]) {
                    sameName = false;
                    break;
                }
            }
            if (!sameName) {
                continue;
            }
            copy[i + 24] = (byte) declared;
            copy[i + 25] = (byte) (declared >>> 8);
            copy[i + 26] = (byte) (declared >>> 16);
            copy[i + 27] = (byte) (declared >>> 24);
            return copy;
        }
        throw new AssertionError("entry not found in central directory: " + entryName);
    }
}
