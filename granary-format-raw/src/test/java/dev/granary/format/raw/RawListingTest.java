package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class RawListingTest {

    private final FakeStorage storage = new FakeStorage();
    private final Repository repo = RawTestSupport.hosted("raw-hosted", "raw");

    private List<Asset> assets(String... paths) {
        for (String path : paths) {
            storage.put(repo, path, "content of " + path);
        }
        return storage.mock.list(repo, "");
    }

    @Test
    void childrenOfTheRootAreDirectoriesFirstThenFiles() {
        List<Asset> all = assets("zeta.txt", "alpha/one.txt", "alpha/two/three.txt", "beta/x", "README");
        List<RawListing.Entry> entries = RawListing.children(all, "");
        assertThat(entries).extracting(RawListing.Entry::name).containsExactly("alpha", "beta", "README", "zeta.txt");
        assertThat(entries.get(0).directory()).isTrue();
        assertThat(entries.get(0).href()).isEqualTo("alpha/");
        assertThat(entries.get(3).directory()).isFalse();
        assertThat(entries.get(3).size()).isEqualTo("content of zeta.txt".length());
        assertThat(entries.get(3).href()).isEqualTo("zeta.txt");
    }

    @Test
    void childrenOfASubdirectoryUseThePrefixAndDirectoriesCarryTheNewestTime() {
        List<Asset> all = assets("alpha/one.txt", "alpha/two/old.txt", "alpha/two/new.txt", "alphabet/x");
        List<RawListing.Entry> entries = RawListing.children(storage.mock.list(repo, "alpha/"), "alpha/");
        assertThat(entries).extracting(RawListing.Entry::name).containsExactly("two", "one.txt");
        Instant newest = storage.asset(repo, "alpha/two/new.txt").getUpdatedAt();
        assertThat(entries.get(0).lastModified()).isEqualTo(newest);
        assertThat(RawListing.children(all, "alpha/")).extracting(RawListing.Entry::name).containsExactly("two", "one.txt");
    }

    @Test
    void mergeKeepsTheFirstSourcesFileAndUnionsDirectories() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-02-01T00:00:00Z");
        List<RawListing.Entry> hosted = List.of(new RawListing.Entry("shared", true, 0, t1), new RawListing.Entry("a.txt", false, 1, t1));
        List<RawListing.Entry> proxy = List.of(new RawListing.Entry("shared", true, 0, t2), new RawListing.Entry("a.txt", false, 99, t2),
                new RawListing.Entry("b.txt", false, 2, t2));
        List<RawListing.Entry> merged = RawListing.merge(List.of(hosted, proxy));
        assertThat(merged).extracting(RawListing.Entry::name).containsExactly("shared", "a.txt", "b.txt");
        assertThat(merged.get(0).lastModified()).isEqualTo(t2);
        assertThat(merged.get(1).size()).isEqualTo(1);
    }

    @Test
    void acceptHeaderDecidesBetweenHtmlAndJson() {
        assertThat(RawListing.wantsJson(null)).isFalse();
        assertThat(RawListing.wantsJson("*/*")).isFalse();
        assertThat(RawListing.wantsJson("text/html,application/xhtml+xml,*/*;q=0.8")).isFalse();
        assertThat(RawListing.wantsJson("application/json")).isTrue();
        assertThat(RawListing.wantsJson("Application/JSON; charset=utf-8")).isTrue();
        assertThat(RawListing.wantsJson("application/json, text/html")).isTrue();
        assertThat(RawListing.wantsJson("text/html, application/json")).isFalse();
    }

    @Test
    void htmlEscapesNamesAndEncodesLinks() {
        Instant t = Instant.parse("2026-03-04T05:06:07Z");
        List<RawListing.Entry> entries = List.of(
                new RawListing.Entry("<script>alert(1)</script>", false, 7, t),
                new RawListing.Entry("a b&c#d?e\"f'g", true, 0, t),
                new RawListing.Entry("日本語.zip", false, 12, null));
        String html = RawListing.html("raw-hosted", "dir/", entries);
        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<title>Index of /raw-hosted/dir/</title>");
        assertThat(html).contains("<a href=\"../\">../</a>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).doesNotContain("<script>alert");
        assertThat(html).contains("href=\"a%20b&amp;c%23d%3Fe%22f&#39;g/\"");
        assertThat(html).contains(">a b&amp;c#d?e&quot;f&#39;g/</a>");
        assertThat(html).contains("href=\"%E6%97%A5%E6%9C%AC%E8%AA%9E.zip\"");
        assertThat(html).contains("2026-03-04 05:06");
        assertThat(html).contains("<td class=\"size\">7</td>");
        assertThat(html).contains("<td class=\"size\">-</td>");
    }

    @Test
    void rootListingHasNoParentLink() {
        String html = RawListing.html("raw-hosted", "", List.of());
        assertThat(html).doesNotContain("../");
        assertThat(html).contains("Index of /raw-hosted/");
    }

    @Test
    void jsonListsEntriesWithTypeSizeAndTime() {
        Instant t = Instant.parse("2026-03-04T05:06:07Z");
        byte[] json = RawListing.json("raw-hosted", "dir/", List.of(
                new RawListing.Entry("sub", true, 0, t), new RawListing.Entry("f.txt", false, 3, t)));
        JsonNode node = RawTestSupport.JSON.readTree(json);
        assertThat(node.path("repository").asString()).isEqualTo("raw-hosted");
        assertThat(node.path("path").asString()).isEqualTo("dir/");
        assertThat(node.path("entries")).hasSize(2);
        assertThat(node.path("entries").get(0).path("type").asString()).isEqualTo("directory");
        assertThat(node.path("entries").get(0).path("href").asString()).isEqualTo("sub/");
        assertThat(node.path("entries").get(0).has("size")).isFalse();
        assertThat(node.path("entries").get(1).path("type").asString()).isEqualTo("file");
        assertThat(node.path("entries").get(1).path("size").asLong()).isEqualTo(3);
        assertThat(node.path("entries").get(1).path("lastModified").asString()).isEqualTo("2026-03-04T05:06:07Z");
    }
}
