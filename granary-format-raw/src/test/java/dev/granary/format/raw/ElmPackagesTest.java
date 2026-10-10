package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ElmPackagesTest {

    @Test
    void pathsAreRecognised() {
        assertThat(ElmPackages.release("packages/elm/core/1.0.5/elm.json")).contains(new ElmPackages.Release("elm", "core", "1.0.5"));
        assertThat(ElmPackages.release("packages/elm/core/1.0.5/")).isEmpty();
        assertThat(ElmPackages.release("packages/elm/core/releases.json")).isEmpty();
        assertThat(ElmPackages.isEndpoint("packages/elm/core/1.0.5/endpoint.json")).isTrue();
        assertThat(ElmPackages.isEndpoint("packages/elm/core/1.0.5/elm.json")).isFalse();
        assertThat(ElmPackages.zipRoute("github/elm/core/zip/1.0.5")).contains(new ElmPackages.Release("elm", "core", "1.0.5"));
        assertThat(ElmPackages.zipRoute("github/elm/core/zip/1.0.5/extra")).isEmpty();
        // the parts become an upstream URL: nothing that could change its meaning or break URI parsing
        for (String odd : java.util.List.of("github/a b/core/zip/1.0.5", "github/elm/core/zip/1.0.5?x=1", "github/elm/core/zip/1.0.5#x",
                "github/elm/core/zip/latest", "github/-elm/core/zip/1.0.5", "github/elm/co%re/zip/1.0.5", "github/elm/@core/zip/1.0.5")) {
            assertThat(ElmPackages.zipRoute(odd)).as(odd).isEmpty();
        }
        assertThat(ElmPackages.zipRoute("github/rtfeldman/elm-css/zip/18.0.0")).isPresent();
        assertThat(ElmPackages.zipRoute("github/some_user/pkg.name/zip/1.0.0")).isPresent();
        assertThat(ElmPackages.sinceIndex("all-packages/since/42")).contains(42);
        assertThat(ElmPackages.sinceIndex("all-packages/since/x")).isEmpty();
        assertThat(ElmPackages.sinceIndex("all-packages/since/99999999999")).isEmpty();
        assertThat(ElmPackages.githubZipball(new ElmPackages.Release("elm", "core", "1.0.5")).toString())
                .isEqualTo("https://github.com/elm/core/zipball/1.0.5");
        ElmPackages.Release r = new ElmPackages.Release("elm", "core", "1.0.5");
        assertThat(r.id()).isEqualTo("elm/core@1.0.5");
        assertThat(r.directory()).isEqualTo("packages/elm/core/1.0.5/");
        assertThat(r.zipRoute()).isEqualTo("github/elm/core/zip/1.0.5");
        assertThat(ElmPackages.coordinates("github/elm/core/zip/1.0.5")).get().satisfies(c -> {
            assertThat(c.namespace()).isEqualTo("elm");
            assertThat(c.name()).isEqualTo("core");
            assertThat(c.version()).isEqualTo("1.0.5");
        });
        assertThat(ElmPackages.coordinates("search.json")).isEmpty();
    }

    @Test
    void endpointUrlsAreRewrittenToTheRepository() {
        String base = "http://granary.test/repository/elm-proxy";
        byte[] github = "{\"url\":\"https://github.com/elm/core/zipball/1.0.5/\",\"hash\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
        JsonNode rewritten = RawTestSupport.JSON.readTree(ElmPackages.rewriteEndpoint(github, base));
        assertThat(rewritten.path("url").asString()).isEqualTo(base + "/github/elm/core/zip/1.0.5");
        assertThat(rewritten.path("hash").asString()).isEqualTo("abc");

        byte[] relative = "{\"url\":\"packages/acme/w/1.0.0/w-1.0.0.zip\",\"hash\":\"h\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(RawTestSupport.JSON.readTree(ElmPackages.rewriteEndpoint(relative, base)).path("url").asString())
                .isEqualTo(base + "/packages/acme/w/1.0.0/w-1.0.0.zip");
        byte[] slash = "{\"url\":\"/x.zip\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(RawTestSupport.JSON.readTree(ElmPackages.rewriteEndpoint(slash, base)).path("url").asString()).isEqualTo(base + "/x.zip");

        byte[] other = "{\"url\":\"https://mirror.example.com/core.zip\",\"hash\":\"h\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(RawTestSupport.JSON.readTree(ElmPackages.rewriteEndpoint(other, base)).path("url").asString())
                .isEqualTo("https://mirror.example.com/core.zip");
        byte[] garbage = "{not json".getBytes(StandardCharsets.UTF_8);
        assertThat(ElmPackages.rewriteEndpoint(garbage, base)).isSameAs(garbage);
        byte[] array = "[1]".getBytes(StandardCharsets.UTF_8);
        assertThat(ElmPackages.rewriteEndpoint(array, base)).isSameAs(array);
        assertThat(new String(ElmPackages.endpoint("u", "h"), StandardCharsets.UTF_8)).isEqualTo("{\"url\":\"u\",\"hash\":\"h\"}");
    }

    @Test
    void allPackagesIsSortedAndMergeable() {
        byte[] json = ElmPackages.allPackages(Map.of("elm/core", List.of("1.0.10", "1.0.9", "1.0.2", "1.0.9"), "acme/w", List.of("2.0.0")));
        assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo("{\"acme/w\":[\"2.0.0\"],\"elm/core\":[\"1.0.2\",\"1.0.9\",\"1.0.10\"]}");
        Map<String, List<String>> parsed = ElmPackages.parseAllPackages(json);
        assertThat(parsed).containsEntry("elm/core", List.of("1.0.2", "1.0.9", "1.0.10"));
        assertThat(ElmPackages.parseAllPackages("[1,2]".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ElmPackages.parseAllPackages("{\"a/b\":\"x\",\"c/d\":[1,\"2.0.0\"]}".getBytes(StandardCharsets.UTF_8)))
                .containsEntry("a/b", List.of()).containsEntry("c/d", List.of("2.0.0"));
        assertThat(ElmPackages.parseAllPackages("nope".getBytes(StandardCharsets.UTF_8))).isEmpty();
        Map<String, List<String>> merged = ElmPackages.mergeAllPackages(List.of(parsed, Map.of("elm/core", List.of("1.0.11", "1.0.9"), "x/y", List.of("1.0.0"))));
        assertThat(merged).containsEntry("elm/core", List.of("1.0.2", "1.0.9", "1.0.10", "1.0.11")).containsKey("x/y").containsKey("acme/w");
    }

    @Test
    void sinceReturnsWhatTheClientHasNotSeen() {
        List<String> newestFirst = List.of("a/b@3.0.0", "a/b@2.0.0", "c/d@1.0.0");
        assertThat(new String(ElmPackages.since(newestFirst, 0), StandardCharsets.UTF_8)).isEqualTo("[\"a/b@3.0.0\",\"a/b@2.0.0\",\"c/d@1.0.0\"]");
        assertThat(new String(ElmPackages.since(newestFirst, 2), StandardCharsets.UTF_8)).isEqualTo("[\"a/b@3.0.0\"]");
        assertThat(new String(ElmPackages.since(newestFirst, 3), StandardCharsets.UTF_8)).isEqualTo("[]");
        assertThat(new String(ElmPackages.since(newestFirst, 10), StandardCharsets.UTF_8)).isEqualTo("[]");
        assertThat(ElmPackages.parseSince("[\"a/b@1.0.0\",5]".getBytes(StandardCharsets.UTF_8))).containsExactly("a/b@1.0.0");
        assertThat(ElmPackages.parseSince("{}".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ElmPackages.parseSince("x".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void versionsCompareNumerically() {
        assertThat(ElmPackages.compareVersions("1.0.10", "1.0.9")).isGreaterThan(0);
        assertThat(ElmPackages.compareVersions("2.0.0", "10.0.0")).isLessThan(0);
        assertThat(ElmPackages.compareVersions("1.0.0", "1.0")).isZero();
        assertThat(ElmPackages.compareVersions("x", "0.0.0")).isZero();
    }
}
