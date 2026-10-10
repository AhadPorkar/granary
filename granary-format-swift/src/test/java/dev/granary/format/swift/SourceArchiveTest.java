package dev.granary.format.swift;

import static dev.granary.format.swift.SwiftTestSupport.MANIFEST;
import static dev.granary.format.swift.SwiftTestSupport.MANIFEST_58;
import static dev.granary.format.swift.SwiftTestSupport.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class SourceArchiveTest {

    @TempDir
    Path dir;

    private SourceArchive inspect(byte[] bytes) throws IOException {
        Path file = Files.createTempFile(dir, "archive", ".zip");
        Files.write(file, bytes);
        return SourceArchive.inspect(file);
    }

    private void assertInvalid(byte[] bytes, String detail) {
        assertThatThrownBy(() -> inspect(bytes))
                .isInstanceOfSatisfying(SwiftProblem.class, p -> {
                    assertThat(p.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(p.code()).isEqualTo("swift.archive.invalid");
                    assertThat(p.detail()).contains(detail);
                });
    }

    @Test
    void readsManifestsBelowTheSingleTopLevelDirectory() throws IOException {
        SourceArchive archive = inspect(SwiftTestSupport.sourceArchive("demo", "a", true));
        assertThat(archive.root()).isEqualTo("demo");
        assertThat(archive.manifests()).containsOnlyKeys("Package.swift", "Package@swift-5.8.swift");
        assertThat(archive.manifests().keySet()).first().isEqualTo("Package.swift");
        assertThat(archive.manifest().toolsVersion()).isEqualTo("5.9");
        assertThat(new String(archive.manifest().content(), StandardCharsets.UTF_8)).isEqualTo(MANIFEST);
        assertThat(archive.alternates()).containsExactly(new SwiftManifests.Alternate("Package@swift-5.8.swift", "5.8", null));
    }

    @Test
    void worksWithoutDirectoryEntries() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("LinkedList-1.1.1/Package.swift", MANIFEST);
        entries.put("LinkedList-1.1.1/Sources/LinkedList/LinkedList.swift", "struct L {}");
        SourceArchive archive = inspect(zip(entries));
        assertThat(archive.root()).isEqualTo("LinkedList-1.1.1");
        assertThat(archive.alternates()).isEmpty();
    }

    @Test
    void manifestsInSubdirectoriesAreIgnored() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("demo/Package.swift", MANIFEST);
        entries.put("demo/Examples/Package.swift", "not a manifest");
        entries.put("demo/Examples/Package@swift-5.swift", "not a manifest");
        assertThat(inspect(zip(entries)).manifests()).containsOnlyKeys("Package.swift");
    }

    @Test
    void rejectsManifestAtTheRoot() {
        assertInvalid(zip(Map.of("Package.swift", MANIFEST, "Sources/Demo/Demo.swift", "")),
                "single top-level directory");
    }

    @Test
    void rejectsSeveralTopLevelDirectories() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("a/Package.swift", MANIFEST);
        entries.put("b/Package.swift", MANIFEST);
        assertInvalid(zip(entries), "exactly one top-level directory, found 2: a, b");
    }

    @Test
    void acceptsFilesNextToTheTopLevelDirectory() throws IOException {
        // SwiftPM counts only directories when it strips the first level and drops loose files
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("demo/Package.swift", MANIFEST);
        entries.put("README.md", "x");
        assertThat(inspect(zip(entries)).root()).isEqualTo("demo");
    }

    @Test
    void rejectsMissingManifest() {
        assertInvalid(zip(Map.of("demo/Sources/Demo/Demo.swift", "")),
                "package doesn't contain a valid manifest (Package.swift) file in 'demo/'");
    }

    @Test
    void rejectsManifestWithoutToolsVersion() {
        assertInvalid(zip(Map.of("demo/Package.swift", "import PackageDescription\n")),
                "Package.swift does not start with a valid '// swift-tools-version:' line");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("demo/Package.swift", MANIFEST);
        entries.put("demo/Package@swift-5.8.swift", "let x = 1");
        assertInvalid(zip(entries), "Package@swift-5.8.swift does not start");
    }

    @Test
    void rejectsUnsafeEntryNames() {
        assertInvalid(zip(Map.of("demo/../../evil", "x", "demo/Package.swift", MANIFEST)), "unsafe entry name 'demo/../../evil'");
        assertInvalid(zip(Map.of("/etc/passwd", "x")), "unsafe entry name");
        assertInvalid(zip(Map.of("demo\\Package.swift", MANIFEST)), "unsafe entry name");
        assertInvalid(zip(Map.of("C:/demo/Package.swift", MANIFEST)), "unsafe entry name");
    }

    @Test
    void rejectsGarbageAndEmptyArchives() {
        assertInvalid("not a zip".getBytes(StandardCharsets.UTF_8), "not a valid zip file");
        assertInvalid(zip(Map.of()), "empty");
    }

    @Test
    void rejectsOversizedManifest() {
        String big = "// swift-tools-version:5.9\n" + "x".repeat(SourceArchive.MAX_MANIFEST_BYTES);
        assertInvalid(zip(Map.of("demo/Package.swift", big)), "larger than");
    }

    @Test
    void unsafeNames() {
        assertThat(SourceArchive.isSafe("demo/Package.swift")).isTrue();
        assertThat(SourceArchive.isSafe("demo/..hidden")).isTrue();
        assertThat(SourceArchive.isSafe("demo/../x")).isFalse();
        assertThat(SourceArchive.isSafe("..")).isFalse();
        assertThat(SourceArchive.isSafe("/abs")).isFalse();
        assertThat(SourceArchive.isSafe("a\\b")).isFalse();
        assertThat(SourceArchive.isSafe("a\0b")).isFalse();
        assertThat(SourceArchive.isSafe("c:x")).isFalse();
        assertThat(SourceArchive.isSafe("")).isFalse();
        assertThat(SourceArchive.isSafe(null)).isFalse();
    }

    @Test
    void variantFixtureIsReadable() {
        assertThat(SwiftManifests.toolsVersion(MANIFEST_58)).contains("5.8");
    }
}
