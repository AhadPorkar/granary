package dev.granary.format.nuget;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SearchResponseTest {

    private static final String BASE = "http://granary.test/repository/nuget-hosted";

    private final ObjectMapper json = JsonMapper.builder().build();
    private final SearchResponse search = new SearchResponse(json);

    private static PackageEntry entry(String id, String version, String description, String tags, long downloads) {
        Nuspec nuspec = NuspecReader.parse(TestNupkgs.nuspec(id, version).description(description).tags(tags)
                .authors("Acme, Bob").xml().getBytes(StandardCharsets.UTF_8));
        return new PackageEntry(nuspec, NuGetVersion.parse(version), Instant.EPOCH, true, downloads);
    }

    private static Map<String, List<PackageEntry>> packages() {
        Map<String, List<PackageEntry>> packages = new LinkedHashMap<>();
        packages.put("acme.lib", List.of(
                entry("Acme.Lib", "1.0.0", "Acme library", "acme core", 5),
                entry("Acme.Lib", "2.0.0-beta.1", "Acme library beta", "acme core", 1)));
        packages.put("acme.tools", List.of(entry("Acme.Tools", "0.9.0", "Tooling for acme", "tools", 2)));
        packages.put("other.thing", List.of(entry("Other.Thing", "3.0.0-rc.1", "Unrelated", "misc", 0)));
        return packages;
    }

    private static SearchResponse.Query query(String q, boolean prerelease) {
        return SearchResponse.Query.of(q, null, null, Boolean.toString(prerelease), null);
    }

    @Test
    void latestStableVersionRepresentsThePackageUnlessPrereleaseIsRequested() {
        ObjectNode stable = search.fromPackages(BASE, packages(), query("", false));
        assertThat(stable.path("totalHits").asInt()).isEqualTo(2); // Other.Thing has no stable version
        JsonNode acme = stable.path("data").get(0);
        assertThat(acme.path("id").stringValue()).isEqualTo("Acme.Lib");
        assertThat(acme.path("version").stringValue()).isEqualTo("1.0.0");
        assertThat(acme.path("versions").size()).isEqualTo(1);

        ObjectNode pre = search.fromPackages(BASE, packages(), query("", true));
        assertThat(pre.path("totalHits").asInt()).isEqualTo(3);
        JsonNode acmePre = pre.path("data").get(0);
        assertThat(acmePre.path("version").stringValue()).isEqualTo("2.0.0-beta.1");
        assertThat(acmePre.path("versions")).extracting(v -> v.path("version").stringValue()).containsExactly("1.0.0", "2.0.0-beta.1");
        assertThat(acmePre.path("versions").get(1).path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/acme.lib/2.0.0-beta.1.json");
        assertThat(acmePre.path("totalDownloads").asLong()).isEqualTo(6);
    }

    @Test
    void hitsCarryRegistrationUrlsAndManifestFacts() {
        JsonNode hit = search.fromPackages(BASE, packages(), query("acme.tools", false)).path("data").get(0);
        assertThat(hit.path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/acme.tools/index.json");
        assertThat(hit.path("registration").stringValue()).isEqualTo(BASE + "/v3/registration/acme.tools/index.json");
        assertThat(hit.path("@type").stringValue()).isEqualTo("Package");
        assertThat(hit.path("description").stringValue()).isEqualTo("Tooling for acme");
        assertThat(hit.path("authors")).extracting(JsonNode::stringValue).containsExactly("Acme", "Bob");
        assertThat(hit.path("tags")).extracting(JsonNode::stringValue).containsExactly("tools");
        assertThat(hit.path("packageTypes").get(0).path("name").stringValue()).isEqualTo("Dependency");
        assertThat(hit.path("verified").asBoolean()).isFalse();
    }

    @Test
    void matchesIdDescriptionAndTagsCaseInsensitively() {
        assertThat(ids(search.fromPackages(BASE, packages(), query("ACME", false)))).containsExactly("Acme.Lib", "Acme.Tools");
        assertThat(ids(search.fromPackages(BASE, packages(), query("tooling", false)))).containsExactly("Acme.Tools");
        assertThat(ids(search.fromPackages(BASE, packages(), query("core", false)))).containsExactly("Acme.Lib");
        assertThat(ids(search.fromPackages(BASE, packages(), query("acme tools", false)))).containsExactly("Acme.Tools");
        assertThat(ids(search.fromPackages(BASE, packages(), query("nomatch", false)))).isEmpty();
        assertThat(ids(search.fromPackages(BASE, packages(), query("misc", true)))).containsExactly("Other.Thing");
    }

    @Test
    void supportsThePackageIdAndIdPrefixes() {
        assertThat(ids(search.fromPackages(BASE, packages(), query("packageid:acme.lib", false)))).containsExactly("Acme.Lib");
        assertThat(ids(search.fromPackages(BASE, packages(), query("packageid:acme", false)))).isEmpty();
        assertThat(ids(search.fromPackages(BASE, packages(), query("id:acme", false)))).containsExactly("Acme.Lib", "Acme.Tools");
    }

    @Test
    void pagesWithSkipAndTakeWhileReportingTheTotal() {
        ObjectNode page = search.fromPackages(BASE, packages(), SearchResponse.Query.of("", "1", "1", "true", null));
        assertThat(page.path("totalHits").asInt()).isEqualTo(3);
        assertThat(ids(page)).containsExactly("Acme.Tools");
    }

    @Test
    void queryDefaultsAreTheClientDefaults() {
        SearchResponse.Query q = SearchResponse.Query.of(null, null, null, null, null);
        assertThat(q.q()).isEmpty();
        assertThat(q.skip()).isZero();
        assertThat(q.take()).isEqualTo(20);
        assertThat(q.prerelease()).isFalse();
        assertThat(q.semVerLevel()).isNull();
        assertThat(q.toQueryString()).isEqualTo("q=&skip=0&take=20&prerelease=false&semVerLevel=2.0.0");

        SearchResponse.Query odd = SearchResponse.Query.of("json net", "-5", "99999", "TRUE", "2.0.0");
        assertThat(odd.skip()).isZero();
        assertThat(odd.take()).isEqualTo(SearchResponse.Query.MAX_TAKE);
        assertThat(odd.prerelease()).isTrue();
        assertThat(odd.toQueryString()).isEqualTo("q=json+net&skip=0&take=1000&prerelease=true&semVerLevel=2.0.0");

        SearchResponse.Query widened = SearchResponse.Query.of("x", "10", "5", null, null).widened();
        assertThat(widened.skip()).isZero();
        assertThat(widened.take()).isEqualTo(15);
        assertThat(SearchResponse.Query.of("x", "abc", "xyz", null, null).take()).isEqualTo(20);
    }

    @Test
    void rewritesUpstreamResultsToTheRepository() {
        JsonNode upstream = json.readTree("""
                {"totalHits": 1234, "data": [
                  {"@id": "https://api.nuget.org/v3/registration5-gz-semver2/newtonsoft.json/index.json", "@type": "Package",
                   "registration": "https://api.nuget.org/v3/registration5-gz-semver2/newtonsoft.json/index.json",
                   "id": "Newtonsoft.Json", "version": "13.0.3", "description": "Json.NET", "totalDownloads": 5,
                   "versions": [
                     {"version": "13.0.1", "downloads": 2, "@id": "https://api.nuget.org/v3/registration5-gz-semver2/newtonsoft.json/13.0.1.json"},
                     {"version": "13.0.3", "downloads": 3, "@id": "https://api.nuget.org/v3/registration5-gz-semver2/newtonsoft.json/13.0.3.json"}
                   ]},
                  {"@type": "Package", "description": "no id, dropped"}
                ]}""");

        ObjectNode rewritten = search.rewrite(upstream, BASE);

        assertThat(rewritten.path("totalHits").asLong()).isEqualTo(1234);
        assertThat(rewritten.path("data").size()).isEqualTo(1);
        JsonNode hit = rewritten.path("data").get(0);
        assertThat(hit.path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/newtonsoft.json/index.json");
        assertThat(hit.path("registration").stringValue()).isEqualTo(BASE + "/v3/registration/newtonsoft.json/index.json");
        assertThat(hit.path("description").stringValue()).isEqualTo("Json.NET");
        assertThat(hit.path("versions").get(0).path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/newtonsoft.json/13.0.1.json");
        assertThat(json.writeValueAsString(rewritten)).doesNotContain("api.nuget.org");
    }

    @Test
    void mergesMembersByIdWithTheFirstWinning() {
        ObjectNode hosted = search.fromPackages(BASE, packages(), query("", true));
        JsonNode proxied = json.readTree("""
                {"totalHits": 2, "data": [
                  {"id": "acme.lib", "version": "9.9.9", "description": "upstream copy"},
                  {"id": "Upstream.Only", "version": "1.0.0", "description": "only upstream"}
                ]}""");

        ObjectNode merged = search.merge(List.of(hosted, proxied), SearchResponse.Query.of("", "0", "10", "true", null));
        assertThat(merged.path("totalHits").asInt()).isEqualTo(4);
        assertThat(ids(merged)).containsExactly("Acme.Lib", "Acme.Tools", "Other.Thing", "Upstream.Only");
        assertThat(merged.path("data").get(0).path("version").stringValue()).isEqualTo("2.0.0-beta.1");

        ObjectNode paged = search.merge(List.of(hosted, proxied), SearchResponse.Query.of("", "3", "10", "true", null));
        assertThat(paged.path("totalHits").asInt()).isEqualTo(4);
        assertThat(ids(paged)).containsExactly("Upstream.Only");
    }

    @Test
    void emptyResultIsWellFormed() {
        ObjectNode empty = search.empty();
        assertThat(empty.path("totalHits").asInt()).isZero();
        assertThat(empty.path("data").isArray()).isTrue();
        assertThat(empty.path("data").size()).isZero();
    }

    private static List<String> ids(JsonNode result) {
        List<String> ids = new java.util.ArrayList<>();
        result.path("data").forEach(hit -> ids.add(hit.path("id").stringValue()));
        return ids;
    }
}
