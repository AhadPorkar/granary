package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexEntryTest {

    /** A real line of index.crates.io, including a field (pubtime) cargo versions before 1.9x do not know. */
    static final String ITOA = "{\"name\":\"itoa\",\"vers\":\"1.0.18\",\"deps\":[{\"name\":\"no-panic\",\"req\":\"^0.1\","
            + "\"features\":[],\"optional\":true,\"default_features\":true,\"target\":null,\"kind\":\"normal\"}],"
            + "\"cksum\":\"8f42a60cbdf9a97f5d2305f08a87dc4e09308d1276d28c869c684d7777685682\",\"features\":{},"
            + "\"yanked\":false,\"rust_version\":\"1.68\",\"pubtime\":\"2026-03-20T00:12:37Z\"}";

    static String line(String name, String vers, boolean yanked) {
        return "{\"name\":\"" + name + "\",\"vers\":\"" + vers + "\",\"deps\":[],\"cksum\":\"" + "ab".repeat(32)
                + "\",\"features\":{},\"yanked\":" + yanked + "}";
    }

    @Test
    void parsesAndKeepsTheOriginalText() {
        IndexEntry e = IndexEntry.parse(ITOA).orElseThrow();
        assertThat(e.name()).isEqualTo("itoa");
        assertThat(e.vers()).isEqualTo("1.0.18");
        assertThat(e.cksum()).isEqualTo("8f42a60cbdf9a97f5d2305f08a87dc4e09308d1276d28c869c684d7777685682");
        assertThat(e.yanked()).isFalse();
        assertThat(e.line()).isEqualTo(ITOA);
    }

    @Test
    void unusableLinesAreSkipped() {
        assertThat(IndexEntry.parse("")).isEmpty();
        assertThat(IndexEntry.parse("   ")).isEmpty();
        assertThat(IndexEntry.parse(null)).isEmpty();
        assertThat(IndexEntry.parse("{not json")).isEmpty();
        assertThat(IndexEntry.parse("[1,2]")).isEmpty();
        assertThat(IndexEntry.parse("{\"name\":\"x\"}")).isEmpty();
        assertThat(IndexEntry.parse("{\"vers\":\"1.0.0\"}")).isEmpty();
        assertThat(IndexEntry.parse("{\"name\":\"\",\"vers\":\"1.0.0\"}")).isEmpty();
        assertThat(IndexEntry.parse("{\"name\":1,\"vers\":\"1.0.0\"}")).isEmpty();
    }

    @Test
    void yankedNeedsARealBoolean() {
        assertThat(IndexEntry.parse(line("x", "1.0.0", true)).orElseThrow().yanked()).isTrue();
        assertThat(IndexEntry.parse("{\"name\":\"x\",\"vers\":\"1.0.0\",\"yanked\":\"true\"}").orElseThrow().yanked()).isFalse();
        assertThat(IndexEntry.parse("{\"name\":\"x\",\"vers\":\"1.0.0\"}").orElseThrow().cksum()).isEmpty();
    }

    @Test
    void fileParsingToleratesCrLfBlankLinesAndGarbage() {
        String file = line("x", "1.0.0", false) + "\r\n\n" + "garbage\n" + line("x", "1.1.0", false);
        List<IndexEntry> entries = IndexEntry.parseFile(file);
        assertThat(entries).extracting(IndexEntry::vers).containsExactly("1.0.0", "1.1.0");
        assertThat(IndexEntry.parseFile(null)).isEmpty();
        assertThat(IndexEntry.parseFile("")).isEmpty();
    }

    @Test
    void renderTerminatesEveryLine() {
        List<IndexEntry> entries = IndexEntry.parseFile(line("x", "1.0.0", false) + "\n" + line("x", "1.1.0", false));
        assertThat(IndexEntry.render(entries)).isEqualTo(line("x", "1.0.0", false) + "\n" + line("x", "1.1.0", false) + "\n");
        assertThat(IndexEntry.render(List.of())).isEmpty();
    }

    @Test
    void withYankedChangesOnlyTheFlag() {
        IndexEntry e = IndexEntry.parse(ITOA).orElseThrow();
        IndexEntry yanked = e.withYanked(true);
        assertThat(yanked.yanked()).isTrue();
        assertThat(yanked.line()).isEqualTo(ITOA.replace("\"yanked\":false", "\"yanked\":true"));
        assertThat(yanked.withYanked(false).line()).isEqualTo(ITOA);
    }

    @Test
    void findPrefersAnExactMatchAndFallsBackToTheOnlyBuildVariant() {
        List<IndexEntry> entries = IndexEntry.parseFile(String.join("\n",
                line("x", "1.0.0+a", false), line("x", "1.0.0", false), line("x", "2.0.0+linux", false)));
        assertThat(IndexEntry.find(entries, "1.0.0").orElseThrow().vers()).isEqualTo("1.0.0");
        assertThat(IndexEntry.find(entries, "1.0.0+a").orElseThrow().vers()).isEqualTo("1.0.0+a");
        assertThat(IndexEntry.find(entries, "2.0.0").orElseThrow().vers()).isEqualTo("2.0.0+linux");
        assertThat(IndexEntry.find(entries, "3.0.0")).isEmpty();
        assertThat(IndexEntry.find(entries, "garbage")).isEmpty();
        // two build variants and no exact match: ambiguous
        List<IndexEntry> ambiguous = IndexEntry.parseFile(line("x", "1.0.0+a", false) + "\n" + line("x", "1.0.0+b", false));
        assertThat(IndexEntry.find(ambiguous, "1.0.0")).isEmpty();
    }

    @Test
    void mergeKeepsTheFirstSourcePerVersionAndOrdersBySemver() {
        List<IndexEntry> hosted = IndexEntry.parseFile(line("acme", "1.0.0", false) + "\n" + line("acme", "0.9.0", false));
        List<IndexEntry> proxy = IndexEntry.parseFile(line("acme", "1.0.0", true) + "\n" + line("acme", "0.10.0", false)
                + "\n" + line("acme", "1.0.0-rc.1", false));
        Map<String, List<IndexEntry>> sources = Map.of("hosted", hosted, "proxy", proxy);
        List<IndexEntry.Sourced<String>> merged = IndexEntry.merge(List.of("hosted", "proxy"), sources::get, s -> s);
        assertThat(merged).extracting(s -> s.entry().vers()).containsExactly("0.9.0", "0.10.0", "1.0.0-rc.1", "1.0.0");
        assertThat(merged).extracting(IndexEntry.Sourced::origin).containsExactly("hosted", "proxy", "proxy", "hosted");
        // the hosted line won 1.0.0, so it is not yanked
        assertThat(merged.get(3).entry().yanked()).isFalse();
    }

    @Test
    void mergeKeepsNonSemverVersionsAtTheEndInTheirOrder() {
        List<IndexEntry> a = IndexEntry.parseFile(line("x", "zzz", false) + "\n" + line("x", "2.0.0", false));
        List<IndexEntry> b = IndexEntry.parseFile(line("x", "aaa", false) + "\n" + line("x", "1.0.0", false));
        Map<String, List<IndexEntry>> sources = Map.of("a", a, "b", b);
        List<IndexEntry.Sourced<String>> merged = IndexEntry.merge(List.of("a", "b"), sources::get, s -> s);
        assertThat(merged).extracting(s -> s.entry().vers()).containsExactly("1.0.0", "2.0.0", "zzz", "aaa");
    }

    @Test
    void mergeOfNothingIsEmpty() {
        assertThat(IndexEntry.merge(List.<String>of(), s -> List.of(), s -> s)).isEmpty();
    }

    @Test
    void unicodeSurvivesUnchanged() {
        String text = "{\"name\":\"x\",\"vers\":\"1.0.0\",\"features\":{\"f\\u00fc\":[]},\"note\":\"é中\"}";
        IndexEntry e = IndexEntry.parse(text).orElseThrow();
        assertThat(e.line()).isEqualTo(text);
    }
}
