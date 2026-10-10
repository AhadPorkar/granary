package dev.granary.format.conan;

import static dev.granary.format.conan.ConanTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

class ConanRevisionsTest {

    private static final Instant T1 = Instant.parse("2026-01-31T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-02-01T08:30:15.250Z");

    @Test
    void formatsLikeArtifactory() {
        assertThat(ConanRevisions.format(T1)).isEqualTo("2026-01-31T12:00:00.000+0000");
        assertThat(ConanRevisions.format(T2)).isEqualTo("2026-02-01T08:30:15.250+0000");
        assertThat(ConanRevisions.format(null)).isEqualTo("1970-01-01T00:00:00.000+0000");
    }

    @Test
    void parsesEveryTimestampShapeServersProduce() {
        assertThat(ConanRevisions.parseTime("2026-01-31T12:00:00.000+0000")).contains(T1);
        assertThat(ConanRevisions.parseTime("2026-01-31T12:00:00+00:00")).contains(T1);
        assertThat(ConanRevisions.parseTime("2026-01-31T12:00:00Z")).contains(T1);
        assertThat(ConanRevisions.parseTime("2026-01-31T12:00:00")).contains(T1);
        assertThat(ConanRevisions.parseTime("2026-01-31T13:00:00.000+0100")).contains(T1);
        assertThat(ConanRevisions.parseTime(" 2026-01-31T12:00:00.000+0000 ")).contains(T1);
        assertThat(ConanRevisions.parseTime(ConanRevisions.format(T2))).contains(T2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "yesterday", "2026-13-01T00:00:00Z", "1700000000", "2026-01-31"})
    void unparsableTimesAreEmpty(String text) {
        assertThat(ConanRevisions.parseTime(text)).isEmpty();
    }

    @Test
    void parseOfNullTimeIsEmpty() {
        assertThat(ConanRevisions.parseTime(null)).isEmpty();
    }

    @Test
    void revisionsDocumentShape() {
        JsonNode doc = json(ConanRevisions.revisionsDocument(List.of(
                ConanRevisions.entry("b", T2), ConanRevisions.entry("a", T1))).toString());
        assertThat(doc.path("revisions")).hasSize(2);
        assertThat(doc.path("revisions").get(0).path("revision").stringValue()).isEqualTo("b");
        assertThat(doc.path("revisions").get(0).path("time").stringValue()).isEqualTo("2026-02-01T08:30:15.250+0000");
        JsonNode latest = json(ConanRevisions.latestDocument(ConanRevisions.entry("b", T2)).toString());
        assertThat(latest.path("revision").stringValue()).isEqualTo("b");
        assertThat(latest.path("time").stringValue()).isEqualTo("2026-02-01T08:30:15.250+0000");
        assertThat(json(ConanRevisions.revisionsDocument(List.of()).toString()).path("revisions").isArray()).isTrue();
    }

    @Test
    void filesDocumentShape() {
        JsonNode doc = json(ConanRevisions.filesDocument(List.of("conanfile.py", "conanmanifest.txt", "metadata/sign/x")).toString());
        assertThat(doc.path("files").propertyNames()).containsExactly("conanfile.py", "conanmanifest.txt", "metadata/sign/x");
        assertThat(doc.path("files").path("conanfile.py").isObject()).isTrue();
        assertThat(doc.path("files").path("conanfile.py").isEmpty()).isTrue();
    }

    @Test
    void parsesRevisionDocumentsAndSkipsJunk() {
        JsonNode doc = json("""
                {"revisions":[
                  {"revision":"r1","time":"2026-01-31T12:00:00.000+0000"},
                  {"revision":"","time":"2026-01-31T12:00:00.000+0000"},
                  {"revision":42},
                  "not an object",
                  {"revision":"r2"},
                  {"revision":"r3","time":"garbage"}
                ]}""");
        List<ConanRevisions.Entry> entries = ConanRevisions.parseRevisions(doc);
        assertThat(entries).extracting(ConanRevisions.Entry::revision).containsExactly("r1", "r2", "r3");
        assertThat(entries.get(0).instant()).isEqualTo(T1);
        assertThat(entries.get(0).time()).isEqualTo("2026-01-31T12:00:00.000+0000");
        assertThat(entries.get(1).instant()).isEqualTo(Instant.EPOCH);
        assertThat(entries.get(1).time()).isEqualTo("1970-01-01T00:00:00.000+0000");
        // an unparsable time is passed through as the server wrote it, and sorts last
        assertThat(entries.get(2).time()).isEqualTo("garbage");
        assertThat(entries.get(2).instant()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void parseRevisionsOfOddDocuments() {
        assertThat(ConanRevisions.parseRevisions(null)).isEmpty();
        assertThat(ConanRevisions.parseRevisions(json("{}"))).isEmpty();
        assertThat(ConanRevisions.parseRevisions(json("{\"revisions\":{}}"))).isEmpty();
        assertThat(ConanRevisions.parseRevisions(json("[]"))).isEmpty();
        assertThat(ConanRevisions.parseEntry(null)).isEmpty();
        assertThat(ConanRevisions.parseEntry(json("\"x\""))).isEmpty();
        assertThat(ConanRevisions.parseEntry(json("{\"revision\":\"r\",\"time\":\"2026-01-31T12:00:00Z\"}")))
                .hasValueSatisfying(e -> assertThat(e.instant()).isEqualTo(T1));
    }

    @Test
    void parsesFileLists() {
        assertThat(ConanRevisions.parseFiles(json("{\"files\":{\"b\":{},\"a\":{}}}"))).containsExactly("b", "a");
        assertThat(ConanRevisions.parseFiles(json("{\"files\":[\"a\"]}"))).isEmpty();
        assertThat(ConanRevisions.parseFiles(json("{}"))).isEmpty();
        assertThat(ConanRevisions.parseFiles(null)).isEmpty();
    }

    @Test
    void mergeIsAUnionNewestFirstWithTheFirstSourceWinning() {
        ConanRevisions.Entry hostedR1 = ConanRevisions.entry("r1", T1);
        ConanRevisions.Entry proxyR1 = new ConanRevisions.Entry("r1", "proxy-time", T2);
        ConanRevisions.Entry proxyR2 = ConanRevisions.entry("r2", T2);
        ConanRevisions.Entry proxyR0 = ConanRevisions.entry("r0", Instant.EPOCH);
        List<ConanRevisions.Entry> merged = ConanRevisions.merge(List.of(List.of(hostedR1), List.of(proxyR1, proxyR2, proxyR0)));
        assertThat(merged).extracting(ConanRevisions.Entry::revision).containsExactly("r2", "r1", "r0");
        assertThat(merged.get(1)).isSameAs(hostedR1);
        assertThat(ConanRevisions.merge(List.of())).isEmpty();
    }

    @Test
    void equalTimesAreOrderedByRevisionForAStableAnswer() {
        List<ConanRevisions.Entry> sorted = ConanRevisions.sorted(List.of(
                ConanRevisions.entry("c", T1), ConanRevisions.entry("a", T1), ConanRevisions.entry("b", T2)));
        assertThat(sorted).extracting(ConanRevisions.Entry::revision).containsExactly("b", "a", "c");
    }

    @Test
    void entryWithoutTimeIsTheEpoch() {
        assertThat(ConanRevisions.entry("r", null).instant()).isEqualTo(Instant.EPOCH);
    }
}
