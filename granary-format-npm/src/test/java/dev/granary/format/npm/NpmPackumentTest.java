package dev.granary.format.npm;

import static dev.granary.format.npm.NpmTestSupport.json;
import static dev.granary.format.npm.NpmTestSupport.str;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class NpmPackumentTest {

    private static final String BASE = "http://granary.test/repository/npm-all";

    @Test
    void rewritesEveryTarballUrlToThisRepository() {
        ObjectNode doc = json("""
                {"name":"demo","versions":{
                  "1.0.0":{"dist":{"tarball":"https://registry.npmjs.org/demo/-/demo-1.0.0.tgz","shasum":"x"}},
                  "2.0.0":{"dist":{"tarball":"http://old.host:9999/some/odd/path/demo-2.0.0.tgz?token=1"}},
                  "3.0.0":{}
                }}""");
        NpmPackument.rewriteTarballs(doc, BASE, "demo");
        assertThat(str(doc, "versions", "1.0.0", "dist", "tarball")).isEqualTo(BASE + "/demo/-/demo-1.0.0.tgz");
        assertThat(str(doc, "versions", "1.0.0", "dist", "shasum")).isEqualTo("x");
        assertThat(str(doc, "versions", "2.0.0", "dist", "tarball")).isEqualTo(BASE + "/demo/-/demo-2.0.0.tgz");
        assertThat(str(doc, "versions", "3.0.0", "dist", "tarball")).isEqualTo(BASE + "/demo/-/demo-3.0.0.tgz");
    }

    @Test
    void scopedTarballUrlsKeepTheScopeInThePathButNotInTheFile() {
        ObjectNode doc = json("""
                {"name":"@babel/core","versions":{"7.0.0":{"dist":{"tarball":"https://registry.npmjs.org/@babel/core/-/core-7.0.0.tgz"}}}}""");
        NpmPackument.rewriteTarballs(doc, BASE + "/", "@babel/core");
        assertThat(str(doc, "versions", "7.0.0", "dist", "tarball")).isEqualTo(BASE + "/@babel/core/-/core-7.0.0.tgz");
    }

    @Test
    void fileNameOfHandlesOddUrls() {
        assertThat(NpmPackument.fileNameOf("https://r/x/-/x-1.0.0.tgz")).isEqualTo("x-1.0.0.tgz");
        assertThat(NpmPackument.fileNameOf("https://r/x/-/x-1.0.0.tgz?sig=abc#frag")).isEqualTo("x-1.0.0.tgz");
        assertThat(NpmPackument.fileNameOf("https://r/x/-/x-1.0.0.tgz/")).isNull();
        assertThat(NpmPackument.fileNameOf("x-1.0.0.tgz")).isEqualTo("x-1.0.0.tgz");
        assertThat(NpmPackument.fileNameOf("https://r/")).isNull();
        assertThat(NpmPackument.fileNameOf("")).isNull();
        assertThat(NpmPackument.fileNameOf(null)).isNull();
    }

    @Test
    void tarballFileNameFallsBackToTheNpmConvention() {
        assertThat(NpmPackument.tarballFileName("@babel/core", "7.0.0", json("{}"))).isEqualTo("core-7.0.0.tgz");
        assertThat(NpmPackument.tarballFileName("demo", "1.0.0", null)).isEqualTo("demo-1.0.0.tgz");
        assertThat(NpmPackument.tarballFileName("demo", "1.0.0",
                json("{\"dist\":{\"tarball\":\"http://h/demo/-/custom.tgz\"}}"))).isEqualTo("custom.tgz");
        assertThat(NpmPackument.defaultFileName("@acme/ui", "2.1.0")).isEqualTo("ui-2.1.0.tgz");
        assertThat(NpmPackument.tarballUrl(BASE + "/", "@acme/ui", "ui-2.1.0.tgz")).isEqualTo(BASE + "/@acme/ui/-/ui-2.1.0.tgz");
    }

    @Test
    void mergeUnionsVersionsWithTheFirstMemberWinning() {
        ObjectNode a = json("""
                {"name":"demo","description":"from A","readme":"readme A","dist-tags":{"latest":"1.0.0"},
                 "versions":{"1.0.0":{"description":"A's 1.0.0"}},"time":{"created":"2024-01-01T00:00:00.000Z","1.0.0":"2024-01-01T00:00:00.000Z","modified":"2024-01-01T00:00:00.000Z"}}""");
        ObjectNode b = json("""
                {"name":"demo","description":"from B","readme":"readme B","dist-tags":{"latest":"2.0.0","beta":"2.1.0-beta.1"},
                 "versions":{"1.0.0":{"description":"B's 1.0.0"},"2.0.0":{"description":"B's 2.0.0"},"2.1.0-beta.1":{}},
                 "time":{"created":"2023-01-01T00:00:00.000Z","2.0.0":"2024-06-01T00:00:00.000Z","modified":"2024-06-01T00:00:00.000Z"}}""");
        ObjectNode merged = NpmPackument.merge(List.of(a, b));
        assertThat(NpmPackument.versionNames(merged)).containsExactly("1.0.0", "2.0.0", "2.1.0-beta.1");
        assertThat(str(merged, "versions", "1.0.0", "description")).isEqualTo("A's 1.0.0");
        assertThat(str(merged, "versions", "2.0.0", "description")).isEqualTo("B's 2.0.0");
        assertThat(str(merged, "name")).isEqualTo("demo");
        assertThat(str(merged, "_id")).isEqualTo("demo");
        assertThat(str(merged, "description")).isEqualTo("from A");
        assertThat(str(merged, "readme")).isEqualTo("readme A");
        assertThat(str(merged, "dist-tags", "latest")).isEqualTo("2.0.0");
        assertThat(str(merged, "dist-tags", "beta")).isEqualTo("2.1.0-beta.1");
        assertThat(str(merged, "time", "created")).isEqualTo("2024-01-01T00:00:00.000Z");
        assertThat(str(merged, "time", "1.0.0")).isEqualTo("2024-01-01T00:00:00.000Z");
        assertThat(str(merged, "time", "2.0.0")).isEqualTo("2024-06-01T00:00:00.000Z");
        assertThat(str(merged, "time", "modified")).isEqualTo("2024-06-01T00:00:00.000Z");
        // members are untouched
        assertThat(str(a, "dist-tags", "latest")).isEqualTo("1.0.0");
    }

    @Test
    void mergeLatestIsTheHighestLatestWhateverTheMemberOrder() {
        ObjectNode hosted = json("{\"name\":\"demo\",\"dist-tags\":{\"latest\":\"1.0.0\"},\"versions\":{\"1.0.0\":{}}}");
        ObjectNode proxy = json("{\"name\":\"demo\",\"dist-tags\":{\"latest\":\"1.2.0\"},\"versions\":{\"1.1.0\":{},\"1.2.0\":{}}}");
        assertThat(str(NpmPackument.merge(List.of(hosted, proxy)), "dist-tags", "latest")).isEqualTo("1.2.0");
        assertThat(str(NpmPackument.merge(List.of(proxy, hosted)), "dist-tags", "latest")).isEqualTo("1.2.0");
    }

    @Test
    void mergeOtherTagsFirstMemberWins() {
        ObjectNode a = json("{\"name\":\"demo\",\"dist-tags\":{\"next\":\"1.0.0\"},\"versions\":{\"1.0.0\":{}}}");
        ObjectNode b = json("{\"name\":\"demo\",\"dist-tags\":{\"next\":\"2.0.0\"},\"versions\":{\"2.0.0\":{}}}");
        assertThat(str(NpmPackument.merge(List.of(a, b)), "dist-tags", "next")).isEqualTo("1.0.0");
        assertThat(str(NpmPackument.merge(List.of(b, a)), "dist-tags", "next")).isEqualTo("2.0.0");
    }

    @Test
    void mergeIgnoresALatestThatPointsNowhereAndComputesOneWhenNobodyHasIt() {
        ObjectNode broken = json("{\"name\":\"demo\",\"dist-tags\":{\"latest\":\"9.9.9\"},\"versions\":{\"1.0.0\":{}}}");
        ObjectNode fine = json("{\"name\":\"demo\",\"dist-tags\":{\"latest\":\"1.1.0\"},\"versions\":{\"1.1.0\":{}}}");
        assertThat(str(NpmPackument.merge(List.of(broken, fine)), "dist-tags", "latest")).isEqualTo("1.1.0");

        ObjectNode untagged = json("{\"name\":\"demo\",\"versions\":{\"1.0.0\":{},\"2.0.0-beta.1\":{},\"1.5.0\":{}}}");
        assertThat(str(NpmPackument.merge(List.of(untagged)), "dist-tags", "latest")).isEqualTo("1.5.0");
    }

    @Test
    void putVersionRecordsTimestamps() {
        ObjectNode doc = NpmPackument.newDocument("demo");
        Instant t1 = Instant.parse("2026-01-01T10:00:00.123Z");
        NpmPackument.putVersion(doc, "1.0.0", json("{\"version\":\"1.0.0\"}"), t1);
        assertThat(str(doc, "time", "created")).isEqualTo("2026-01-01T10:00:00.123Z");
        assertThat(str(doc, "time", "modified")).isEqualTo("2026-01-01T10:00:00.123Z");
        assertThat(str(doc, "time", "1.0.0")).isEqualTo("2026-01-01T10:00:00.123Z");

        Instant t2 = Instant.parse("2026-02-01T10:00:00.000Z");
        NpmPackument.putVersion(doc, "1.1.0", json("{\"version\":\"1.1.0\"}"), t2);
        assertThat(str(doc, "time", "created")).isEqualTo("2026-01-01T10:00:00.123Z");
        assertThat(str(doc, "time", "modified")).isEqualTo("2026-02-01T10:00:00.000Z");
        assertThat(NpmPackument.versionNames(doc)).containsExactly("1.0.0", "1.1.0");
    }

    @Test
    void removeVersionDropsItsTagsAndRetargetsLatest() {
        ObjectNode doc = json("""
                {"name":"demo","dist-tags":{"latest":"2.0.0","next":"2.0.0","old":"1.0.0"},
                 "versions":{"1.0.0":{},"2.0.0":{},"3.0.0-rc.1":{}},"time":{"1.0.0":"a","2.0.0":"b","3.0.0-rc.1":"c"}}""");
        assertThat(NpmPackument.removeVersion(doc, "2.0.0")).isTrue();
        assertThat(NpmPackument.versionNames(doc)).containsExactly("1.0.0", "3.0.0-rc.1");
        assertThat(doc.path("dist-tags").has("next")).isFalse();
        assertThat(str(doc, "dist-tags", "old")).isEqualTo("1.0.0");
        assertThat(str(doc, "dist-tags", "latest")).isEqualTo("1.0.0"); // highest release, not the rc
        assertThat(doc.path("time").has("2.0.0")).isFalse();
        assertThat(NpmPackument.removeVersion(doc, "2.0.0")).isFalse();
    }

    @Test
    void versionLookupResolvesNumbersAndTags() {
        ObjectNode doc = json("{\"dist-tags\":{\"latest\":\"1.1.0\",\"broken\":\"9.9.9\"},\"versions\":{\"1.0.0\":{\"v\":\"a\"},\"1.1.0\":{\"v\":\"b\"}}}");
        assertThat(NpmPackument.version(doc, "1.0.0").map(n -> str(n, "v"))).contains("a");
        assertThat(NpmPackument.version(doc, "latest").map(n -> str(n, "v"))).contains("b");
        assertThat(NpmPackument.version(doc, "broken")).isEmpty();
        assertThat(NpmPackument.version(doc, "2.0.0")).isEmpty();
        assertThat(NpmPackument.latest(doc)).contains("1.1.0");
        assertThat(NpmPackument.latest(json("{}"))).isEmpty();
    }

    @Test
    void bumpRevisionCountsUp() {
        ObjectNode doc = NpmPackument.newDocument("demo");
        assertThat(NpmPackument.bumpRevision(doc)).startsWith("1-");
        assertThat(NpmPackument.bumpRevision(doc)).startsWith("2-");
        doc.put("_rev", "41-deadbeef");
        assertThat(NpmPackument.bumpRevision(doc)).startsWith("42-");
        doc.put("_rev", "garbage");
        assertThat(NpmPackument.bumpRevision(doc)).startsWith("1-");
    }

    @Test
    void copyDescriptiveFieldsSkipsStructuralMembers() {
        ObjectNode from = json("""
                {"_id":"x","_rev":"1-a","name":"x","versions":{"1.0.0":{}},"dist-tags":{"latest":"1.0.0"},"time":{},
                 "_attachments":{},"access":"public","description":"d","readme":"r","author":{"name":"me"},"keywords":["k"]}""");
        ObjectNode to = NpmPackument.newDocument("y");
        NpmPackument.copyDescriptiveFields(from, to);
        assertThat(str(to, "name")).isEqualTo("y");
        assertThat(str(to, "description")).isEqualTo("d");
        assertThat(str(to, "readme")).isEqualTo("r");
        assertThat(str(to, "author", "name")).isEqualTo("me");
        assertThat(to.path("keywords").isArray()).isTrue();
        assertThat(to.has("_attachments")).isFalse();
        assertThat(to.has("access")).isFalse();
        assertThat(to.has("_rev")).isFalse();
        assertThat(NpmPackument.versionNames(to)).isEmpty();
    }

    @Test
    void stringHelperOnlyReturnsStrings() {
        ObjectNode node = json("{\"s\":\"v\",\"n\":1,\"o\":{}}");
        assertThat(NpmPackument.string(node, "s")).isEqualTo("v");
        assertThat(NpmPackument.string(node, "n")).isNull();
        assertThat(NpmPackument.string(node, "o")).isNull();
        assertThat(NpmPackument.string(node, "missing")).isNull();
        assertThat(NpmPackument.string(null, "s")).isNull();
    }
}
