package dev.granary.format.maven;

import dev.granary.core.storage.StorageService;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Maven 2 repository path into its coordinates.
 *
 * <pre>
 *   org/example/lib/1.2.0/lib-1.2.0-sources.jar          → ARTIFACT  g=org.example a=lib v=1.2.0 c=sources e=jar
 *   org/example/lib/1.2.0/lib-1.2.0.jar.sha1             → CHECKSUM  (of the jar above, algorithm sha1)
 *   org/example/lib/maven-metadata.xml                   → METADATA  artifact level (versions)
 *   org/example/lib/1.0-SNAPSHOT/maven-metadata.xml      → METADATA  snapshot version level
 *   org/example/lib/1.0-SNAPSHOT/lib-1.0-20260101.120000-3.jar → ARTIFACT timestamped snapshot
 * </pre>
 */
public record MavenPath(String path, Kind kind, String groupId, String artifactId, String version,
                        String fileName, String classifier, String extension, String checksumAlgorithm,
                        String checksumOf) {

    public enum Kind {
        /** A regular artifact file (jar, pom, …, including .asc signatures). */
        ARTIFACT,
        /** A {@code .sha1}/{@code .md5}/{@code .sha256}/{@code .sha512} sidecar. {@link #checksumOf()} is the subject path. */
        CHECKSUM,
        /** A {@code maven-metadata.xml}. */
        METADATA,
        /** A directory (path is empty or ends with a slash). */
        DIRECTORY,
        /** Anything else, e.g. {@code .index/} files or archetype catalogs. Stored verbatim. */
        OTHER
    }

    public static final String METADATA_FILE = "maven-metadata.xml";
    private static final Pattern SNAPSHOT_TIMESTAMP = Pattern.compile("^(\\d{8}\\.\\d{6})-(\\d+)$");
    private static final Pattern CHECKSUM_EXT = Pattern.compile("^(.*)\\.(sha1|md5|sha256|sha512)$", Pattern.CASE_INSENSITIVE);

    public static MavenPath parse(String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        if (path.isEmpty() || path.endsWith("/")) {
            return new MavenPath(path, Kind.DIRECTORY, null, null, null, null, null, null, null, null);
        }
        Matcher checksum = CHECKSUM_EXT.matcher(path);
        if (checksum.matches()) {
            MavenPath subject = parse(checksum.group(1));
            return new MavenPath(path, Kind.CHECKSUM, subject.groupId, subject.artifactId, subject.version,
                    fileName(path), subject.classifier, subject.extension,
                    checksum.group(2).toLowerCase(Locale.ROOT), checksum.group(1));
        }
        String[] segments = path.split("/");
        String file = segments[segments.length - 1];
        if (METADATA_FILE.equals(file)) {
            return parseMetadata(path, segments);
        }
        if (segments.length < 4) {
            return new MavenPath(path, Kind.OTHER, null, null, null, file, null, null, null, null);
        }
        String version = segments[segments.length - 2];
        String artifactId = segments[segments.length - 3];
        String groupId = String.join(".", java.util.Arrays.copyOfRange(segments, 0, segments.length - 3));
        String prefix = artifactId + "-";
        if (!file.startsWith(prefix) || groupId.startsWith(".")) {
            return new MavenPath(path, Kind.OTHER, groupId, artifactId, version, file, null, null, null, null);
        }
        String rest = file.substring(prefix.length());
        String versionPart = versionPartOf(rest, version);
        if (versionPart == null) {
            return new MavenPath(path, Kind.OTHER, groupId, artifactId, version, file, null, null, null, null);
        }
        String tail = rest.substring(versionPart.length()); // "-classifier.ext" or ".ext"
        String classifier = null;
        String extension;
        if (tail.startsWith("-")) {
            int dot = tail.indexOf('.');
            if (dot < 0) {
                return new MavenPath(path, Kind.OTHER, groupId, artifactId, version, file, null, null, null, null);
            }
            classifier = tail.substring(1, dot);
            extension = tail.substring(dot + 1);
        } else if (tail.startsWith(".")) {
            extension = tail.substring(1);
        } else {
            return new MavenPath(path, Kind.OTHER, groupId, artifactId, version, file, null, null, null, null);
        }
        if (extension.isEmpty()) {
            return new MavenPath(path, Kind.OTHER, groupId, artifactId, version, file, null, null, null, null);
        }
        return new MavenPath(path, Kind.ARTIFACT, groupId, artifactId, version, file, classifier, extension, null, null);
    }

    /** The part of the file name that encodes the version: either the directory version or a timestamped snapshot. */
    private static String versionPartOf(String rest, String version) {
        if (rest.startsWith(version) && boundary(rest, version.length())) {
            return version;
        }
        if (isSnapshot(version)) {
            String base = version.substring(0, version.length() - "-SNAPSHOT".length()) + "-";
            if (rest.startsWith(base)) {
                String after = rest.substring(base.length());
                // timestamp is 8.6 digits, then -buildNumber
                Matcher m = Pattern.compile("^(\\d{8}\\.\\d{6}-\\d+)").matcher(after);
                if (m.find() && boundary(after, m.end())) {
                    return base + m.group(1);
                }
            }
        }
        return null;
    }

    private static boolean boundary(String s, int index) {
        return index == s.length() || s.charAt(index) == '-' || s.charAt(index) == '.';
    }

    private static MavenPath parseMetadata(String path, String[] segments) {
        // g/a/maven-metadata.xml (artifact level) or g/a/1.0-SNAPSHOT/maven-metadata.xml (version level)
        if (segments.length >= 3 && isSnapshot(segments[segments.length - 2])) {
            String version = segments[segments.length - 2];
            String artifactId = segments[segments.length - 3];
            String groupId = segments.length > 3
                    ? String.join(".", java.util.Arrays.copyOfRange(segments, 0, segments.length - 3)) : "";
            return new MavenPath(path, Kind.METADATA, groupId, artifactId, version, METADATA_FILE, null, "xml", null, null);
        }
        if (segments.length >= 2) {
            String artifactId = segments[segments.length - 2];
            String groupId = segments.length > 2
                    ? String.join(".", java.util.Arrays.copyOfRange(segments, 0, segments.length - 2)) : "";
            return new MavenPath(path, Kind.METADATA, groupId, artifactId, null, METADATA_FILE, null, "xml", null, null);
        }
        return new MavenPath(path, Kind.METADATA, "", null, null, METADATA_FILE, null, "xml", null, null);
    }

    public static boolean isSnapshot(String version) {
        return version != null && version.endsWith("-SNAPSHOT");
    }

    public boolean isSnapshot() {
        return isSnapshot(version);
    }

    /** True for {@code lib-1.0-SNAPSHOT.jar} style names, which upstream rewrites on every build. */
    public boolean isMutableSnapshotArtifact() {
        return kind == Kind.ARTIFACT && isSnapshot() && fileName.contains(version);
    }

    public boolean isTimestampedSnapshot() {
        return kind == Kind.ARTIFACT && isSnapshot() && !fileName.contains(version);
    }

    /** The snapshot timestamp and build number of a timestamped snapshot file, e.g. {@code 20260101.120000-3}. */
    public Optional<String> snapshotTimestamp() {
        if (!isTimestampedSnapshot()) {
            return Optional.empty();
        }
        String base = version.substring(0, version.length() - "-SNAPSHOT".length());
        String rest = fileName.substring(artifactId.length() + 1 + base.length() + 1);
        int cut = rest.indexOf(classifier == null ? "." + extension : "-" + classifier + "." + extension);
        if (cut < 0) {
            return Optional.empty();
        }
        String ts = rest.substring(0, cut);
        return SNAPSHOT_TIMESTAMP.matcher(ts).matches() ? Optional.of(ts) : Optional.empty();
    }

    public boolean isArtifactLevelMetadata() {
        return kind == Kind.METADATA && version == null;
    }

    public boolean isVersionLevelMetadata() {
        return kind == Kind.METADATA && version != null;
    }

    /** Whether this path must be re-validated against upstream instead of being cached forever. */
    public boolean isMutable() {
        return kind == Kind.METADATA
                || isMutableSnapshotArtifact()
                || (kind == Kind.CHECKSUM && parse(checksumOf).isMutable());
    }

    /**
     * The component a file belongs to: groupId, artifactId and (directory) version of an artifact, or of the artifact
     * a checksum sidecar describes, so that deleting a component takes its checksums along. Metadata documents, their
     * checksums, directories and files outside the Maven layout belong to none.
     */
    public Optional<StorageService.ComponentCoordinates> component() {
        MavenPath subject = kind == Kind.CHECKSUM ? parse(checksumOf) : this;
        if (subject.kind != Kind.ARTIFACT) {
            return Optional.empty();
        }
        return Optional.of(new StorageService.ComponentCoordinates(subject.groupId, subject.artifactId, subject.version));
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
