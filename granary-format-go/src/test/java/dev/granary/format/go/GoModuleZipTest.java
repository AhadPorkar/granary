package dev.granary.format.go;

import static dev.granary.format.go.GoTestZips.entries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoModuleZipTest {

    private static final String MODULE = "example.com/acme/hello";
    private static final String VERSION = "v0.1.0";
    private static final String PREFIX = MODULE + "@" + VERSION + "/";

    @TempDir
    Path dir;

    @Test
    void aWellFormedZipYieldsItsGoMod() throws IOException {
        byte[] zip = GoTestZips.zip(entries(
                PREFIX, "",
                PREFIX + "go.mod", "module example.com/acme/hello\n",
                PREFIX + "hello.go", "package hello\n",
                PREFIX + "internal/", "",
                PREFIX + "internal/x.go", "package internal\n",
                PREFIX + "docs/Café Notes.md", "# notes\n",
                PREFIX + "vendor/modules.txt", "# vendored\n",
                PREFIX + "LICENSE", "MIT\n"));
        GoModuleZip.Contents contents = GoModuleZip.check(write(zip), MODULE, VERSION);
        assertThat(new String(contents.goMod(), StandardCharsets.UTF_8)).isEqualTo("module example.com/acme/hello\n");
        assertThat(contents.files()).isEqualTo(6);
        assertThat(contents.directories()).isEqualTo(2);
        assertThat(contents.uncompressedSize()).isEqualTo("module example.com/acme/hello\n".length() + "package hello\n".length()
                + "package internal\n".length() + "# notes\n".getBytes(StandardCharsets.UTF_8).length + "# vendored\n".length() + "MIT\n".length());
    }

    @Test
    void directoryEntriesAreDroppedByTheCopy() throws IOException {
        byte[] zip = GoTestZips.zip(entries(
                PREFIX, "",
                PREFIX + "go.mod", "module example.com/acme/hello\n",
                PREFIX + "internal/", "",
                PREFIX + "internal/x.go", "package internal\n"));
        Path source = write(zip);
        Path target = dir.resolve("copy.zip");
        GoModuleZip.copyWithoutDirectories(source, target);
        try (java.util.zip.ZipFile copy = new java.util.zip.ZipFile(target.toFile())) {
            assertThat(copy.stream().map(java.util.zip.ZipEntry::getName))
                    .containsExactly(PREFIX + "go.mod", PREFIX + "internal/x.go");
            assertThat(copy.getInputStream(copy.getEntry(PREFIX + "internal/x.go")).readAllBytes())
                    .isEqualTo("package internal\n".getBytes(StandardCharsets.UTF_8));
        }
        GoModuleZip.Contents contents = GoModuleZip.check(target, MODULE, VERSION);
        assertThat(contents.directories()).isZero();
        assertThat(contents.files()).isEqualTo(2);
        assertThat(GoModuleZip.check(source, MODULE, VERSION).uncompressedSize()).isEqualTo(contents.uncompressedSize());
    }

    @Test
    void aZipWithoutGoModIsReportedNotRefused() throws IOException {
        GoModuleZip.Contents contents = GoModuleZip.check(write(GoTestZips.zip(entries(PREFIX + "hello.go", "package hello\n"))), MODULE, VERSION);
        assertThat(contents.goMod()).isNull();
        assertThat(contents.files()).isEqualTo(1);
    }

    @Test
    void anEmptyZipIsValidButEmpty() throws IOException {
        GoModuleZip.Contents contents = GoModuleZip.check(write(GoTestZips.zip(Map.of())), MODULE, VERSION);
        assertThat(contents.files()).isZero();
        assertThat(contents.goMod()).isNull();
    }

    @Test
    void entriesOutsideThePrefixAreRefused() {
        expectInvalid(GoTestZips.zip(entries("hello.go", "package hello\n")), "does not have prefix \"" + PREFIX + "\"");
        expectInvalid(GoTestZips.zip(entries(MODULE + "@v0.2.0/go.mod", "module example.com/acme/hello\n")), "does not have prefix");
        expectInvalid(GoTestZips.zip(entries("example.com/acme/Hello@v0.1.0/go.mod", "module x\n")), "does not have prefix");
        expectInvalid(GoTestZips.zip(entries("/" + PREFIX + "go.mod", "module x\n")), "does not have prefix");
    }

    @Test
    void uncleanAndTraversingPathsAreRefused() {
        expectInvalid(GoTestZips.zip(entries(PREFIX + "../evil.go", "package evil\n")), "file path is not clean");
        expectInvalid(GoTestZips.zip(entries(PREFIX + "a/../b.go", "package b\n")), "file path is not clean");
        expectInvalid(GoTestZips.zip(entries(PREFIX + "./a.go", "package a\n")), "file path is not clean");
        expectInvalid(GoTestZips.zip(entries(PREFIX + "a//b.go", "package b\n")), "file path is not clean");
        expectInvalid(GoTestZips.zip(entries(PREFIX + "a\\b.go", "package b\n")), "invalid char");
        expectInvalid(GoTestZips.zip(entries(PREFIX + "con.go", "package con\n")), "disallowed as path element component on Windows");
        expectInvalid(GoTestZips.zip(entries(PREFIX + "a:b.go", "package b\n")), "invalid char");
    }

    @Test
    void goModRules() {
        expectInvalid(GoTestZips.zip(entries(PREFIX + "sub/go.mod", "module x\n")), "go.mod file not in module root directory");
        expectInvalid(GoTestZips.zip(entries(PREFIX + "GO.MOD", "module x\n")), "go.mod files must have lowercase names");
        expectInvalid(GoTestZips.zip(entries(PREFIX + "sub/Go.mod", "module x\n")), "go.mod file not in module root directory");
    }

    @Test
    void vendoredPackagesAreRefused() {
        expectInvalid(GoTestZips.zip(entries(PREFIX + "vendor/golang.org/x/mod/zip.go", "package zip\n")), "file is in vendor directory");
        expectInvalid(GoTestZips.zip(entries(PREFIX + "cmd/vendor/x/y.go", "package y\n")), "file is in vendor directory");
        assertThat(GoModuleZip.isVendoredPackage("vendor/modules.txt")).isFalse();
        assertThat(GoModuleZip.isVendoredPackage("vendor/x/y.go")).isTrue();
        assertThat(GoModuleZip.isVendoredPackage("a/vendor/x/y.go")).isTrue();
        assertThat(GoModuleZip.isVendoredPackage("a/vendor/y.go")).isFalse();
        assertThat(GoModuleZip.isVendoredPackage("vendors/x/y.go")).isFalse();
    }

    @Test
    void collisionsAreRefused() {
        expectInvalid(GoTestZips.zip(entries(PREFIX + "a.go", "package a\n", PREFIX + "A.go", "package a\n")), "case-insensitive file name collision");
        expectInvalid(GoTestZips.zip(entries(PREFIX + "a.go", "package a\n", PREFIX + "a.go/b.go", "package b\n")), "is both a file and a directory");
        expectInvalid(GoTestZips.zip(entries(PREFIX + "dir/", "", PREFIX + "DIR/x.go", "package x\n")), "case-insensitive file name collision");
    }

    @Test
    void duplicateEntriesAreRefused() throws IOException {
        // ZipOutputStream refuses duplicates itself, so splice two single-entry zips' central directories by hand:
        // simpler to assert the checker directly.
        GoModuleZip.CollisionChecker checker = new GoModuleZip.CollisionChecker();
        checker.check("a/b.go", false);
        assertThatThrownBy(() -> checker.check("a/b.go", false)).isInstanceOf(GoError.class).hasMessageContaining("multiple entries");
        checker.check("a", true); // a directory may be seen many times
    }

    @Test
    void declaredSizesMustMatchTheInflatedBytes() throws IOException {
        byte[] honest = GoTestZips.zip(entries(PREFIX + "go.mod", "module example.com/acme/hello\n", PREFIX + "hello.go", "package hello\n"));
        byte[] lying = GoTestZips.patchDeclaredSize(honest, PREFIX + "hello.go", 3);
        expectInvalid(lying, "uncompressed size 14 does not match the 3 bytes declared");
    }

    @Test
    void oversizedGoModAndLicenseAreRefused() {
        byte[] big = new byte[(int) GoModulePaths.MAX_GO_MOD + 1];
        Arrays.fill(big, (byte) ' ');
        expectInvalid(GoTestZips.zip(entries(PREFIX + "go.mod", big)), "go.mod file too large");
        expectInvalid(GoTestZips.zip(entries(PREFIX + "LICENSE", big)), "LICENSE file too large");
    }

    @Test
    void garbageIsNotAZip() {
        expectInvalid("definitely not a zip".getBytes(StandardCharsets.UTF_8), "not a valid zip file");
        expectInvalid(new byte[0], "not a valid zip file");
    }

    @Test
    void cleanPaths() {
        assertThat(GoModuleZip.isClean("a/b")).isTrue();
        assertThat(GoModuleZip.isClean("")).isFalse();
        assertThat(GoModuleZip.isClean("/a")).isFalse();
        assertThat(GoModuleZip.isClean("a/")).isFalse();
        assertThat(GoModuleZip.isClean("a/./b")).isFalse();
        assertThat(GoModuleZip.isClean("a/../b")).isFalse();
        assertThat(GoModuleZip.isClean("..")).isFalse();
    }

    private void expectInvalid(byte[] zip, String message) {
        assertThatThrownBy(() -> GoModuleZip.check(write(zip), MODULE, VERSION))
                .isInstanceOf(GoError.class)
                .hasMessageContaining(message)
                .satisfies(e -> assertThat(((GoError) e).status().value()).isEqualTo(400));
    }

    private Path write(byte[] zip) {
        try {
            Path file = Files.createTempFile(dir, "module-", ".zip");
            Files.write(file, zip);
            return file;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
