package dev.granary.format.nuget;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RegistrationBuilderTest {

    private static final String BASE = "http://granary.test/repository/nuget-hosted";
    private static final String UPSTREAM = "https://api.nuget.org";

    private final ObjectMapper json = JsonMapper.builder().build();
    private final RegistrationBuilder builder = new RegistrationBuilder(json);

    private static PackageEntry entry(String version, String... deps) {
        TestNupkgs.NuspecXml xml = TestNupkgs.nuspec("Acme.Lib", version)
                .description("Acme library " + version)
                .authors("Acme")
                .licenseUrl("https://example.com/license")
                .projectUrl("https://example.com/acme")
                .tags("acme lib");
        for (int i = 0; i + 1 < deps.length; i += 2) {
            xml.dependency("net8.0", deps[i], deps[i + 1]);
        }
        Nuspec nuspec = NuspecReader.parse(xml.xml().getBytes(StandardCharsets.UTF_8));
        return new PackageEntry(nuspec, NuGetVersion.parse(version), Instant.parse("2026-01-02T03:04:05Z"), true, 7);
    }

    @Test
    void buildsAnIndexWithOneInlinedPageOrderedByVersion() {
        ObjectNode index = builder.index(BASE, "acme.lib",
                List.of(entry("2.0.0"), entry("1.0.0-beta.1"), entry("1.0.0", "Newtonsoft.Json", "13.0.1")));

        assertThat(index.path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/acme.lib/index.json");
        assertThat(index.path("count").asInt()).isEqualTo(1);
        assertThat(index.path("@type")).extracting(JsonNode::stringValue)
                .containsExactly("catalog:CatalogRoot", "PackageRegistration", "catalog:Permalink");
        JsonNode page = index.path("items").get(0);
        assertThat(page.path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/acme.lib/index.json#page/1.0.0-beta.1/2.0.0");
        assertThat(page.path("parent").stringValue()).isEqualTo(BASE + "/v3/registration/acme.lib/index.json");
        assertThat(page.path("lower").stringValue()).isEqualTo("1.0.0-beta.1");
        assertThat(page.path("upper").stringValue()).isEqualTo("2.0.0");
        assertThat(page.path("count").asInt()).isEqualTo(3);
        assertThat(page.path("items")).extracting(i -> i.path("catalogEntry").path("version").stringValue())
                .containsExactly("1.0.0-beta.1", "1.0.0", "2.0.0");

        JsonNode item = page.path("items").get(1);
        assertThat(item.path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/acme.lib/1.0.0.json");
        assertThat(item.path("@type").stringValue()).isEqualTo("Package");
        assertThat(item.path("packageContent").stringValue()).isEqualTo(BASE + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.nupkg");
        assertThat(item.path("registration").stringValue()).isEqualTo(BASE + "/v3/registration/acme.lib/index.json");

        JsonNode entry = item.path("catalogEntry");
        assertThat(entry.path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/acme.lib/1.0.0.json");
        assertThat(entry.path("@type").stringValue()).isEqualTo("PackageDetails");
        assertThat(entry.path("id").stringValue()).isEqualTo("Acme.Lib");
        assertThat(entry.path("version").stringValue()).isEqualTo("1.0.0");
        assertThat(entry.path("description").stringValue()).isEqualTo("Acme library 1.0.0");
        assertThat(entry.path("authors").stringValue()).isEqualTo("Acme");
        assertThat(entry.path("listed").asBoolean()).isTrue();
        assertThat(entry.path("published").stringValue()).isEqualTo("2026-01-02T03:04:05Z");
        assertThat(entry.path("packageContent").stringValue()).isEqualTo(BASE + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.nupkg");
        assertThat(entry.path("licenseUrl").stringValue()).isEqualTo("https://example.com/license");
        assertThat(entry.path("projectUrl").stringValue()).isEqualTo("https://example.com/acme");
        assertThat(entry.path("tags")).extracting(JsonNode::stringValue).containsExactly("acme", "lib");
        JsonNode group = entry.path("dependencyGroups").get(0);
        assertThat(group.path("targetFramework").stringValue()).isEqualTo("net8.0");
        JsonNode dependency = group.path("dependencies").get(0);
        assertThat(dependency.path("id").stringValue()).isEqualTo("Newtonsoft.Json");
        assertThat(dependency.path("range").stringValue()).isEqualTo("[13.0.1, )");
        assertThat(dependency.path("registration").stringValue()).isEqualTo(BASE + "/v3/registration/newtonsoft.json/index.json");
    }

    @Test
    void emptyIndexHasNoPages() {
        ObjectNode index = builder.index(BASE, "nothing", List.of());
        assertThat(index.path("count").asInt()).isZero();
        assertThat(index.path("items").size()).isZero();
    }

    @Test
    void extractsALeafForAVersion() {
        ObjectNode index = builder.index(BASE, "acme.lib", List.of(entry("1.0.0"), entry("1.1.0")));

        ObjectNode leaf = builder.leaf(index, BASE, "acme.lib", NuGetVersion.parse("1.1")).orElseThrow();
        assertThat(leaf.path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/acme.lib/1.1.0.json");
        assertThat(leaf.path("@type")).extracting(JsonNode::stringValue).contains("Package");
        assertThat(leaf.path("catalogEntry").stringValue()).isEqualTo(BASE + "/v3/registration/acme.lib/1.1.0.json");
        assertThat(leaf.path("packageContent").stringValue()).isEqualTo(BASE + "/v3/flatcontainer/acme.lib/1.1.0/acme.lib.1.1.0.nupkg");
        assertThat(leaf.path("registration").stringValue()).isEqualTo(BASE + "/v3/registration/acme.lib/index.json");
        assertThat(leaf.path("listed").asBoolean()).isTrue();
        assertThat(leaf.path("published").stringValue()).isEqualTo("2026-01-02T03:04:05Z");

        assertThat(builder.leaf(index, BASE, "acme.lib", NuGetVersion.parse("9.9.9"))).isEmpty();
    }

    @Test
    void rewritesEveryUpstreamUrlToTheRepository() {
        JsonNode upstream = json.readTree(upstreamIndex(true));

        ObjectNode rewritten = builder.rewrite(upstream, BASE, "newtonsoft.json");

        String text = json.writeValueAsString(rewritten);
        assertThat(text).doesNotContain(UPSTREAM);
        assertThat(rewritten.path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/newtonsoft.json/index.json");
        JsonNode page = rewritten.path("items").get(0);
        assertThat(page.path("parent").stringValue()).isEqualTo(BASE + "/v3/registration/newtonsoft.json/index.json");
        assertThat(page.path("lower").stringValue()).isEqualTo("12.0.3");
        assertThat(page.path("upper").stringValue()).isEqualTo("13.0.1");
        assertThat(page.path("items").size()).isEqualTo(2);
        JsonNode item = page.path("items").get(1);
        assertThat(item.path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/newtonsoft.json/13.0.1.json");
        assertThat(item.path("packageContent").stringValue())
                .isEqualTo(BASE + "/v3/flatcontainer/newtonsoft.json/13.0.1/newtonsoft.json.13.0.1.nupkg");
        assertThat(item.path("registration").stringValue()).isEqualTo(BASE + "/v3/registration/newtonsoft.json/index.json");
        JsonNode entry = item.path("catalogEntry");
        assertThat(entry.path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/newtonsoft.json/13.0.1.json");
        assertThat(entry.path("packageContent").stringValue())
                .isEqualTo(BASE + "/v3/flatcontainer/newtonsoft.json/13.0.1/newtonsoft.json.13.0.1.nupkg");
        assertThat(entry.path("id").stringValue()).isEqualTo("Newtonsoft.Json");
        assertThat(entry.path("description").stringValue()).isEqualTo("Json.NET");
        assertThat(entry.path("dependencyGroups").get(0).path("dependencies").get(0).path("registration").stringValue())
                .isEqualTo(BASE + "/v3/registration/microsoft.csharp/index.json");
        // catalog fragment ids move onto the leaf URL, keeping their fragment
        assertThat(entry.path("dependencyGroups").get(0).path("@id").stringValue())
                .isEqualTo(BASE + "/v3/registration/newtonsoft.json/13.0.1.json#dependencygroup/.netframework2.0");
        assertThat(entry.path("dependencyGroups").get(0).path("dependencies").get(0).path("@id").stringValue())
                .isEqualTo(BASE + "/v3/registration/newtonsoft.json/13.0.1.json#dependencygroup/.netframework2.0/microsoft.csharp");
        // the leaf that was described only by its @id (no catalogEntry.version) is still placed by that id
        assertThat(page.path("items").get(0).path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/newtonsoft.json/12.0.3.json");
    }

    @Test
    void rewriteDropsItemsWhoseVersionCannotBeDetermined() {
        JsonNode upstream = json.readTree("""
                {"@id": "%s/v3/registration5-gz-semver2/x/index.json", "count": 1, "items": [
                  {"@id": "%s/v3/registration5-gz-semver2/x/index.json#page/a/b", "count": 2, "items": [
                    {"@id": "%s/v3/registration5-gz-semver2/x/not-a-version.json", "catalogEntry": {"id": "x"}},
                    {"@id": "%s/v3/registration5-gz-semver2/x/1.0.0.json", "catalogEntry": {"id": "x", "version": "1.0.0"}}
                  ]}
                ]}""".formatted(UPSTREAM, UPSTREAM, UPSTREAM, UPSTREAM));
        ObjectNode rewritten = builder.rewrite(upstream, BASE, "x");
        assertThat(RegistrationBuilder.leaves(rewritten)).hasSize(1);
        assertThat(RegistrationBuilder.leaves(rewritten).get(0).path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/x/1.0.0.json");
    }

    @Test
    void nonInlinedPagesContributeNothingUntilTheyAreInlined() {
        JsonNode upstream = json.readTree(upstreamIndex(false));
        assertThat(RegistrationBuilder.leaves(upstream)).isEmpty();
        assertThat(builder.rewrite(upstream, BASE, "newtonsoft.json").path("count").asInt()).isZero();
    }

    @Test
    void mergesIndexesByVersionWithTheFirstMemberWinning() {
        ObjectNode hosted = builder.index(BASE, "acme.lib", List.of(entry("1.0.0"), entry("2.0.0")));
        PackageEntry other = new PackageEntry(
                NuspecReader.parse(TestNupkgs.nuspec("Acme.Lib", "2.0.0").description("from the proxy").xml().getBytes(StandardCharsets.UTF_8)),
                NuGetVersion.parse("2.0.0"), Instant.EPOCH, true, 0);
        ObjectNode proxied = builder.index(BASE, "acme.lib", List.of(other, entry("3.0.0-rc.1")));

        ObjectNode merged = builder.merge(BASE, "acme.lib", List.of(hosted, proxied));

        List<JsonNode> leaves = RegistrationBuilder.leaves(merged);
        assertThat(leaves).extracting(l -> l.path("catalogEntry").path("version").stringValue())
                .containsExactly("1.0.0", "2.0.0", "3.0.0-rc.1");
        assertThat(leaves.get(1).path("catalogEntry").path("description").stringValue()).isEqualTo("Acme library 2.0.0");
        assertThat(merged.path("items").get(0).path("upper").stringValue()).isEqualTo("3.0.0-rc.1");
        assertThat(merged.path("items").get(0).path("count").asInt()).isEqualTo(3);
    }

    @Test
    void convertsNuspecVersionsToRanges() {
        assertThat(RegistrationBuilder.rangeOf("1.0")).isEqualTo("[1.0.0, )");
        assertThat(RegistrationBuilder.rangeOf("13.0.1")).isEqualTo("[13.0.1, )");
        assertThat(RegistrationBuilder.rangeOf("[1.0.0, 2.0.0)")).isEqualTo("[1.0.0, 2.0.0)");
        assertThat(RegistrationBuilder.rangeOf("(1.0.0,)")).isEqualTo("(1.0.0,)");
        assertThat(RegistrationBuilder.rangeOf(null)).isEqualTo("(, )");
        assertThat(RegistrationBuilder.rangeOf("  ")).isEqualTo("(, )");
    }

    @Test
    void versionOfFallsBackToTheLeafUrl() {
        JsonNode withEntry = json.readTree("{\"@id\":\"x/1.0.0.json\",\"catalogEntry\":{\"version\":\"2.0\"}}");
        assertThat(RegistrationBuilder.versionOf(withEntry)).contains(NuGetVersion.parse("2.0.0"));
        JsonNode withoutEntry = json.readTree("{\"@id\":\"https://h/reg/x/3.0.0-beta.json\"}");
        assertThat(RegistrationBuilder.versionOf(withoutEntry)).contains(NuGetVersion.parse("3.0.0-beta"));
        assertThat(RegistrationBuilder.versionOf(json.readTree("{\"@id\":\"https://h/reg/x/index.json\"}"))).isEmpty();
        assertThat(RegistrationBuilder.versionOf(json.readTree("{}"))).isEmpty();
    }

    /** A nuget.org-shaped registration index; with {@code inlined} false the page only carries its URL. */
    static String upstreamIndex(boolean inlined) {
        String reg = UPSTREAM + "/v3/registration5-gz-semver2/newtonsoft.json";
        String items = inlined ? """
                , "items": [
                  {"@id": "%1$s/12.0.3.json", "@type": "Package",
                   "commitId": "c1", "commitTimeStamp": "2019-11-09T01:27:32.4096317+00:00",
                   "packageContent": "%2$s/v3-flatcontainer/newtonsoft.json/12.0.3/newtonsoft.json.12.0.3.nupkg",
                   "registration": "%1$s/index.json",
                   "catalogEntry": {"@id": "%2$s/v3/catalog0/data/2019.11.09.01.27.32/newtonsoft.json.12.0.3.json", "@type": "PackageDetails",
                     "id": "Newtonsoft.Json", "description": "Json.NET", "authors": "James Newton-King", "listed": true,
                     "published": "2019-11-09T01:27:32+00:00",
                     "packageContent": "%2$s/v3-flatcontainer/newtonsoft.json/12.0.3/newtonsoft.json.12.0.3.nupkg"}},
                  {"@id": "%1$s/13.0.1.json", "@type": "Package",
                   "packageContent": "%2$s/v3-flatcontainer/newtonsoft.json/13.0.1/newtonsoft.json.13.0.1.nupkg",
                   "registration": "%1$s/index.json",
                   "catalogEntry": {"@id": "%2$s/v3/catalog0/data/2021.03.22.15.49.28/newtonsoft.json.13.0.1.json", "@type": "PackageDetails",
                     "id": "Newtonsoft.Json", "version": "13.0.1", "description": "Json.NET", "authors": "James Newton-King",
                     "listed": true, "published": "2021-03-22T15:49:28.03+00:00",
                     "licenseUrl": "https://www.nuget.org/packages/Newtonsoft.Json/13.0.1/license",
                     "packageContent": "%2$s/v3-flatcontainer/newtonsoft.json/13.0.1/newtonsoft.json.13.0.1.nupkg",
                     "dependencyGroups": [{"@id": "%2$s/v3/catalog0/data/2021.03.22.15.49.28/newtonsoft.json.13.0.1.json#dependencygroup/.netframework2.0",
                       "@type": "PackageDependencyGroup", "targetFramework": ".NETFramework2.0",
                       "dependencies": [{"@id": "%2$s/v3/catalog0/data/2021.03.22.15.49.28/newtonsoft.json.13.0.1.json#dependencygroup/.netframework2.0/microsoft.csharp",
                         "@type": "PackageDependency", "id": "Microsoft.CSharp", "range": "[4.3.0, )",
                         "registration": "%2$s/v3/registration5-gz-semver2/microsoft.csharp/index.json"}]}]}}
                ]""".formatted(reg, UPSTREAM) : "";
        return """
                {"@id": "%1$s/index.json", "@type": ["catalog:CatalogRoot", "PackageRegistration", "catalog:Permalink"],
                 "commitId": "c2", "commitTimeStamp": "2021-03-22T15:49:28.03+00:00", "count": 1,
                 "items": [
                   {"@id": "%1$s/page/12.0.3/13.0.1.json", "@type": "catalog:CatalogPage", "count": 2,
                    "lower": "12.0.3", "upper": "13.0.1", "parent": "%1$s/index.json"%2$s}
                 ],
                 "@context": {"@vocab": "http://schema.nuget.org/schema#", "catalog": "http://schema.nuget.org/catalog#"}}
                """.formatted(reg, items);
    }

    /** The page document upstream serves for the non-inlined page of {@link #upstreamIndex}. */
    static String upstreamPage() {
        JsonNode full = JsonMapper.builder().build().readTree(upstreamIndex(true));
        return JsonMapper.builder().build().writeValueAsString(full.path("items").get(0));
    }
}
