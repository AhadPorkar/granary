package dev.granary.format.go;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Validates an uploaded module zip the way {@code golang.org/x/mod/zip.CheckZip} does before the go command
 * accepts a download: every entry under {@code <module>@<version>/}, clean and portable file names, no
 * case-insensitive collisions, go.mod only in the root and spelled in lower case, the size limits, and every
 * entry inflating to exactly the size its header declares. Vendored packages are refused as well, because a
 * module zip must never carry them.
 */
public final class GoModuleZip {

    /**
     * What the check learned about a valid zip.
     *
     * @param goMod            the go.mod in the module root, or {@code null} when the zip has none
     * @param uncompressedSize the sum of all entries' sizes
     * @param files            number of regular files
     * @param directories      number of directory entries ({@code zip -r} writes them, the go command never does)
     */
    public record Contents(byte[] goMod, long uncompressedSize, int files, int directories) {
    }

    private GoModuleZip() {
    }

    public static Contents check(Path zipFile, String module, String version) throws IOException {
        String prefix = module + "@" + version + "/";
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            CollisionChecker collisions = new CollisionChecker();
            long size = 0;
            int files = 0;
            int directories = 0;
            byte[] goMod = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String fullName = entry.getName();
                if (!fullName.startsWith(prefix)) {
                    throw GoError.invalidZip(fullName + ": path does not have prefix \"" + prefix + "\"");
                }
                String name = fullName.substring(prefix.length());
                if (name.isEmpty()) {
                    directories++;
                    continue;
                }
                boolean isDir = name.endsWith("/");
                if (isDir) {
                    name = name.substring(0, name.length() - 1);
                }
                if (!isClean(name)) {
                    throw GoError.invalidZip(fullName + ": file path is not clean");
                }
                try {
                    GoModulePaths.checkFilePath(name);
                } catch (GoError e) {
                    throw GoError.invalidZip(fullName + ": " + e.getMessage());
                }
                collisions.check(name, isDir);
                if (isDir) {
                    directories++;
                    continue;
                }
                if (isVendoredPackage(name)) {
                    throw GoError.invalidZip(fullName + ": file is in vendor directory");
                }
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.equalsIgnoreCase("go.mod")) {
                    if (!base.equals(name)) {
                        throw GoError.invalidZip(fullName + ": go.mod file not in module root directory");
                    }
                    if (!name.equals("go.mod")) {
                        throw GoError.invalidZip(fullName + ": go.mod files must have lowercase names");
                    }
                }
                long declared = entry.getSize();
                if (declared >= 0) {
                    if (GoModulePaths.MAX_ZIP_FILE - size < declared) {
                        throw GoError.invalidZip("total uncompressed size of module contents too large (max size is "
                                + GoModulePaths.MAX_ZIP_FILE + " bytes)");
                    }
                    if (name.equals("go.mod") && declared > GoModulePaths.MAX_GO_MOD) {
                        throw GoError.invalidZip(fullName + ": go.mod file too large (max size is " + GoModulePaths.MAX_GO_MOD + " bytes)");
                    }
                    if (name.equals("LICENSE") && declared > GoModulePaths.MAX_LICENSE) {
                        throw GoError.invalidZip(fullName + ": LICENSE file too large (max size is " + GoModulePaths.MAX_LICENSE + " bytes)");
                    }
                }
                boolean capture = name.equals("go.mod");
                Inflated inflated = inflate(zip, entry, capture, GoModulePaths.MAX_ZIP_FILE - size);
                if (declared >= 0 && inflated.size() != declared) {
                    throw GoError.invalidZip(fullName + ": uncompressed size " + inflated.size()
                            + " does not match the " + declared + " bytes declared in the zip header");
                }
                size += inflated.size();
                if (capture) {
                    goMod = inflated.bytes();
                }
                files++;
            }
            return new Contents(goMod, size, files, directories);
        } catch (ZipException e) {
            throw GoError.invalidZip("not a valid zip file: " + e.getMessage());
        }
    }

    /**
     * Copies a checked zip without its directory entries. The go command hashes every entry of a downloaded zip
     * ({@code dirhash.HashZip}) but only the files of the extracted module ({@code dirhash.HashDir}), so a zip
     * with directory entries, which {@code zip -r} writes by default, would make {@code go mod verify} report the
     * module as modified. Zips the go command creates itself never contain them.
     */
    public static void copyWithoutDirectories(Path source, Path target) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (ZipFile zip = new ZipFile(source.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                out.putNextEntry(copy);
                try (InputStream in = zip.getInputStream(entry)) {
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        out.write(buffer, 0, n);
                    }
                }
                out.closeEntry();
            }
        } catch (ZipException e) {
            throw GoError.invalidZip("not a valid zip file: " + e.getMessage());
        }
    }

    private record Inflated(long size, byte[] bytes) {
    }

    private static Inflated inflate(ZipFile zip, ZipEntry entry, boolean capture, long budget) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        java.io.ByteArrayOutputStream captured = capture ? new java.io.ByteArrayOutputStream() : null;
        long total = 0;
        try (InputStream in = zip.getInputStream(entry)) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > budget) {
                    throw GoError.invalidZip("total uncompressed size of module contents too large (max size is "
                            + GoModulePaths.MAX_ZIP_FILE + " bytes)");
                }
                if (captured != null) {
                    if (total > GoModulePaths.MAX_GO_MOD) {
                        throw GoError.invalidZip(entry.getName() + ": go.mod file too large (max size is "
                                + GoModulePaths.MAX_GO_MOD + " bytes)");
                    }
                    captured.write(buffer, 0, n);
                }
            }
        }
        return new Inflated(total, captured == null ? null : captured.toByteArray());
    }

    /** {@code path.Clean(name) == name}: no empty, {@code .} or {@code ..} elements and no leading slash. */
    static boolean isClean(String name) {
        if (name.isEmpty() || name.startsWith("/")) {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** A file inside a {@code vendor} directory that belongs to a vendored package ({@code vendor/modules.txt} is not one). */
    static boolean isVendoredPackage(String name) {
        int i;
        if (name.startsWith("vendor/")) {
            i = "vendor/".length();
        } else {
            int j = name.indexOf("/vendor/");
            if (j < 0) {
                return false;
            }
            i = j + "/vendor/".length();
        }
        return name.indexOf('/', i) >= 0;
    }

    /** {@code zip.collisionChecker}: paths that are equal when folded to lower case may not coexist. */
    static final class CollisionChecker {
        private record Info(String path, boolean isDir) {
        }

        private final Map<String, Info> seen = new HashMap<>();

        void check(String path, boolean isDir) {
            String fold = path.toLowerCase(Locale.ROOT);
            Info other = seen.get(fold);
            if (other != null) {
                if (!path.equals(other.path())) {
                    throw GoError.invalidZip("case-insensitive file name collision: \"" + other.path() + "\" and \"" + path + "\"");
                }
                if (isDir != other.isDir()) {
                    throw GoError.invalidZip("entry \"" + path + "\" is both a file and a directory");
                }
                if (!isDir) {
                    throw GoError.invalidZip("multiple entries for file \"" + path + "\"");
                }
            } else {
                seen.put(fold, new Info(path, isDir));
            }
            int slash = path.lastIndexOf('/');
            if (slash > 0) {
                check(path.substring(0, slash), true);
            }
        }
    }
}
