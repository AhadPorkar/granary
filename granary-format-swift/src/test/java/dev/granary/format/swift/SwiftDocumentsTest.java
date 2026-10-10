package dev.granary.format.swift;

import static dev.granary.format.swift.SwiftTestSupport.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.model.Repository;
import dev.granary.format.swift.SwiftDocuments.Release;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class SwiftDocumentsTest {

    private static final String BASE = "https://granary.test/repository/swift";
    private static final SwiftIdentity ID = new SwiftIdentity("mona", "LinkedList");

    private final SwiftDocuments documents = new SwiftDocuments(MAPPER);
    private final Repository a = SwiftTestSupport.hosted("a");
    private final Repository b = SwiftTestSupport.proxy("b", "https://up.test");

    private static Map<String, Release> releases(Repository owner, String... versions) {
        return SwiftDocuments.sorted(java.util.Arrays.stream(versions).map(v -> new Release(v, null, owner)).toList());
    }

    @Test
    void sortedIsHighestFirst() {
        assertThat(releases(a, "1.0.0", "1.10.0", "1.2.0", "2.0.0-beta").keySet())
                .containsExactly("2.0.0-beta", "1.10.0", "1.2.0", "1.0.0");
    }

    @Test
    void mergeKeepsTheFirstMemberAsOwner() {
        Map<String, Release> merged = SwiftDocuments.merge(List.of(releases(a, "1.0.0", "1.1.0"), releases(b, "1.1.0", "2.0.0")));
        assertThat(merged.keySet()).containsExactly("2.0.0", "1.1.0", "1.0.0");
        assertThat(merged.get("1.1.0").owner()).isSameAs(a);
        assertThat(merged.get("2.0.0").owner()).isSameAs(b);
    }

    @Test
    void parsesUpstreamReleaseListsWithProblems() {
        JsonNode doc = MAPPER.readTree("""
                {"releases":{
                  "1.0.0":{"url":"https://up.test/mona/LinkedList/1.0.0"},
                  "1.1.0":{"url":"https://up.test/mona/LinkedList/1.1.0","problem":{"status":410,"title":"Gone","detail":"withdrawn"}},
                  "not-a-version":{},
                  "1.2.0":{}
                }}""");
        Map<String, Release> releases = SwiftDocuments.parseReleases(doc, b);
        assertThat(releases.keySet()).containsExactly("1.2.0", "1.1.0", "1.0.0");
        assertThat(releases.get("1.1.0").available()).isFalse();
        assertThat(SwiftDocuments.available(releases)).containsExactly("1.2.0", "1.0.0");
        assertThat(SwiftDocuments.parseReleases(MAPPER.readTree("{\"releases\":[]}"), b)).isEmpty();
        assertThat(SwiftDocuments.parseReleases(null, b)).isEmpty();
    }

    @Test
    void releasesDocumentPointsAtThisRepository() {
        Map<String, Release> releases = SwiftDocuments.parseReleases(MAPPER.readTree(
                "{\"releases\":{\"1.0.0\":{},\"1.1.0\":{\"problem\":{\"status\":410,\"detail\":\"gone\"}}}}"), b);
        ObjectNode doc = documents.releasesDocument(BASE, ID, releases.values());
        assertThat(doc.path("releases").path("1.0.0").path("url").stringValue()).isEqualTo(BASE + "/mona/LinkedList/1.0.0");
        assertThat(doc.path("releases").path("1.1.0").path("problem").path("status").intValue()).isEqualTo(410);
        assertThat(doc.path("releases").path("1.0.0").has("problem")).isFalse();
    }

    @Test
    void releaseDocumentShape() {
        ObjectNode metadata = MAPPER.createObjectNode().put("description", "d");
        ObjectNode doc = documents.releaseDocument(ID, "1.1.1", "a".repeat(64), null, null, metadata,
                Instant.parse("2024-01-31T12:00:00.5Z"));
        assertThat(doc.path("id").stringValue()).isEqualTo("mona.LinkedList");
        assertThat(doc.path("version").stringValue()).isEqualTo("1.1.1");
        JsonNode archive = doc.path("resources").get(0);
        assertThat(archive.path("name").stringValue()).isEqualTo("source-archive");
        assertThat(archive.path("type").stringValue()).isEqualTo("application/zip");
        assertThat(archive.path("checksum").stringValue()).isEqualTo("a".repeat(64));
        assertThat(archive.has("signing")).isFalse();
        assertThat(doc.path("metadata").path("description").stringValue()).isEqualTo("d");
        assertThat(doc.path("publishedAt").stringValue()).isEqualTo("2024-01-31T12:00:00Z");
        assertThat(SwiftDocuments.checksum(doc)).contains("a".repeat(64));
        assertThat(SwiftDocuments.signing(doc)).isEmpty();

        ObjectNode signed = documents.releaseDocument(ID, "1.1.1", "B".repeat(64), "c2ln", "cms-1.0.0", null, Instant.now());
        assertThat(signed.path("metadata").isObject()).isTrue();
        assertThat(SwiftDocuments.checksum(signed)).contains("b".repeat(64));
        assertThat(SwiftDocuments.signing(signed)).hasValueSatisfying(s -> assertThat(s).containsExactly("c2ln", "cms-1.0.0"));
    }

    @Test
    void checksumOfForeignDocuments() {
        assertThat(SwiftDocuments.checksum(null)).isEmpty();
        assertThat(SwiftDocuments.checksum(MAPPER.readTree("{\"resources\":[{\"name\":\"other\",\"checksum\":\"" + "a".repeat(64) + "\"}]}"))).isEmpty();
        assertThat(SwiftDocuments.checksum(MAPPER.readTree("{\"resources\":[{\"name\":\"source-archive\",\"checksum\":\"xyz\"}]}"))).isEmpty();
        assertThat(SwiftDocuments.signing(MAPPER.readTree("{\"resources\":[{\"name\":\"source-archive\",\"signing\":{\"signatureFormat\":\"cms-1.0.0\"}}]}"))).isEmpty();
    }

    @Test
    void identifiers() {
        ObjectNode doc = documents.identifiersDocument(List.of("mona.LinkedList", "acme.list"));
        assertThat(doc.toString()).isEqualTo("{\"identifiers\":[\"mona.LinkedList\",\"acme.list\"]}");
        assertThat(SwiftDocuments.parseIdentifiers(MAPPER.readTree("{\"identifiers\":[\"mona.LinkedList\",\"bad\",3,\" acme.list \"]}")))
                .containsExactly("mona.LinkedList", "acme.list");
        assertThat(SwiftDocuments.parseIdentifiers(null)).isEmpty();

        List<String> target = new java.util.ArrayList<>(List.of("mona.LinkedList"));
        SwiftDocuments.addAll(target, List.of("MONA.linkedlist", "acme.list"));
        assertThat(target).containsExactly("mona.LinkedList", "acme.list");
    }

    @Test
    void releaseListLinks() {
        String links = SwiftDocuments.releasesLinks(BASE, ID, List.of("1.1.0", "1.0.0"),
                List.of("https://github.com/mona/LinkedList", "git@github.com:mona/LinkedList.git", "https://x.test/a,b")).orElseThrow();
        assertThat(links).isEqualTo("<" + BASE + "/mona/LinkedList/1.1.0>; rel=\"latest-version\", "
                + "<https://github.com/mona/LinkedList>; rel=\"canonical\", "
                + "<git@github.com:mona/LinkedList.git>; rel=\"alternate\"");
        assertThat(SwiftDocuments.releasesLinks(BASE, ID, List.of(), List.of())).isEmpty();
    }

    @Test
    void releaseLinksNameNeighbours() {
        List<String> available = List.of("2.0.0", "1.2.0", "1.1.0", "1.0.0");
        assertThat(SwiftDocuments.releaseLinks(BASE, ID, available, "1.1.0")).contains(
                "<" + BASE + "/mona/LinkedList/2.0.0>; rel=\"latest-version\", "
                        + "<" + BASE + "/mona/LinkedList/1.2.0>; rel=\"successor-version\", "
                        + "<" + BASE + "/mona/LinkedList/1.0.0>; rel=\"predecessor-version\"");
        assertThat(SwiftDocuments.releaseLinks(BASE, ID, available, "2.0.0")).contains(
                "<" + BASE + "/mona/LinkedList/2.0.0>; rel=\"latest-version\", "
                        + "<" + BASE + "/mona/LinkedList/1.2.0>; rel=\"predecessor-version\"");
        assertThat(SwiftDocuments.releaseLinks(BASE, ID, List.of(), "1.0.0")).isEmpty();
    }

    @Test
    void linkSafety() {
        assertThat(SwiftDocuments.linkSafe("https://github.com/mona/LinkedList")).isTrue();
        assertThat(SwiftDocuments.linkSafe("https://x.test/a;b")).isFalse();
        assertThat(SwiftDocuments.linkSafe("https://x.test/<a>")).isFalse();
        assertThat(SwiftDocuments.linkSafe("https://x.test/a b")).isFalse();
        assertThat(SwiftDocuments.linkSafe("https://x.test/\"")).isFalse();
        assertThat(SwiftDocuments.linkSafe("x".repeat(2049))).isFalse();
        assertThat(SwiftDocuments.linkSafe(null)).isFalse();
    }

    @Test
    void urls() {
        assertThat(SwiftDocuments.packageUrl(BASE, ID)).isEqualTo(BASE + "/mona/LinkedList");
        assertThat(SwiftDocuments.manifestUrl(BASE, ID, "1.0.0")).isEqualTo(BASE + "/mona/LinkedList/1.0.0/Package.swift");
        assertThat(SwiftDocuments.text(MAPPER.readTree("{\"a\":1}"), "a")).isNull();
        assertThat(SwiftDocuments.text(null, "a")).isNull();
    }
}
