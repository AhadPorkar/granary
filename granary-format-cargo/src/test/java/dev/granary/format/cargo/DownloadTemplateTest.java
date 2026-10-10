package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DownloadTemplateTest {

    private static final String CKSUM = "ab".repeat(32);

    @Test
    void withoutMarkersCargoAppendsCrateVersionAndDownload() {
        assertThat(DownloadTemplate.expand("https://static.crates.io/crates", "itoa", "1.0.18", CKSUM))
                .isEqualTo("https://static.crates.io/crates/itoa/1.0.18/download");
        assertThat(DownloadTemplate.expand("https://host/api/v1/crates/", "itoa", "1.0.18", CKSUM))
                .isEqualTo("https://host/api/v1/crates/itoa/1.0.18/download");
        assertThat(DownloadTemplate.hasMarkers("https://static.crates.io/crates")).isFalse();
    }

    @Test
    void theDefaultTemplateOfTheSetting() {
        assertThat(DownloadTemplate.expand(CargoSettings.DEFAULT_DOWNLOAD_URL, "itoa", "1.0.18", CKSUM))
                .isEqualTo("https://static.crates.io/crates/itoa/itoa-1.0.18.crate");
    }

    @Test
    void everyMarker() {
        String template = "https://mirror/{prefix}/{lowerprefix}/{crate}/{version}/{sha256-checksum}";
        assertThat(DownloadTemplate.expand(template, "Inflector", "0.11.4", CKSUM))
                .isEqualTo("https://mirror/In/fl/in/fl/Inflector/0.11.4/" + CKSUM);
        assertThat(DownloadTemplate.expand(template, "a", "1.0.0", CKSUM)).isEqualTo("https://mirror/1/1/a/1.0.0/" + CKSUM);
        assertThat(DownloadTemplate.expand(template, "Abc", "1.0.0", null)).isEqualTo("https://mirror/3/A/3/a/Abc/1.0.0/");
        assertThat(DownloadTemplate.hasMarkers(template)).isTrue();
    }

    @Test
    void problems() {
        assertThat(DownloadTemplate.problem(CargoSettings.DEFAULT_DOWNLOAD_URL)).isNull();
        assertThat(DownloadTemplate.problem("http://mirror.local/crates")).isNull();
        assertThat(DownloadTemplate.problem("ftp://mirror/{crate}")).contains("http(s)");
        assertThat(DownloadTemplate.problem("/relative/{crate}")).contains("http(s)");
        assertThat(DownloadTemplate.problem("https://mirror/{crate} {version}")).contains("valid URL");
    }
}
