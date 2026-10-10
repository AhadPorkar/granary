package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RawContentTypesTest {

    @Test
    void knowsWebAssetsAndArchivesOnTopOfTheCoreTable() {
        assertThat(RawContentTypes.guess("lib/app.js")).isEqualTo("text/javascript");
        assertThat(RawContentTypes.guess("style.CSS")).isEqualTo("text/css");
        assertThat(RawContentTypes.guess("logo.svg")).isEqualTo("image/svg+xml");
        assertThat(RawContentTypes.guess("font.woff2")).isEqualTo("font/woff2");
        assertThat(RawContentTypes.guess("demo.jar")).isEqualTo("application/java-archive");
        assertThat(RawContentTypes.guess("dist.tar.gz")).isEqualTo("application/gzip");
        assertThat(RawContentTypes.guess("dist.tar.xz")).isEqualTo("application/x-xz");
        assertThat(RawContentTypes.guess("setup.exe")).isEqualTo("application/vnd.microsoft.portable-executable");
        assertThat(RawContentTypes.guess("module.wasm")).isEqualTo("application/wasm");
        assertThat(RawContentTypes.guess("config.yml")).isEqualTo("application/yaml");
    }

    @Test
    void unknownAndMissingExtensionsAreOctetStream() {
        assertThat(RawContentTypes.guess("binaries/zlib/1.3.1/abcdef")).isEqualTo("application/octet-stream");
        assertThat(RawContentTypes.guess("file.unknownext")).isEqualTo("application/octet-stream");
        assertThat(RawContentTypes.guess("trailingdot.")).isEqualTo("application/octet-stream");
        assertThat(RawContentTypes.guess(null)).isEqualTo("application/octet-stream");
        assertThat(RawContentTypes.guess("")).isEqualTo("application/octet-stream");
    }
}
