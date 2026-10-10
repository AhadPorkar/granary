package dev.granary.format.terraform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModuleSourceTest {

    private static final String FILE_URL = "https://granary.test/repository/tf/files/modules/hashicorp/consul/aws/0.11.0.tgz";

    @Test
    void githubTarballAsRegistryTerraformIoAnswersIt() {
        ModuleSource.Archive a = ModuleSource.archive(
                "https://api.github.com/repos/hashicorp/terraform-aws-consul/tarball/v0.11.0//*?archive=tar.gz").orElseThrow();
        assertThat(a.url()).isEqualTo(URI.create("https://api.github.com/repos/hashicorp/terraform-aws-consul/tarball/v0.11.0"));
        assertThat(a.subdir()).isEqualTo("*");
        assertThat(a.extension()).isEqualTo("tgz");
        assertThat(ModuleSource.rewrite(a, FILE_URL)).isEqualTo(FILE_URL + "//*");
    }

    @Test
    void subdirectoriesAndOtherQueryParametersAreKept() {
        ModuleSource.Archive a = ModuleSource.archive(
                "https://example.com/dl/mod.zip//modules/vpc?ref=v1&archive=zip&checksum=sha256:abc").orElseThrow();
        assertThat(a.url().toString()).isEqualTo("https://example.com/dl/mod.zip?ref=v1&checksum=sha256:abc");
        assertThat(a.subdir()).isEqualTo("modules/vpc");
        assertThat(a.extension()).isEqualTo("zip");
        assertThat(ModuleSource.rewrite(a, "https://g/files/modules/a/b/c/1.0.0.zip")).isEqualTo("https://g/files/modules/a/b/c/1.0.0.zip//modules/vpc");
    }

    @Test
    void archiveTypeComesFromTheExtensionWhenNotForced() {
        assertThat(ModuleSource.archive("https://example.com/m.tgz").orElseThrow().extension()).isEqualTo("tgz");
        assertThat(ModuleSource.archive("https://example.com/m.tar.gz").orElseThrow().extension()).isEqualTo("tgz");
        assertThat(ModuleSource.archive("https://example.com/m.ZIP").orElseThrow().extension()).isEqualTo("zip");
        assertThat(ModuleSource.archive("https://example.com/m.tar.bz2").orElseThrow().extension()).isEqualTo("tbz2");
        assertThat(ModuleSource.archive("https://example.com/m.txz").orElseThrow().extension()).isEqualTo("txz");
        assertThat(ModuleSource.archive("http://example.com/m.tar").orElseThrow().extension()).isEqualTo("tar");
        assertThat(ModuleSource.archive("https://example.com/m.tgz").orElseThrow().subdir()).isEmpty();
    }

    @Test
    void forcedHttpsGetterIsAccepted() {
        ModuleSource.Archive a = ModuleSource.archive("https::https://example.com/m.tgz").orElseThrow();
        assertThat(a.url()).isEqualTo(URI.create("https://example.com/m.tgz"));
        assertThat(ModuleSource.archive("  https://example.com/m.tgz?archive=tgz  ").orElseThrow().url())
                .isEqualTo(URI.create("https://example.com/m.tgz"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "git::https://github.com/hashicorp/example.git?ref=v1.2.0",
            "git::ssh://git@github.com/org/repo.git",
            "hg::https://example.com/repo",
            "s3::https://s3.amazonaws.com/bucket/key.zip",
            "gcs::https://www.googleapis.com/storage/v1/bucket/module.zip",
            "github.com/hashicorp/example",
            "git@github.com:hashicorp/example.git",
            "https://api.github.com/repos/org/repo/tarball/v1.0.0",
            "https://example.com/module?archive=false",
            "https://example.com/m.tgz?archive=",
            "https://example.com/m.rar",
            "https:///nohost.tgz",
            "ftp://example.com/m.tgz",
            "", "   "})
    void everythingGranaryCannotCacheIsPassedThrough(String source) {
        assertThat(ModuleSource.archive(source)).isEmpty();
    }

    @Test
    void nullIsHandled() {
        assertThat(ModuleSource.archive(null)).isEqualTo(Optional.empty());
    }
}
