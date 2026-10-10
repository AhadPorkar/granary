package dev.granary.format.terraform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderFilenameTest {

    @Test
    void parsesTheHashicorpConvention() {
        ProviderFilename f = ProviderFilename.parse("terraform-provider-null_3.2.4_linux_amd64.zip").orElseThrow();
        assertThat(f.type()).isEqualTo("null");
        assertThat(f.version()).isEqualTo("3.2.4");
        assertThat(f.os()).isEqualTo("linux");
        assertThat(f.arch()).isEqualTo("amd64");
        assertThat(f.platformKey()).isEqualTo("linux_amd64");
        assertThat(ProviderFilename.of("null", "3.2.4", "linux", "amd64")).isEqualTo("terraform-provider-null_3.2.4_linux_amd64.zip");
    }

    @Test
    void hyphenatedTypesAndPrereleases() {
        ProviderFilename f = ProviderFilename.parse("terraform-provider-google-beta_5.1.0-rc1_darwin_arm64.zip").orElseThrow();
        assertThat(f.type()).isEqualTo("google-beta");
        assertThat(f.version()).isEqualTo("5.1.0-rc1");
        assertThat(f.os()).isEqualTo("darwin");
        assertThat(f.arch()).isEqualTo("arm64");
    }

    @ParameterizedTest
    @ValueSource(strings = {"terraform-provider-null_3.2.4_SHA256SUMS", "terraform-provider-null_3.2.4_SHA256SUMS.sig",
            "terraform-provider-null_3.2.4_linux_amd64.tar.gz", "terraform-provider-null_linux_amd64.zip", "random.zip", ""})
    void anythingElseIsNotAnArchiveName(String name) {
        assertThat(ProviderFilename.parse(name)).isEmpty();
    }

    @Test
    void nullIsHandled() {
        assertThat(ProviderFilename.parse(null)).isEmpty();
        assertThat(ProviderFilename.isShaSums(null)).isFalse();
        assertThat(ProviderFilename.isSignature(null)).isFalse();
    }

    @Test
    void checksumAndSignatureFilesAreRecognisedByTheirSuffix() {
        assertThat(ProviderFilename.isShaSums("terraform-provider-null_3.2.4_SHA256SUMS")).isTrue();
        assertThat(ProviderFilename.isShaSums("SHA256SUMS")).isTrue();
        assertThat(ProviderFilename.isShaSums("terraform-provider-null_3.2.4_SHA256SUMS.sig")).isFalse();
        assertThat(ProviderFilename.isSignature("terraform-provider-null_3.2.4_SHA256SUMS.sig")).isTrue();
        assertThat(ProviderFilename.isSignature("SHA256SUMS.sig")).isTrue();
        assertThat(ProviderFilename.isSignature("SHA256SUMS")).isFalse();
    }
}
