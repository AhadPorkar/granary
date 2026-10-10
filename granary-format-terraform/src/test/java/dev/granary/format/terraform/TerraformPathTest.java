package dev.granary.format.terraform;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.terraform.TerraformPath.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TerraformPathTest {

    @Test
    void rootAndDiscovery() {
        assertThat(TerraformPath.parse(null).kind()).isEqualTo(Kind.ROOT);
        assertThat(TerraformPath.parse("").kind()).isEqualTo(Kind.ROOT);
        assertThat(TerraformPath.parse("/").kind()).isEqualTo(Kind.ROOT);
        assertThat(TerraformPath.parse(".well-known/terraform.json").kind()).isEqualTo(Kind.DISCOVERY);
        assertThat(TerraformPath.parse("/.well-known/terraform.json/").kind()).isEqualTo(Kind.DISCOVERY);
        assertThat(TerraformPath.parse(".well-known/other.json").kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void providerRegistryEndpoints() {
        TerraformPath info = TerraformPath.parse("v1/providers/hashicorp/null");
        assertThat(info.kind()).isEqualTo(Kind.PROVIDER_INFO);
        assertThat(info.namespace()).isEqualTo("hashicorp");
        assertThat(info.name()).isEqualTo("null");
        assertThat(info.isProvider()).isTrue();

        assertThat(TerraformPath.parse("v1/providers/hashicorp/null/versions").kind()).isEqualTo(Kind.PROVIDER_VERSIONS);
        assertThat(TerraformPath.parse("v1/providers/hashicorp/null/gpg-key").kind()).isEqualTo(Kind.PROVIDER_GPG_KEY);

        TerraformPath version = TerraformPath.parse("v1/providers/hashicorp/null/3.2.4");
        assertThat(version.kind()).isEqualTo(Kind.PROVIDER_VERSION_INFO);
        assertThat(version.version()).isEqualTo("3.2.4");

        TerraformPath download = TerraformPath.parse("v1/providers/hashicorp/null/3.2.4/download/linux/amd64");
        assertThat(download.kind()).isEqualTo(Kind.PROVIDER_DOWNLOAD);
        assertThat(download.os()).isEqualTo("linux");
        assertThat(download.arch()).isEqualTo("amd64");

        TerraformPath archive = TerraformPath.parse("v1/providers/hashicorp/null/3.2.4/linux/amd64/terraform-provider-null_3.2.4_linux_amd64.zip");
        assertThat(archive.kind()).isEqualTo(Kind.PROVIDER_ARCHIVE);
        assertThat(archive.filename()).isEqualTo("terraform-provider-null_3.2.4_linux_amd64.zip");
        assertThat(archive.os()).isEqualTo("linux");
        assertThat(archive.arch()).isEqualTo("amd64");

        TerraformPath sums = TerraformPath.parse("v1/providers/hashicorp/null/3.2.4/SHA256SUMS");
        assertThat(sums.kind()).isEqualTo(Kind.PROVIDER_SHASUMS);
        assertThat(sums.filename()).isEqualTo("SHA256SUMS");
        TerraformPath sig = TerraformPath.parse("v1/providers/hashicorp/null/3.2.4/SHA256SUMS.sig");
        assertThat(sig.kind()).isEqualTo(Kind.PROVIDER_SHASUMS_SIG);
        assertThat(sig.filename()).isEqualTo("SHA256SUMS.sig");
    }

    @Test
    void versionsAreCanonicalSoOneReleaseHasOneName() {
        assertThat(TerraformPath.parse("v1/providers/acme/demo/v1.0.0/linux/amd64/terraform-provider-demo_1.0.0_linux_amd64.zip").version())
                .isEqualTo("1.0.0");
        assertThat(TerraformPath.parse("v1/providers/acme/demo/v1.0.0/SHA256SUMS").version()).isEqualTo("1.0.0");
        assertThat(TerraformPath.parse("v1/providers/acme/demo/v1.0.0").version()).isEqualTo("1.0.0");
        assertThat(TerraformPath.parse("v1/providers/acme/demo/v1.0.0/download/linux/amd64").version()).isEqualTo("1.0.0");
        assertThat(TerraformPath.parse("v1/modules/acme/demo/local/v1.0.0.tgz").version()).isEqualTo("1.0.0");
        assertThat(TerraformPath.parse("v1/modules/acme/demo/local/v1.0.0").version()).isEqualTo("1.0.0");
        assertThat(TerraformPath.parse("v1/modules/acme/demo/local/v1.0.0/download").version()).isEqualTo("1.0.0");
        assertThat(TerraformPath.parse("files/acme/demo/v1.0.0/x.zip").version()).isEqualTo("1.0.0");
        assertThat(TerraformPath.parse("files/modules/acme/demo/local/v1.0.0.tgz").version()).isEqualTo("1.0.0");
        assertThat(TerraformPath.parse("mirror/registry.terraform.io/acme/demo/v1.0.0.json").version()).isEqualTo("1.0.0");
        assertThat(TerraformPath.parse("v1/providers/acme/demo/1.0.0-rc.1+build.7").version()).isEqualTo("1.0.0-rc.1+build.7");
        assertThat(TerraformPath.canonical("v2.3.4")).isEqualTo("2.3.4");
        assertThat(TerraformPath.canonical("not-a-version")).isEqualTo("not-a-version");
    }

    @Test
    void providerFiles() {
        TerraformPath file = TerraformPath.parse("files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_linux_amd64.zip");
        assertThat(file.kind()).isEqualTo(Kind.PROVIDER_FILE);
        assertThat(file.namespace()).isEqualTo("hashicorp");
        assertThat(file.name()).isEqualTo("null");
        assertThat(file.version()).isEqualTo("3.2.4");
        assertThat(file.filename()).isEqualTo("terraform-provider-null_3.2.4_linux_amd64.zip");
        assertThat(TerraformPath.parse("files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS.sig").kind()).isEqualTo(Kind.PROVIDER_FILE);
        assertThat(TerraformPath.parse("files/hashicorp/null/3.2.4").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(TerraformPath.parse("files/hashicorp/null/3.2.4/a/b").kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void moduleRegistryEndpoints() {
        TerraformPath info = TerraformPath.parse("v1/modules/acme/demo/local");
        assertThat(info.kind()).isEqualTo(Kind.MODULE_INFO);
        assertThat(info.namespace()).isEqualTo("acme");
        assertThat(info.name()).isEqualTo("demo");
        assertThat(info.system()).isEqualTo("local");
        assertThat(info.isProvider()).isFalse();

        assertThat(TerraformPath.parse("v1/modules/acme/demo/local/versions").kind()).isEqualTo(Kind.MODULE_VERSIONS);
        TerraformPath latest = TerraformPath.parse("v1/modules/acme/demo/local/download");
        assertThat(latest.kind()).isEqualTo(Kind.MODULE_DOWNLOAD);
        assertThat(latest.version()).isNull();
        TerraformPath download = TerraformPath.parse("v1/modules/acme/demo/local/1.0.0/download");
        assertThat(download.kind()).isEqualTo(Kind.MODULE_DOWNLOAD);
        assertThat(download.version()).isEqualTo("1.0.0");
        assertThat(TerraformPath.parse("v1/modules/acme/demo/local/1.0.0").kind()).isEqualTo(Kind.MODULE_VERSION_INFO);

        TerraformPath archive = TerraformPath.parse("v1/modules/acme/demo/local/1.0.0.tgz");
        assertThat(archive.kind()).isEqualTo(Kind.MODULE_ARCHIVE);
        assertThat(archive.version()).isEqualTo("1.0.0");
        assertThat(archive.extension()).isEqualTo("tgz");

        TerraformPath file = TerraformPath.parse("files/modules/acme/demo/local/1.0.0.tgz");
        assertThat(file.kind()).isEqualTo(Kind.MODULE_FILE);
        assertThat(file.version()).isEqualTo("1.0.0");
        assertThat(file.extension()).isEqualTo("tgz");
        assertThat(TerraformPath.parse("files/modules/acme/demo/local/1.0.0-rc.1.zip").extension()).isEqualTo("zip");
        assertThat(TerraformPath.parse("files/modules/acme/demo/local/1.0.0.rar").kind()).isEqualTo(Kind.INVALID);
        assertThat(TerraformPath.parse("v1/modules/acme/demo").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(TerraformPath.parse("v1/modules/acme/demo/local/1.0.0/download/x").kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void networkMirrorEndpoints() {
        TerraformPath index = TerraformPath.parse("mirror/registry.terraform.io/hashicorp/null/index.json");
        assertThat(index.kind()).isEqualTo(Kind.MIRROR_INDEX);
        assertThat(index.hostname()).isEqualTo("registry.terraform.io");
        assertThat(index.namespace()).isEqualTo("hashicorp");
        assertThat(index.name()).isEqualTo("null");

        TerraformPath version = TerraformPath.parse("mirror/localhost:8443/acme/demo/1.0.0.json");
        assertThat(version.kind()).isEqualTo(Kind.MIRROR_VERSION);
        assertThat(version.hostname()).isEqualTo("localhost:8443");
        assertThat(version.version()).isEqualTo("1.0.0");

        assertThat(TerraformPath.parse("mirror/registry.terraform.io/hashicorp/null/other.txt").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(TerraformPath.parse("mirror/registry.terraform.io/hashicorp/null/notaversion.json").kind()).isEqualTo(Kind.INVALID);
        assertThat(TerraformPath.parse("mirror/registry.terraform.io/hashicorp/null").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(TerraformPath.parse("mirror/bad host/hashicorp/null/index.json").kind()).isEqualTo(Kind.INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "v1/providers/../etc/passwd", "v1/providers/hashicorp/null/../../x", "v1/providers/hashicorp/null/./versions",
            "v1/providers/hashi corp/null", "v1/providers/hashicorp/nu|ll/versions", "v1/providers/hashicorp/null/notaversion",
            "v1/providers/hashicorp/null/3.2.4/download/Linux/amd64", "v1/providers/hashicorp/null/3.2.4/download/linux/am d64",
            "v1/providers/hashicorp/null/3.2.4/linux/amd64/.hidden.zip", "v1/providers/hashicorp/null/3.2.4/linux/amd64/not-a-zip.tar",
            "v1/providers/hashicorp/null/3.2.4/linux/amd64/a\\b.zip", "v1/modules/acme/demo/local/x.tgz",
            "v1/modules/acme/de..mo/local/versions", "files/acme/null/1.0.0/..", "files/modules/acme/demo/local/1.0.0",
            "v1/providers//null/versions", "v1/providers/-bad/null/versions", "mirror/registry.terraform.io/../null/index.json"})
    void oddPathsAreInvalidNotCrashes(String path) {
        assertThat(TerraformPath.parse(path).kind()).isEqualTo(Kind.INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v1/other/a/b", "v2/providers/a/b/versions", "something/else", "files", "files/a",
            "v1/providers/a", "v1/modules/search", "v1/providers/a/b/1.0.0/x/y/z/w", "mirror", "robots.txt"})
    void unrelatedPathsAreUnknown(String path) {
        assertThat(TerraformPath.parse(path).kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void unicodeAndEncodedSlashesNeverMapToAnEndpoint() {
        assertThat(TerraformPath.parse("v1/providers/hashic\u00f6rp/null/versions").kind()).isEqualTo(Kind.INVALID);
        // the dispatcher decodes %2F before the handler sees the path, so a literal slash splits segments
        assertThat(TerraformPath.parse("v1/providers/hashicorp%2Fnull/versions").kind()).isEqualTo(Kind.INVALID);
        assertThat(TerraformPath.parse("v1/providers/hashicorp/null\u0000/versions").kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void metadataClassification() {
        assertThat(TerraformPath.parse("v1/providers/a/b/versions").isMetadata()).isTrue();
        assertThat(TerraformPath.parse("v1/providers/a/b/1.0.0/download/linux/amd64").isMetadata()).isTrue();
        assertThat(TerraformPath.parse("v1/modules/a/b/c/1.0.0/download").isMetadata()).isTrue();
        assertThat(TerraformPath.parse("mirror/h/a/b/index.json").isMetadata()).isTrue();
        assertThat(TerraformPath.parse(".well-known/terraform.json").isMetadata()).isTrue();
        assertThat(TerraformPath.parse("files/a/b/1.0.0/x.zip").isMetadata()).isFalse();
        assertThat(TerraformPath.parse("files/modules/a/b/c/1.0.0.tgz").isMetadata()).isFalse();
        assertThat(TerraformPath.parse("v1/modules/a/b/c/1.0.0.tgz").isMetadata()).isFalse();
        assertThat(TerraformPath.parse("v1/providers/a/b/1.0.0/SHA256SUMS").isMetadata()).isFalse();
    }
}
