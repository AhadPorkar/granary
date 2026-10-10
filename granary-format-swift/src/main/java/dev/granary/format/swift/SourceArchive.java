package dev.granary.format.swift;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.springframework.http.HttpStatus;

/**
 * What a published source archive contains, checked the way SwiftPM will consume it.
 *
 * <p>{@code swift package archive-source} (and {@code swift package-registry publish}, which uses it) zips the
 * package directory itself, so every entry sits below one top-level directory; when SwiftPM downloads a release
 * it extracts the archive and moves the content of that single directory up ("strip first level"), failing when
 * there is not exactly one. The archive is therefore accepted only when it has exactly one top-level directory
 * with a {@code Package.swift} in it, no entry escapes the extraction directory, and every manifest declares a
 * valid tools version. The manifests are returned so they can be served without opening the archive again.
 *
 * @param root      the single top-level directory
 * @param manifests the manifests below it by file name, {@code Package.swift} first, variants in name order
 */
public record SourceArchive(String root, Map<String, Manifest> manifests) {

    /** Largest manifest read into memory; real manifests are a few kilobytes. */
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    private static final String MANIFEST_AT_ROOT = "The source archive must hold the package in a single top-level directory "
            + "(as 'swift package archive-source' creates it); Package.swift was found at the root of the archive or not at all";

    /** A manifest file and the tools version it declares. */
    public record Manifest(String filename, byte[] content, String toolsVersion) {
    }

    public Manifest manifest() {
        return manifests.get(SwiftManifests.MANIFEST);
    }

    /** The version-specific manifests, as {@link SwiftManifests.Alternate}s without URL. */
    public List<SwiftManifests.Alternate> alternates() {
        List<SwiftManifests.Alternate> result = new ArrayList<>();
        for (Manifest m : manifests.values()) {
            if (SwiftManifests.isAlternateFileName(m.filename())) {
                result.add(new SwiftManifests.Alternate(m.filename(), m.toolsVersion(), null));
            }
        }
        return result;
    }

    /** Inspects the zip file at {@code zip}; throws a 422 {@link SwiftProblem} describing the first violation. */
    public static SourceArchive inspect(Path zip) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            Set<String> topLevel = new TreeSet<>();
            List<ZipEntry> entries = new ArrayList<>();
            Enumeration<? extends ZipEntry> e = file.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (!isSafe(name)) {
                    throw invalid("The source archive contains an unsafe entry name '" + printable(name) + "'");
                }
                int slash = name.indexOf('/');
                if (slash > 0) {
                    topLevel.add(name.substring(0, slash));
                }
                entries.add(entry);
            }
            if (entries.isEmpty()) {
                throw invalid("The source archive is empty");
            }
            if (topLevel.size() != 1) {
                throw invalid(topLevel.isEmpty()
                        ? MANIFEST_AT_ROOT
                        : "The source archive must have exactly one top-level directory, found " + topLevel.size() + ": "
                        + printable(String.join(", ", topLevel.stream().limit(5).toList())));
            }
            String root = topLevel.iterator().next();
            String prefix = root + "/";
            Map<String, Manifest> found = new TreeMap<>();
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix)) {
                    continue;
                }
                String relative = name.substring(prefix.length());
                if (relative.contains("/") || !SwiftManifests.isManifestFileName(relative)) {
                    continue;
                }
                if (found.containsKey(relative)) {
                    throw invalid("The source archive contains " + relative + " twice");
                }
                byte[] content = read(file, entry, relative);
                String text = new String(content, StandardCharsets.UTF_8);
                String tools = SwiftManifests.toolsVersion(text).orElseThrow(() -> invalid(relative
                        + " does not start with a valid '// swift-tools-version:' line"));
                found.put(relative, new Manifest(relative, content, tools));
            }
            if (!found.containsKey(SwiftManifests.MANIFEST)) {
                if (entries.stream().anyMatch(entry -> SwiftManifests.MANIFEST.equals(entry.getName()))) {
                    throw invalid(MANIFEST_AT_ROOT);
                }
                throw invalid("package doesn't contain a valid manifest (Package.swift) file in '" + printable(root) + "/'");
            }
            Map<String, Manifest> ordered = new LinkedHashMap<>();
            ordered.put(SwiftManifests.MANIFEST, found.remove(SwiftManifests.MANIFEST));
            ordered.putAll(found);
            return new SourceArchive(root, ordered);
        } catch (ZipException | IllegalArgumentException | SecurityException ex) {
            throw invalid("The source archive is not a valid zip file: " + ex.getMessage());
        }
    }

    private static byte[] read(ZipFile file, ZipEntry entry, String name) throws IOException {
        try (InputStream in = file.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) {
                throw invalid(name + " is larger than " + MAX_MANIFEST_BYTES + " bytes");
            }
            return bytes;
        } catch (ZipException ex) {
            throw invalid("Cannot read " + name + " from the source archive: " + ex.getMessage());
        }
    }

    /** Relative, forward-slash names that stay inside the extraction directory. */
    static boolean isSafe(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.indexOf('\0') >= 0) {
            return false;
        }
        if (name.length() >= 2 && name.charAt(1) == ':' && Character.isLetter(name.charAt(0))) {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if (segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static String printable(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 200; i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        return sb.toString();
    }

    private static SwiftProblem invalid(String detail) {
        return new SwiftProblem(HttpStatus.UNPROCESSABLE_CONTENT, "swift.archive.invalid", detail);
    }
}
