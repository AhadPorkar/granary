package dev.granary.format.cocoapods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

class PodspecTest {

    /** Shaped like https://cdn.cocoapods.org/Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json. */
    static final String AFNETWORKING = """
            {
              "name": "AFNetworking",
              "version": "4.0.1",
              "license": "MIT",
              "summary": "A delightful networking framework for Apple platforms.",
              "homepage": "https://github.com/AFNetworking/AFNetworking",
              "authors": {"Mattt Thompson": "m@mattt.me"},
              "source": {"git": "https://github.com/AFNetworking/AFNetworking.git", "tag": "4.0.1"},
              "platforms": {"ios": "9.0", "osx": "10.10"},
              "swift_versions": [4.10, 5.0],
              "subspecs": [{"name": "Serialization", "source_files": "AFNetworking/AFURL{Request,Response}Serialization.{h,m}"}]
            }
            """;

    static final String HTTP_POD = """
            {"name": "Zipped", "version": "1.2.0", "summary": "Ünïcødé ✓",
             "source": {"http": "https://example.com/dl/Zipped-1.2.0.zip", "type": "zip", "flatten": true,
                        "sha256": "abc", "headers": ["Accept: application/octet-stream"]},
             "deprecated_in_favor_of": "Unzipped"}
            """;

    private static Podspec spec(String json) {
        return Podspec.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsNameVersionAndSource() {
        Podspec af = spec(AFNETWORKING);
        assertThat(af.name()).contains("AFNetworking");
        assertThat(af.version()).contains("4.0.1");
        assertThat(af.source()).isPresent();
        assertThat(af.httpSource()).isEmpty();
        assertThat(af.sourceType()).isEmpty();
        assertThat(af.sourceHeaders()).isEmpty();
        assertThat(af.isDeprecated()).isFalse();

        Podspec zipped = spec(HTTP_POD);
        assertThat(zipped.httpSource()).contains("https://example.com/dl/Zipped-1.2.0.zip");
        assertThat(zipped.sourceType()).contains("zip");
        assertThat(zipped.sourceHeaders()).containsExactly("Accept: application/octet-stream");
        assertThat(zipped.isDeprecated()).isTrue();
    }

    @Test
    void deprecationFlags() {
        assertThat(spec("{\"name\":\"a\",\"deprecated\":true}").isDeprecated()).isTrue();
        assertThat(spec("{\"name\":\"a\",\"deprecated\":false}").isDeprecated()).isFalse();
        assertThat(spec("{\"name\":\"a\",\"deprecated\":\"yes\"}").isDeprecated()).isFalse();
        assertThat(spec("{\"name\":\"a\",\"deprecated_in_favor_of\":\" \"}").isDeprecated()).isFalse();
    }

    @Test
    void headersMayBeASingleString() {
        assertThat(spec("{\"source\":{\"http\":\"https://x/a.zip\",\"headers\":\"Authorization: token 1\"}}").sourceHeaders())
                .containsExactly("Authorization: token 1");
        assertThat(spec("{\"source\":{\"http\":\"https://x/a.zip\",\"headers\":[1, \"A: b\"]}}").sourceHeaders()).containsExactly("A: b");
    }

    @Test
    void nonStringFieldsAreNotNamesOrVersions() {
        Podspec numeric = spec("{\"name\": 7, \"version\": 1.0, \"source\": \"git\"}");
        assertThat(numeric.name()).isEmpty();
        assertThat(numeric.version()).isEmpty();
        assertThat(numeric.source()).isEmpty();
        assertThat(numeric.httpSource()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "\"text\"", "{", "{\"name\": }", "Pod::Spec.new do |s|\nend", "null", "42"})
    void anythingButAJsonObjectIsRefused(String body) {
        assertThat(Podspec.tryParse(body.getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThatThrownBy(() -> Podspec.parse(body.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CocoaPodsError.class)
                .hasMessageContaining("pod ipc spec");
    }

    @Test
    void nullIsRefused() {
        assertThat(Podspec.tryParse(null)).isEmpty();
    }

    @Test
    void rewritingTheHttpSourceKeepsEverythingElseAndDropsTheHeaders() {
        Podspec zipped = spec(HTTP_POD);
        Podspec rewritten = zipped.withHttpSource("http://granary/repository/g/sources/Zipped/1.2.0/Zipped-1.2.0.zip");
        JsonNode source = rewritten.node().get("source");
        assertThat(source.get("http").asString()).isEqualTo("http://granary/repository/g/sources/Zipped/1.2.0/Zipped-1.2.0.zip");
        assertThat(source.get("type").asString()).isEqualTo("zip");
        assertThat(source.get("flatten").asBoolean()).isTrue();
        assertThat(source.get("sha256").asString()).isEqualTo("abc");
        assertThat(source.has("headers")).isFalse();
        // the original is untouched
        assertThat(zipped.httpSource()).contains("https://example.com/dl/Zipped-1.2.0.zip");
        assertThat(zipped.sourceHeaders()).hasSize(1);

        String json = new String(rewritten.toBytes(), StandardCharsets.UTF_8);
        assertThat(json).contains("Ünïcødé ✓").contains("\"deprecated_in_favor_of\"");
        assertThat(json.indexOf("\"name\"")).isLessThan(json.indexOf("\"version\"")).isLessThan(json.indexOf("\"source\""));
    }

    @Test
    void aRewrittenPodspecIsLaidOutLikePodIpcSpec() {
        String json = "{\"name\":\"N\",\"version\":\"1.0\",\"source\":{\"http\":\"https://x/a.zip\"},"
                + "\"platforms\":{\"ios\":\"12.0\"},\"frameworks\":[\"UIKit\",\"Foundation\"],\"resources\":[],\"pod_target_xcconfig\":{}}";
        String out = new String(spec(json).withHttpSource("http://local/a.zip").toBytes(), StandardCharsets.UTF_8);
        assertThat(out).isEqualTo("""
                {
                  "name": "N",
                  "version": "1.0",
                  "source": {
                    "http": "http://local/a.zip"
                  },
                  "platforms": {
                    "ios": "12.0"
                  },
                  "frameworks": [
                    "UIKit",
                    "Foundation"
                  ],
                  "resources": [],
                  "pod_target_xcconfig": {}
                }""");
    }

    @Test
    void numbersKeepTheirExactSpellingThroughARewrite() {
        String json = "{\"name\":\"N\",\"version\":\"1.0\",\"swift_versions\":[4.10, 5.0, 5],\"big\":123456789012345678901234567890,"
                + "\"tiny\":0.000000000000000000001,\"source\":{\"http\":\"https://x/a.zip\"}}";
        String out = new String(spec(json).withHttpSource("http://local/a.zip").toBytes(), StandardCharsets.UTF_8);
        assertThat(out).contains("4.10").contains("5.0").contains("123456789012345678901234567890");
        assertThat(spec(out).node().get("swift_versions").get(0).decimalValue()).isEqualByComparingTo("4.10");
        assertThat(spec(out).node().get("swift_versions").get(0).toString()).isEqualTo("4.10");
        assertThat(spec(out).node().get("tiny").decimalValue()).isEqualByComparingTo("1E-21");
    }

    @Test
    void anUploadedArchiveReplacesTheWholeSource() {
        Podspec af = spec(AFNETWORKING.replace("\"tag\": \"4.0.1\"", "\"tag\": \"4.0.1\", \"flatten\": false"));
        Podspec uploaded = af.withUploadedSource("http://g/sources/AFNetworking/4.0.1/af.zip", SourceArchive.Type.ZIP, "deadbeef", true);
        JsonNode source = uploaded.node().get("source");
        assertThat(source.get("http").asString()).isEqualTo("http://g/sources/AFNetworking/4.0.1/af.zip");
        assertThat(source.get("sha256").asString()).isEqualTo("deadbeef");
        assertThat(source.has("git")).isFalse();
        assertThat(source.has("tag")).isFalse();
        assertThat(source.has("type")).isFalse();
        assertThat(source.get("flatten").asBoolean()).isFalse();

        Podspec typed = af.withUploadedSource("http://g/sources/AFNetworking/4.0.1/blob", SourceArchive.Type.TGZ, "00", false);
        assertThat(typed.node().get("source").get("type").asString()).isEqualTo("tgz");
        assertThat(spec("{\"name\":\"x\"}").withUploadedSource("http://g/a.zip", SourceArchive.Type.ZIP, "1", true)
                .node().get("source").has("flatten")).isFalse();
    }
}
