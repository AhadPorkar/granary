package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class PublishMetadataTest {

    private static final String CKSUM = "0123456789abcdef".repeat(4);
    private static final Instant WHEN = Instant.parse("2026-10-10T06:15:54.123456Z");

    private static ObjectNode dep(ObjectNode meta, String name, String req) {
        ObjectNode d = ((ArrayNode) meta.get("deps")).addObject();
        d.put("optional", false);
        d.put("default_features", true);
        d.put("name", name);
        d.putArray("features");
        d.put("version_req", req);
        d.putNull("target");
        d.put("kind", "normal");
        d.putNull("registry");
        d.putNull("explicit_name_in_toml");
        return d;
    }

    @Test
    void indexLineHasTheFieldsOfCratesIoInItsOrder() {
        ObjectNode line = PublishMetadata.of(CargoTestSupport.metadata("acme-hello", "0.1.0")).toIndexLine(CKSUM, WHEN);
        assertThat(CargoJson.write(line)).isEqualTo("{\"name\":\"acme-hello\",\"vers\":\"0.1.0\",\"deps\":[{\"name\":\"itoa\","
                + "\"req\":\"^1\",\"features\":[],\"optional\":false,\"default_features\":true,\"target\":null,"
                + "\"kind\":\"normal\",\"registry\":\"https://github.com/rust-lang/crates.io-index\"}],\"cksum\":\"" + CKSUM
                + "\",\"features\":{},\"yanked\":false,\"pubtime\":\"2026-10-10T06:15:54Z\"}");
    }

    @Test
    void renamedDependencyIsListedUnderItsAlias() {
        ObjectNode meta = CargoTestSupport.metadata("acme", "1.0.0");
        ObjectNode d = dep(meta, "serde_json", "^1.0");
        d.put("explicit_name_in_toml", "json");
        d.put("optional", true);
        d.put("default_features", false);
        d.put("target", "cfg(unix)");
        d.put("kind", "dev");
        ((ArrayNode) d.get("features")).add("std");
        ObjectNode line = PublishMetadata.of(meta).toIndexLine(CKSUM, WHEN);
        ObjectNode json = (ObjectNode) line.get("deps").get(1);
        assertThat(CargoJson.write(json)).isEqualTo("{\"name\":\"json\",\"req\":\"^1.0\",\"features\":[\"std\"],"
                + "\"optional\":true,\"default_features\":false,\"target\":\"cfg(unix)\",\"kind\":\"dev\","
                + "\"package\":\"serde_json\"}");
    }

    @Test
    void dependenciesAreSortedByName() {
        ObjectNode meta = CargoTestSupport.metadata("acme", "1.0.0");
        dep(meta, "zeta", "^1");
        dep(meta, "alpha", "^2");
        ObjectNode line = PublishMetadata.of(meta).toIndexLine(CKSUM, WHEN);
        assertThat(line.get("deps").valueStream().map(d -> d.get("name").stringValue()))
                .containsExactly("alpha", "itoa", "zeta");
    }

    @Test
    void missingOptionalDependencyFieldsGetCargoDefaults() {
        ObjectNode meta = CargoTestSupport.metadata("acme", "1.0.0");
        meta.putArray("deps").addObject().put("name", "rand").put("version_req", "^0.8");
        ObjectNode d = (ObjectNode) PublishMetadata.of(meta).toIndexLine(CKSUM, WHEN).get("deps").get(0);
        assertThat(CargoJson.write(d)).isEqualTo("{\"name\":\"rand\",\"req\":\"^0.8\",\"features\":[],\"optional\":false,"
                + "\"default_features\":true,\"target\":null,\"kind\":\"normal\"}");
    }

    @Test
    void featuresWithNewSyntaxMoveToFeatures2() {
        ObjectNode meta = CargoTestSupport.metadata("acme", "1.0.0");
        ObjectNode features = meta.putObject("features");
        features.putArray("std").add("itoa/std");
        features.putArray("default").add("std");
        features.putArray("fast").add("dep:itoa");
        features.putArray("weak").add("serde?/derive");
        ObjectNode line = PublishMetadata.of(meta).toIndexLine(CKSUM, WHEN);
        assertThat(CargoJson.write(line.get("features"))).isEqualTo("{\"default\":[\"std\"],\"std\":[\"itoa/std\"]}");
        assertThat(CargoJson.write(line.get("features2"))).isEqualTo("{\"fast\":[\"dep:itoa\"],\"weak\":[\"serde?/derive\"]}");
        assertThat(line.get("v").intValue()).isEqualTo(2);
        // v comes after yanked, as on crates.io
        assertThat(line.propertyNames()).containsSubsequence("features", "features2", "yanked", "v", "pubtime");
    }

    @Test
    void linksAndRustVersionAreCarried() {
        ObjectNode meta = CargoTestSupport.metadata("acme-sys", "1.0.0");
        meta.put("links", "acme");
        meta.put("rust_version", "1.70");
        ObjectNode line = PublishMetadata.of(meta).toIndexLine(CKSUM, WHEN);
        assertThat(line.get("links").stringValue()).isEqualTo("acme");
        assertThat(line.get("rust_version").stringValue()).isEqualTo("1.70");
        assertThat(line.has("features2")).isFalse();
        assertThat(line.has("v")).isFalse();
    }

    @Test
    void artifactDependencyFieldsPassThrough() {
        ObjectNode meta = CargoTestSupport.metadata("acme", "1.0.0");
        ObjectNode d = dep(meta, "tool", "^1");
        d.putArray("artifact").add("bin");
        d.put("bindep_target", "x86_64-unknown-linux-gnu");
        d.put("lib", false);
        ObjectNode out = (ObjectNode) PublishMetadata.of(meta).toIndexLine(CKSUM, WHEN).get("deps").get(1);
        assertThat(out.get("artifact").get(0).stringValue()).isEqualTo("bin");
        assertThat(out.get("bindep_target").stringValue()).isEqualTo("x86_64-unknown-linux-gnu");
        assertThat(out.has("lib")).isFalse();
    }

    @Test
    void accessors() {
        PublishMetadata m = PublishMetadata.of(CargoTestSupport.metadata("acme", "1.2.3-rc.1+b"));
        assertThat(m.name()).isEqualTo("acme");
        assertThat(m.vers()).isEqualTo("1.2.3-rc.1+b");
        assertThat(m.description()).isEqualTo("Hello from acme");
    }

    @Test
    void invalidMetadataIsRefusedWithAReason() {
        assertRefused(meta -> meta.remove("name"), "missing crate name");
        assertRefused(meta -> meta.put("name", 5), "`name` must be a string");
        assertRefused(meta -> meta.put("name", "9lives"), "must start with an ASCII letter");
        assertRefused(meta -> meta.put("name", "../etc"), "must start with an ASCII letter");
        assertRefused(meta -> meta.remove("vers"), "missing version");
        assertRefused(meta -> meta.put("vers", "1.0"), "not a valid semver version");
        assertRefused(meta -> meta.put("vers", "1.0.0/../x"), "not a valid semver version");
        assertRefused(meta -> meta.put("deps", "itoa"), "`deps` must be an array");
        assertRefused(meta -> ((ArrayNode) meta.get("deps")).add(1), "must be an object");
        assertRefused(meta -> ((ObjectNode) meta.get("deps").get(0)).remove("version_req"), "missing version requirement");
        assertRefused(meta -> ((ObjectNode) meta.get("deps").get(0)).put("version_req", " "), "empty version requirement");
        assertRefused(meta -> ((ObjectNode) meta.get("deps").get(0)).put("name", "a/b"), "not a valid crate name");
        assertRefused(meta -> ((ObjectNode) meta.get("deps").get(0)).put("kind", "weird"), "unknown kind");
        assertRefused(meta -> ((ObjectNode) meta.get("deps").get(0)).put("explicit_name_in_toml", "x y"),
                "rename `x y` is not a valid");
        assertRefused(meta -> ((ObjectNode) meta.get("deps").get(0)).put("optional", "yes"), "must be a boolean");
        assertRefused(meta -> ((ObjectNode) meta.get("deps").get(0)).put("features", "std"), "array of strings");
        assertRefused(meta -> ((ObjectNode) meta.get("deps").get(0)).putArray("features").add(1), "array of strings");
        assertRefused(meta -> meta.put("features", "x"), "`features` must be an object");
        assertRefused(meta -> meta.putObject("features").put("std", "x"), "feature `std` must be an array");
        assertRefused(meta -> meta.putObject("features").putArray("std").add(true), "feature `std` must be an array");
        assertRefused(meta -> meta.put("links", 1), "`links` must be a string or null");
        assertRefused(meta -> meta.put("rust_version", 1.7), "`rust_version` must be a string or null");
    }

    @Test
    void absentDepsAndFeaturesAreFine() {
        ObjectNode meta = CargoJson.object();
        meta.put("name", "bare");
        meta.put("vers", "0.0.1");
        ObjectNode line = PublishMetadata.of(meta).toIndexLine(CKSUM, WHEN);
        assertThat(line.get("deps").isEmpty()).isTrue();
        assertThat(line.get("features").isEmpty()).isTrue();
    }

    private static void assertRefused(java.util.function.Consumer<ObjectNode> change, String message) {
        ObjectNode meta = CargoTestSupport.metadata("acme", "1.0.0");
        change.accept(meta);
        assertThatThrownBy(() -> PublishMetadata.of(meta))
                .isInstanceOf(CargoError.class)
                .hasMessageContaining(message);
    }
}
