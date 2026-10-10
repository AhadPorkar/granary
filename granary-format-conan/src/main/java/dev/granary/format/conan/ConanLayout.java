package dev.granary.format.conan;

import dev.granary.core.storage.ContentTypes;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where files live inside a repository, for hosted uploads and proxy caches alike:
 * <pre>
 * conans/{name}/{version}/{user}/{channel}/{rrev}/export/{file}
 * conans/{name}/{version}/{user}/{channel}/{rrev}/package/{package_id}/{prev}/{file}
 * </pre>
 * Everything a client can ask for (revision lists, file lists, package searches) is derived from these paths, so
 * there is no index that could get out of sync with the stored files.
 */
public final class ConanLayout {

    public static final String ROOT = "conans/";
    static final String EXPORT = "export";
    static final String PACKAGE = "package";

    private ConanLayout() {
    }

    public static String recipePrefix(ConanReference ref) {
        return ROOT + ref.name() + "/" + ref.version() + "/" + ref.userSegment() + "/" + ref.channelSegment() + "/";
    }

    public static String revisionPrefix(ConanReference ref, String rrev) {
        return recipePrefix(ref) + rrev + "/";
    }

    public static String exportPrefix(ConanReference ref, String rrev) {
        return revisionPrefix(ref, rrev) + EXPORT + "/";
    }

    public static String exportPath(ConanReference ref, String rrev, String file) {
        return exportPrefix(ref, rrev) + file;
    }

    public static String packagesPrefix(ConanReference ref, String rrev) {
        return revisionPrefix(ref, rrev) + PACKAGE + "/";
    }

    public static String packagePrefix(ConanReference ref, String rrev, String packageId) {
        return packagesPrefix(ref, rrev) + packageId + "/";
    }

    public static String packageRevisionPrefix(ConanReference ref, String rrev, String packageId, String prev) {
        return packagePrefix(ref, rrev, packageId) + prev + "/";
    }

    public static String packagePath(ConanReference ref, String rrev, String packageId, String prev, String file) {
        return packageRevisionPrefix(ref, rrev, packageId, prev) + file;
    }

    /** One stored file, located by its path. {@code packageId} and {@code packageRevision} are {@code null} for recipe files. */
    public record StoredFile(ConanReference reference, String recipeRevision, String packageId, String packageRevision,
                             String file) {
        public boolean isPackage() {
            return packageId != null;
        }
    }

    /** The reverse of the path builders; empty for paths that do not follow the layout. */
    public static Optional<StoredFile> parse(String storedPath) {
        if (storedPath == null || !storedPath.startsWith(ROOT)) {
            return Optional.empty();
        }
        String[] s = storedPath.split("/", -1);
        // conans n v u c rrev export file...   -> at least 8 segments
        if (s.length < 8) {
            return Optional.empty();
        }
        for (int i = 1; i <= 5; i++) {
            if (!ConanReference.isValidSegment(s[i])) {
                return Optional.empty();
            }
        }
        ConanReference ref;
        try {
            ref = new ConanReference(s[1], s[2], s[3], s[4]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String rrev = s[5];
        List<String> rest = Arrays.asList(s).subList(6, s.length);
        if (rest.get(0).equals(EXPORT)) {
            String file = ConanPath.file(rest.subList(1, rest.size()));
            return file == null ? Optional.empty() : Optional.of(new StoredFile(ref, rrev, null, null, file));
        }
        // conans n v u c rrev package pid prev file...  -> at least 10 segments
        if (!rest.get(0).equals(PACKAGE) || rest.size() < 4
                || !ConanReference.isValidSegment(rest.get(1)) || !ConanReference.isValidSegment(rest.get(2))) {
            return Optional.empty();
        }
        String file = ConanPath.file(rest.subList(3, rest.size()));
        return file == null ? Optional.empty()
                : Optional.of(new StoredFile(ref, rrev, rest.get(1), rest.get(2), file));
    }

    /** The folder of a revision whose files may change without a new revision ({@code conan upload --metadata}). */
    public static final String METADATA_FOLDER = "metadata/";

    /**
     * Whether a stored path (or a bare file name) is a metadata file: signatures, logs and other attachments
     * {@code conan upload --metadata} may replace without changing the revision.
     */
    public static boolean isMutableFile(String path) {
        if (path == null) {
            return false;
        }
        if (path.startsWith(METADATA_FOLDER)) {
            return true;
        }
        return parse(path).map(f -> f.file().startsWith(METADATA_FOLDER)).orElse(false);
    }

    /** The media type of a Conan file; the client does not care, but browsers and the UI do. */
    public static String contentType(String file) {
        String name = file == null ? "" : file.toLowerCase(Locale.ROOT);
        if (name.endsWith(".py")) {
            return "text/x-python";
        }
        if (name.endsWith(".txt")) {
            return "text/plain";
        }
        if (name.endsWith(".tgz") || name.endsWith(".gz")) {
            return "application/gzip";
        }
        if (name.endsWith(".txz") || name.endsWith(".xz")) {
            return "application/x-xz";
        }
        if (name.endsWith(".tzst") || name.endsWith(".zst")) {
            return "application/zstd";
        }
        return ContentTypes.guess(name);
    }
}
