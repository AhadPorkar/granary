package dev.granary.format.pub;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PubListingTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String GROUP = "http://granary.test/repository/pub-group";

    private static ObjectNode json(String text) {
        return (ObjectNode) JSON.readTree(text.replace('\'', '"'));
    }

    /** A pub.dev-shaped listing with versions in the order given. */
    private static ObjectNode upstream(String name, String latest, String... versions) {
        StringBuilder sb = new StringBuilder("{'name':'" + name + "',");
        if (latest != null) {
            sb.append("'latest':{'version':'").append(latest).append("','archive_url':'https://pub.dev/api/archives/")
                    .append(name).append('-').append(latest).append(".tar.gz'},");
        }
        sb.append("'versions':[");
        for (int i = 0; i < versions.length; i++) {
            sb.append(i == 0 ? "" : ",").append("{'version':'").append(versions[i])
                    .append("','pubspec':{'name':'").append(name).append("','version':'").append(versions[i])
                    .append("'},'archive_url':'https://pub.dev/api/archives/").append(name).append('-').append(versions[i])
                    .append(".tar.gz','archive_sha256':'sha-").append(versions[i])
                    .append("','published':'2024-01-0").append(i % 9 + 1).append("T00:00:00.000000Z'}");
        }
        return json(sb.append("]}").toString());
    }

    private static List<String> versions(JsonNode listing) {
        return PubListing.entries(listing).stream().map(e -> e.version().text()).toList();
    }

    @Test
    void renderPointsEveryArchiveAtTheRepositoryAndSortsVersions() {
        ObjectNode rendered = PubListing.render("path", upstream("path", "1.9.1", "1.9.1", "1.8.0", "1.9.0"), GROUP, true);
        assertThat(versions(rendered)).containsExactly("1.8.0", "1.9.0", "1.9.1");
        for (JsonNode version : rendered.get("versions")) {
            assertThat(version.get("archive_url").stringValue())
                    .isEqualTo(GROUP + "/packages/path/versions/" + version.get("version").stringValue() + ".tar.gz");
            assertThat(version.get("archive_sha256").stringValue()).startsWith("sha-");
            assertThat(version.get("pubspec").get("name").stringValue()).isEqualTo("path");
        }
        assertThat(rendered.get("latest").get("version").stringValue()).isEqualTo("1.9.1");
        assertThat(rendered.get("latest").get("archive_url").stringValue())
                .isEqualTo(GROUP + "/packages/path/versions/1.9.1.tar.gz");
        assertThat(rendered.get("name").stringValue()).isEqualTo("path");
        assertThat(rendered.toString()).doesNotContain("pub.dev/api/archives");
    }

    @Test
    void renderKeepsMembersInPubDevsOrderAndUnknownMembersToo() {
        ObjectNode source = json("{'name':'x','versions':[{'published':'p','archive_sha256':'s','pubspec':{},"
                + "'archive_url':'u','retracted':true,'version':'1.0.0','x-extra':{'a':1},'_internal':'hidden'}]}");
        ObjectNode version = (ObjectNode) PubListing.render("x", source, GROUP, false).get("versions").get(0);
        assertThat(version.propertyNames()).containsExactly("version", "retracted", "pubspec", "archive_url",
                "archive_sha256", "published", "x-extra");
    }

    @Test
    void renderDropsUnusableEntriesAndDuplicates() {
        ObjectNode source = json("{'name':'x','versions':[{'version':'1.0.0'},{'version':'not-a-version'},"
                + "'garbage',{'pubspec':{}},{'version':'1.0.0+01'},{'version':'1.0.0+1'},{'version':'2.0.0'}]}");
        ObjectNode rendered = PubListing.render("x", source, GROUP, false);
        assertThat(versions(rendered)).containsExactly("1.0.0", "1.0.0+01", "2.0.0");
        // a version without a pubspec still gets an (empty) one, the client requires the member
        assertThat(rendered.get("versions").get(0).get("pubspec").isObject()).isTrue();
    }

    @Test
    void renderComputesLatestWithPubsRulesUnlessTheUpstreamNamedOne() {
        ObjectNode source = upstream("x", "1.0.0", "1.0.0", "1.1.0", "2.0.0-dev.1");
        assertThat(PubListing.render("x", source, GROUP, false).get("latest").get("version").stringValue()).isEqualTo("1.1.0");
        assertThat(PubListing.render("x", source, GROUP, true).get("latest").get("version").stringValue()).isEqualTo("1.0.0");
        // an upstream latest that is not listed is ignored
        ObjectNode bogus = upstream("x", "9.9.9", "1.0.0");
        assertThat(PubListing.render("x", bogus, GROUP, true).get("latest").get("version").stringValue()).isEqualTo("1.0.0");
        // retracted versions are not latest
        ObjectNode retracted = upstream("x", null, "1.0.0", "1.1.0");
        ((ObjectNode) retracted.get("versions").get(1)).put("retracted", true);
        assertThat(PubListing.render("x", retracted, GROUP, false).get("latest").get("version").stringValue()).isEqualTo("1.0.0");
    }

    @Test
    void renderOfAListingWithoutVersionsHasNoLatest() {
        ObjectNode rendered = PubListing.render("x", json("{'name':'x','versions':[]}"), GROUP, false);
        assertThat(rendered.has("latest")).isFalse();
        assertThat(rendered.get("versions").isEmpty()).isTrue();
        assertThat(PubListing.render("x", json("{'name':'x'}"), GROUP, false).get("versions").isArray()).isTrue();
        assertThat(PubListing.render("x", json("{'versions':{'1.0.0':{}}}"), GROUP, false).get("versions").isEmpty()).isTrue();
    }

    @Test
    void renderCarriesTheStatusFlagsOnlyWhenSet() {
        ObjectNode source = upstream("x", null, "1.0.0");
        source.put("isDiscontinued", true);
        source.put("replacedBy", "y");
        source.put("advisoriesUpdated", "2026-05-04T16:03:47.124153Z");
        ObjectNode rendered = PubListing.render("x", source, GROUP, true);
        assertThat(rendered.propertyNames()).containsExactly("name", "isDiscontinued", "replacedBy", "advisoriesUpdated",
                "latest", "versions");
        source.put("isDiscontinued", false);
        assertThat(PubListing.render("x", source, GROUP, true).has("isDiscontinued")).isFalse();
        assertThat(PubListing.render("x", source, GROUP, true).has("replacedBy")).isFalse();
        source.put("isDiscontinued", "yes"); // not a boolean: ignored
        assertThat(PubListing.render("x", source, GROUP, true).has("isDiscontinued")).isFalse();
    }

    @Test
    void renderTrustsTheUrlsPackageNameAndToleratesATrailingSlash() {
        ObjectNode rendered = PubListing.render("x", json("{'name':'evil','versions':[{'version':'1.0.0'}]}"), GROUP + "/", false);
        assertThat(rendered.get("name").stringValue()).isEqualTo("x");
        assertThat(rendered.get("versions").get(0).get("archive_url").stringValue())
                .isEqualTo(GROUP + "/packages/x/versions/1.0.0.tar.gz");
    }

    @Test
    void findPrefersTheExactSpellingThenAnEqualVersion() {
        ObjectNode listing = json("{'versions':[{'version':'1.0.0+1'},{'version':'2.0.0'}]}");
        assertThat(PubListing.find(listing, "1.0.0+1")).hasValueSatisfying(e -> assertThat(e.version().text()).isEqualTo("1.0.0+1"));
        assertThat(PubListing.find(listing, "1.0.0+01")).hasValueSatisfying(e -> assertThat(e.version().text()).isEqualTo("1.0.0+1"));
        assertThat(PubListing.find(listing, "3.0.0")).isEmpty();
        assertThat(PubListing.find(listing, "garbage")).isEmpty();
        assertThat(PubListing.find(null, "1.0.0")).isEmpty();
    }

    @Test
    void mergeGivesEachVersionToTheFirstMemberThatListsIt() {
        ObjectNode hosted = PubListing.render("x", upstream("x", null, "1.0.0", "3.0.0"), GROUP, false);
        ((ObjectNode) hosted.get("versions").get(0)).put("archive_sha256", "hosted-1.0.0");
        ObjectNode proxied = PubListing.render("x", upstream("x", "2.0.0", "1.0.0", "2.0.0"), GROUP, true);
        ObjectNode merged = PubListing.merge("x", List.of(hosted, proxied));
        assertThat(versions(merged)).containsExactly("1.0.0", "2.0.0", "3.0.0");
        assertThat(PubListing.find(merged, "1.0.0").orElseThrow().sha256()).contains("hosted-1.0.0");
        assertThat(merged.get("latest").get("version").stringValue()).isEqualTo("3.0.0");
        assertThat(merged.get("versions").get(1).get("archive_url").stringValue())
                .isEqualTo(GROUP + "/packages/x/versions/2.0.0.tar.gz");
    }

    @Test
    void mergeLatestPrefersReleasesOverPreReleases() {
        ObjectNode a = PubListing.render("x", upstream("x", null, "1.0.0"), GROUP, false);
        ObjectNode b = PubListing.render("x", upstream("x", null, "2.0.0-dev.1"), GROUP, false);
        assertThat(PubListing.merge("x", List.of(a, b)).get("latest").get("version").stringValue()).isEqualTo("1.0.0");
        assertThat(PubListing.merge("x", List.of(b)).get("latest").get("version").stringValue()).isEqualTo("2.0.0-dev.1");
    }

    @Test
    void mergeTakesStatusFromTheFirstMemberAndTheLatestAdvisoriesDate() {
        ObjectNode first = PubListing.render("x", upstream("x", null, "1.0.0"), GROUP, false);
        ObjectNode second = upstream("x", null, "2.0.0");
        second.put("isDiscontinued", true);
        second.put("advisoriesUpdated", "2026-05-04T16:03:47.124153Z");
        ObjectNode third = upstream("x", null, "2.0.0");
        third.put("advisoriesUpdated", "2025-01-01T00:00:00.000Z");
        ObjectNode merged = PubListing.merge("x", List.of(first, PubListing.render("x", second, GROUP, true),
                PubListing.render("x", third, GROUP, true)));
        assertThat(merged.has("isDiscontinued")).isFalse();
        assertThat(merged.get("advisoriesUpdated").stringValue()).isEqualTo("2026-05-04T16:03:47.124153Z");

        ObjectNode discontinuedFirst = PubListing.merge("x", List.of(PubListing.render("x", second, GROUP, true), first));
        assertThat(discontinuedFirst.get("isDiscontinued").booleanValue()).isTrue();
    }

    @Test
    void mergeOfNothingIsAnEmptyListing() {
        ObjectNode merged = PubListing.merge("x", List.of());
        assertThat(merged.get("name").stringValue()).isEqualTo("x");
        assertThat(merged.get("versions").isEmpty()).isTrue();
        assertThat(merged.has("latest")).isFalse();
    }

    @Test
    void mergedAdvisoriesContainEveryAdvisoryOnce() {
        ObjectNode a = json("{'advisories':[{'id':'GHSA-1','summary':'one'},{'summary':'no id'}],"
                + "'advisoriesUpdated':'2026-01-01T00:00:00.000'}");
        ObjectNode b = json("{'advisories':[{'id':'GHSA-1','summary':'dup'},{'id':'GHSA-2'}],"
                + "'advisoriesUpdated':'2026-02-01T00:00:00.000Z'}");
        ObjectNode merged = PubListing.mergeAdvisories(List.of(a, b, json("{'advisories':'broken'}")));
        assertThat(merged.get("advisories")).hasSize(3);
        assertThat(merged.get("advisories").get(0).get("summary").stringValue()).isEqualTo("one");
        assertThat(merged.get("advisoriesUpdated").stringValue()).isEqualTo("2026-02-01T00:00:00.000Z");
        assertThat(PubListing.mergeAdvisories(List.of()).get("advisoriesUpdated").isNull()).isTrue();
    }

    @Test
    void noAdvisoriesIsTheDocumentHostedPackagesAnswer() {
        assertThat(PubListing.noAdvisories().toString()).isEqualTo("{\"advisories\":[],\"advisoriesUpdated\":null}");
    }

    @Test
    void laterComparesTimestampsWithAndWithoutZones() {
        assertThat(PubListing.later(null, "a")).isEqualTo("a");
        assertThat(PubListing.later("a", null)).isEqualTo("a");
        assertThat(PubListing.later("2026-01-01T00:00:00.000", "2025-12-31T23:59:59Z")).isEqualTo("2026-01-01T00:00:00.000");
        assertThat(PubListing.later("2025-12-31T23:59:59Z", "2026-01-01T00:00:00+01:00")).isEqualTo("2025-12-31T23:59:59Z");
        assertThat(PubListing.later("b", "a")).isEqualTo("b"); // unparsable: text order
    }

    @Test
    void timestampsUseMicrosecondsInUtc() {
        assertThat(PubListing.timestamp(Instant.parse("2026-10-10T07:17:35.123Z"))).isEqualTo("2026-10-10T07:17:35.123000Z");
        assertThat(PubListing.timestamp(Instant.parse("2026-10-10T07:17:35Z"))).isEqualTo("2026-10-10T07:17:35.000000Z");
    }

    @Test
    void archiveUrlsAreBuiltFromTheRepositoryBase() {
        assertThat(PubListing.archiveUrl(GROUP, "x", "1.0.0+1")).isEqualTo(GROUP + "/packages/x/versions/1.0.0+1.tar.gz");
        assertThat(PubListing.archiveUrl(GROUP + "/", "x", "1.0.0")).isEqualTo(GROUP + "/packages/x/versions/1.0.0.tar.gz");
    }
}
