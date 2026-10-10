package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.cargo.CargoPath.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CargoPathTest {

    @Test
    void registryLevelPaths() {
        assertThat(CargoPath.parse("").kind()).isEqualTo(Kind.ROOT);
        assertThat(CargoPath.parse(null).kind()).isEqualTo(Kind.ROOT);
        assertThat(CargoPath.parse("me").kind()).isEqualTo(Kind.ME);
        assertThat(CargoPath.parse("me/").kind()).isEqualTo(Kind.ME);
        assertThat(CargoPath.parse("index/config.json").kind()).isEqualTo(Kind.CONFIG);
        assertThat(CargoPath.parse("api/v1/crates").kind()).isEqualTo(Kind.SEARCH);
        assertThat(CargoPath.parse("api/v1/crates/new").kind()).isEqualTo(Kind.PUBLISH);
    }

    @Test
    void indexFiles() {
        assertThat(CargoPath.parse("index/1/a")).isEqualTo(new CargoPath(Kind.INDEX, "a", null));
        assertThat(CargoPath.parse("index/2/ab")).isEqualTo(new CargoPath(Kind.INDEX, "ab", null));
        assertThat(CargoPath.parse("index/3/s/syn")).isEqualTo(new CargoPath(Kind.INDEX, "syn", null));
        assertThat(CargoPath.parse("index/se/rd/serde")).isEqualTo(new CargoPath(Kind.INDEX, "serde", null));
        assertThat(CargoPath.parse("index/se/rd/serde_json")).isEqualTo(new CargoPath(Kind.INDEX, "serde_json", null));
        // other clients may keep the case; the crate is the same
        assertThat(CargoPath.parse("index/In/fl/Inflector")).isEqualTo(new CargoPath(Kind.INDEX, "Inflector", null));
        assertThat(CargoPath.parse("index/in/fl/Inflector")).isEqualTo(new CargoPath(Kind.INDEX, "Inflector", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"index/xx/yy/serde", "index/1/ab", "index/3/x/syn", "index/se/serde", "index/serde",
            "index/se/rd/ex/serde", "index/", "index", "something", "api/v1", "api/v1/crates/serde",
            "api/v1/crates/serde/1.0.0", "api/v1/crates/serde/1.0.0/info", "api/v1/crates/serde/1.0.0/download/x"})
    void unknownPaths(String path) {
        assertThat(CargoPath.parse(path).kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void webApiPaths() {
        assertThat(CargoPath.parse("api/v1/crates/serde/1.0.210/download"))
                .isEqualTo(new CargoPath(Kind.DOWNLOAD, "serde", "1.0.210"));
        assertThat(CargoPath.parse("api/v1/crates/serde/1.0.0-rc.1+build.2/download"))
                .isEqualTo(new CargoPath(Kind.DOWNLOAD, "serde", "1.0.0-rc.1+build.2"));
        assertThat(CargoPath.parse("api/v1/crates/acme-hello/0.1.0/yank"))
                .isEqualTo(new CargoPath(Kind.YANK, "acme-hello", "0.1.0"));
        assertThat(CargoPath.parse("api/v1/crates/acme-hello/0.1.0/unyank"))
                .isEqualTo(new CargoPath(Kind.UNYANK, "acme-hello", "0.1.0"));
        assertThat(CargoPath.parse("api/v1/crates/acme-hello/owners"))
                .isEqualTo(new CargoPath(Kind.OWNERS, "acme-hello", null));
        // a crate called "new" is still reachable
        assertThat(CargoPath.parse("api/v1/crates/new/1.0.0/download")).isEqualTo(new CargoPath(Kind.DOWNLOAD, "new", "1.0.0"));
        assertThat(CargoPath.parse("api/v1/crates/new/owners")).isEqualTo(new CargoPath(Kind.OWNERS, "new", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"index/2/a.", "index/3/./..x", "index/2/éa", "api/v1/crates/../1.0.0/download",
            "api/v1/crates/serde/../download", "api/v1/crates/serde/1..0/download", "api/v1/crates/a b/1.0.0/download",
            "api/v1/crates/serde/1.0.0 /download", "api/v1/crates/sérde/owners", "index\\se\\rd\\serde",
            "api/v1/crates/serde/1.0.0\\x/download", "index//se/rd/serde", "index/2/a\u0000"})
    void oddPathsAreInvalid(String path) {
        assertThat(CargoPath.parse(path).kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void encodedSlashesNeverBecomeANameWithASlash() {
        // the dispatcher decodes %2F before the handler sees the path; the extra segment makes it unknown
        assertThat(CargoPath.parse("index/se/rd/se/rde").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(CargoPath.parse("api/v1/crates/a/b/1.0.0/download").kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void metadataKinds() {
        assertThat(CargoPath.parse("index/config.json").isMetadata()).isTrue();
        assertThat(CargoPath.parse("index/se/rd/serde").isMetadata()).isTrue();
        assertThat(CargoPath.parse("api/v1/crates").isMetadata()).isTrue();
        assertThat(CargoPath.parse("api/v1/crates/serde/owners").isMetadata()).isTrue();
        assertThat(CargoPath.parse("api/v1/crates/serde/1.0.0/download").isMetadata()).isFalse();
        assertThat(CargoPath.parse("api/v1/crates/new").isMetadata()).isFalse();
        assertThat(CargoPath.parse("me").isMetadata()).isFalse();
    }
}
