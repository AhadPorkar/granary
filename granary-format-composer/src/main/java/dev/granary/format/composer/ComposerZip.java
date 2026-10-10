package dev.granary.format.composer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Finds and reads {@code composer.json} inside an uploaded zip, the way Composer's {@code Util\Zip} does for
 * artifact repositories: at the root of the archive, or inside its single top-level directory (the layout
 * {@code git archive --prefix}, GitHub and GitLab archives have). {@code __MACOSX} resource forks are ignored.
 */
public final class ComposerZip {

    static final String COMPOSER_JSON = "composer.json";
    /** composer.json files are a few kilobytes; anything this large is not one. */
    static final int MAX_COMPOSER_JSON = 1024 * 1024;

    private ComposerZip() {
    }

    /** Reads and parses the archive's {@code composer.json}; any problem is a {@link ComposerError} (400). */
    public static ObjectNode readComposerJson(Path zip, ObjectMapper mapper) {
        byte[] json;
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry entry = locate(file);
            if (entry.getSize() > MAX_COMPOSER_JSON) {
                throw ComposerError.invalidPackage("composer.json in the archive is larger than 1 MiB");
            }
            try (InputStream in = file.getInputStream(entry)) {
                json = readLimited(in);
            }
        } catch (ZipException e) {
            throw ComposerError.invalidPackage("The upload is not a valid zip archive: " + e.getMessage());
        } catch (IOException e) {
            throw ComposerError.invalidPackage("The zip archive cannot be read: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            // malformed entry names (invalid UTF-8) surface as IllegalArgumentException from java.util.zip
            throw ComposerError.invalidPackage("The zip archive has malformed entry names");
        }
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (JacksonException e) {
            throw ComposerError.invalidPackage("composer.json in the archive is not valid JSON: " + e.getOriginalMessage());
        }
        if (!(node instanceof ObjectNode object)) {
            throw ComposerError.invalidPackage("composer.json in the archive must contain a JSON object");
        }
        return object;
    }

    /** {@code Zip::locateFile}: the root {@code composer.json}, else the one in the only top-level directory. */
    static ZipEntry locate(ZipFile file) {
        ZipEntry root = file.getEntry(COMPOSER_JSON);
        if (root != null && !root.isDirectory()) {
            return root;
        }
        Set<String> topLevel = new LinkedHashSet<>();
        Enumeration<? extends ZipEntry> entries = file.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.contains("__MACOSX")) {
                continue;
            }
            String trimmed = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            int slash = trimmed.indexOf('/');
            if (slash < 0) {
                // an entry at the top level: a directory record ("dir/"), or a file next to the expected directory
                topLevel.add(name);
            } else {
                topLevel.add(trimmed.substring(0, slash) + "/");
            }
            if (topLevel.size() > 1) {
                throw ComposerError.invalidPackage("The archive has more than one top-level entry and no composer.json "
                        + "at the top level; top-level entries found: " + String.join(", ", topLevel));
            }
        }
        if (topLevel.size() == 1) {
            String dir = topLevel.iterator().next();
            if (dir.endsWith("/")) {
                ZipEntry nested = file.getEntry(dir + COMPOSER_JSON);
                if (nested != null && !nested.isDirectory()) {
                    return nested;
                }
            }
        }
        throw ComposerError.invalidPackage("No composer.json found either at the top level of the archive or within "
                + "its topmost directory");
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_COMPOSER_JSON) {
                throw ComposerError.invalidPackage("composer.json in the archive is larger than 1 MiB");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
