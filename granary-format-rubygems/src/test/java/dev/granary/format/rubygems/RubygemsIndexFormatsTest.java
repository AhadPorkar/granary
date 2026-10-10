package dev.granary.format.rubygems;

import static dev.granary.format.rubygems.RubygemsTestSupport.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.format.rubygems.marshal.MarshalException;
import dev.granary.format.rubygems.marshal.MarshalReader;
import dev.granary.format.rubygems.marshal.Ruby;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import org.junit.jupiter.api.Test;

class RubygemsIndexFormatsTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final String SHA = "cb825b2bd5f1f8e91ca37bddb4b9aaf345551b4731da62949be002fa89283701";

    static GemRecord record(String name, String version, String platform, Instant createdAt,
                            GemRecord.Dependency... deps) {
        return new GemRecord(name, version, platform, SHA, 1234, List.of(deps), ">= 0", ">= 0", "summary", null,
                List.of("Acme"), List.of("MIT"), null, Map.of(), "3.5.22", createdAt, false, null, "admin");
    }

    private static SpecTuple t(String name, String version, String platform) {
        return new SpecTuple(name, version, platform);
    }

    // --- specs indexes ------------------------------------------------------------------------------

    @Test
    void readsIndexesWrittenByRuby() throws IOException {
        List<SpecTuple> tuples = SpecsIndex.read(new ByteArrayInputStream(resource("ruby-specs.4.8.gz")));
        assertThat(tuples).containsExactly(
                t("alpha", "1.0", "ruby"), t("alpha", "1.1", "ruby"), t("alpha", "2.0", "ruby"),
                t("beta", "1.0", "ruby"), t("beta", "1.1", "ruby"), t("beta", "2.0", "ruby"),
                t("gamma", "0.1", "x86_64-linux"), t("delta", "3.0", "ruby"), t("delta", "3.0", "java"));
    }

    @Test
    void writesWhatItReadsAndSharesRepeatedStrings() throws IOException {
        List<SpecTuple> tuples = List.of(t("rake", "13.4.2", "ruby"), t("rake", "13.4.1", "ruby"),
                t("nokogiri", "1.16.0", "x86_64-linux"), t("nokogiri", "1.16.0", "ruby"));
        byte[] gz = SpecsIndex.gzip(SpecsIndex.sorted(tuples));
        assertThat(SpecsIndex.read(new ByteArrayInputStream(gz))).containsExactly(
                t("nokogiri", "1.16.0", "ruby"), t("nokogiri", "1.16.0", "x86_64-linux"),
                t("rake", "13.4.1", "ruby"), t("rake", "13.4.2", "ruby"));
        byte[] raw = gunzip(gz);
        // "rake" and "ruby" are written once and linked afterwards, as Marshal.dump does for shared objects
        assertThat(occurrences(raw, "rake")).isEqualTo(1);
        assertThat(occurrences(raw, "ruby")).isEqualTo(1);
        // a full (link-resolving) read sees the same values
        List<?> loaded = (List<?>) MarshalReader.load(raw);
        assertThat(loaded).hasSize(4);
        assertThat(((List<?>) loaded.get(3)).get(0)).isEqualTo("rake");
        assertThat(Ruby.gemVersionString(((List<?>) loaded.get(3)).get(1))).isEqualTo("13.4.2");
    }

    @Test
    void selectsReleasedPrereleaseAndLatest() {
        List<SpecTuple> all = List.of(t("a", "1.0", "ruby"), t("a", "2.0", "ruby"), t("a", "3.0.beta", "ruby"),
                t("a", "1.5", "java"), t("b", "0.1.pre", "ruby"), t("a", "2.0", "ruby"), t("c", "10.0", "ruby"),
                t("c", "9.0", "ruby"));
        assertThat(SpecsIndex.released(all)).containsExactly(t("a", "1.0", "ruby"), t("a", "1.5", "java"),
                t("a", "2.0", "ruby"), t("c", "9.0", "ruby"), t("c", "10.0", "ruby"));
        assertThat(SpecsIndex.prerelease(all)).containsExactly(t("a", "3.0.beta", "ruby"), t("b", "0.1.pre", "ruby"));
        assertThat(SpecsIndex.latest(all)).containsExactly(t("a", "1.5", "java"), t("a", "2.0", "ruby"),
                t("c", "10.0", "ruby"));
    }

    @Test
    void ordersTuplesLikeRubygems() {
        List<SpecTuple> sorted = SpecsIndex.sorted(List.of(t("b", "1.0", "ruby"), t("a", "1.10", "ruby"),
                t("a", "1.9", "x86_64-linux"), t("a", "1.9", "java"), t("a", "1.9", "ruby"), t("A", "5", "ruby")));
        assertThat(sorted).containsExactly(t("A", "5", "ruby"), t("a", "1.9", "ruby"), t("a", "1.9", "java"),
                t("a", "1.9", "x86_64-linux"), t("a", "1.10", "ruby"), t("b", "1.0", "ruby"));
        assertThat(t("a", "1.0", null).platform()).isEqualTo("ruby");
        assertThat(t("a", "1.0", "x86_64-linux").fullName()).isEqualTo("a-1.0-x86_64-linux");
    }

    @Test
    void skipsElementsThatAreNotTuples() throws IOException {
        List<Object> mixed = new ArrayList<>();
        mixed.add(List.of("ok", Ruby.gemVersion("1.0"), "ruby"));
        mixed.add("not a tuple");
        mixed.add(List.of("short"));
        mixed.add(List.of(42L, Ruby.gemVersion("1.0"), "ruby"));
        mixed.add(List.of("noversion", "x", "ruby"));
        mixed.add(List.of("objplat", Ruby.gemVersion("2.0"),
                new Ruby.RObject("Gem::Platform", orderedIvars("@cpu", "x86_64", "@os", "linux", "@version", null))));
        byte[] gz = gzipped(dev.granary.format.rubygems.marshal.MarshalWriter.dump(mixed));
        assertThat(SpecsIndex.read(new ByteArrayInputStream(gz)))
                .containsExactly(t("ok", "1.0", "ruby"), t("objplat", "2.0", "x86_64-linux"));
    }

    @Test
    void refusesCorruptIndexes() {
        assertThatThrownBy(() -> SpecsIndex.read(new ByteArrayInputStream("not gzip".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ZipException.class);
        assertThatThrownBy(() -> SpecsIndex.read(new ByteArrayInputStream(gzipped(new byte[] {4, 8, 'i', 0}))))
                .isInstanceOf(MarshalException.class);
        assertThatThrownBy(() -> SpecsIndex.read(new ByteArrayInputStream(gzipped(new byte[] {4, 9, '[', 0}))))
                .isInstanceOf(MarshalException.class);
        byte[] whole = SpecsIndex.gzip(List.of(t("a", "1.0", "ruby"), t("b", "1.0", "ruby")));
        byte[] raw = gunzipQuietly(whole);
        byte[] cut = java.util.Arrays.copyOf(raw, raw.length - 5);
        assertThatThrownBy(() -> SpecsIndex.read(new ByteArrayInputStream(gzipped(cut))))
                .isInstanceOfAny(MarshalException.class, java.io.EOFException.class);
    }

    @Test
    void mergesOntoAnIndexWrittenByRuby() throws IOException {
        byte[] base = resource("ruby-specs.4.8.gz");
        List<SpecTuple> others = List.of(t("alpha", "2.0", "ruby"), t("alpha", "3.0", "ruby"),
                t("delta", "3.0", "java"), t("epsilon", "1.0", "ruby"), t("gamma", "0.2", "x86_64-linux"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int appended = SpecsIndex.mergeOnto(() -> new ByteArrayInputStream(base), others, out);

        assertThat(appended).isEqualTo(3);
        List<SpecTuple> merged = SpecsIndex.read(new ByteArrayInputStream(out.toByteArray()));
        assertThat(merged).hasSize(12);
        assertThat(merged.subList(0, 9)).isEqualTo(SpecsIndex.read(new ByteArrayInputStream(base)));
        assertThat(merged.subList(9, 12)).containsExactly(t("alpha", "3.0", "ruby"), t("epsilon", "1.0", "ruby"),
                t("gamma", "0.2", "x86_64-linux"));

        // the appended tail links back into Ruby's part ("alpha", "x86_64-linux") with the right object numbers
        byte[] raw = gunzip(out.toByteArray());
        List<?> loaded = (List<?>) MarshalReader.load(raw);
        assertThat(loaded).hasSize(12);
        for (int i = 0; i < loaded.size(); i++) {
            assertThat(SpecTuple.fromMarshal(loaded.get(i))).as("element " + i).isEqualTo(merged.get(i));
        }
        int baseRawLength = gunzip(base).length;
        assertThat(java.util.Arrays.copyOfRange(raw, 4, baseRawLength))
                .as("Ruby's elements are copied byte for byte")
                .isEqualTo(java.util.Arrays.copyOfRange(gunzip(base), 4, baseRawLength));
    }

    @Test
    void mergeWithNothingNewCopiesTheBase() throws IOException {
        byte[] base = resource("ruby-specs.4.8.gz");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(SpecsIndex.mergeOnto(() -> new ByteArrayInputStream(base), List.of(t("beta", "1.1", "ruby")), out))
                .isZero();
        assertThat(gunzip(out.toByteArray())).isEqualTo(gunzip(base));
    }

    @Test
    void mergesOntoItsOwnOutputRepeatedly() throws IOException {
        byte[] current = SpecsIndex.gzip(List.of(t("a", "1.0", "ruby")));
        for (int i = 2; i < 40; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] base = current;
            SpecsIndex.mergeOnto(() -> new ByteArrayInputStream(base),
                    List.of(t("a", i + ".0", "ruby"), t("b" + (i % 3), "1.0", i % 2 == 0 ? "ruby" : "java")), out);
            current = out.toByteArray();
        }
        List<SpecTuple> all = SpecsIndex.read(new ByteArrayInputStream(current));
        assertThat(all).hasSize(39 + 6);
        List<?> loaded = (List<?>) MarshalReader.load(gunzip(current));
        for (int i = 0; i < loaded.size(); i++) {
            assertThat(SpecTuple.fromMarshal(loaded.get(i))).isEqualTo(all.get(i));
        }
    }

    // --- compact index: info ------------------------------------------------------------------------

    @Test
    void writesInfoLinesLikeRubygemsOrg() {
        GemRecord plain = record("rake", "13.4.2", "ruby", Instant.parse("2026-04-16T04:47:46.123Z"));
        plain = new GemRecord(plain.name(), plain.version(), plain.platform(), plain.sha256(), plain.size(),
                plain.dependencies(), ">= 2.3", ">= 0", null, null, null, null, null, null, null, plain.createdAt(),
                false, null, null);
        assertThat(CompactIndex.infoLine(plain)).isEqualTo("13.4.2 |checksum:" + SHA + ",ruby:>= 2.3,created_at:2026-04-16T04:47:46Z");

        GemRecord deps = new GemRecord("nokogiri", "1.16.0", "x86_64-linux", SHA, 1, List.of(
                new GemRecord.Dependency("racc", "~> 1.4", "runtime"),
                new GemRecord.Dependency("rake", ">= 13", "development"),
                new GemRecord.Dependency("mini_portile2", "< 3, >= 2.8.2", "runtime")),
                ">= 3.0, < 3.4.dev", ">= 3.3.22", null, null, null, null, null, null, null, null, false, null, null);
        assertThat(CompactIndex.infoLine(deps)).isEqualTo("1.16.0-x86_64-linux mini_portile2:< 3&>= 2.8.2,racc:~> 1.4"
                + "|checksum:" + SHA + ",ruby:< 3.4.dev&>= 3.0,rubygems:>= 3.3.22");
    }

    @Test
    void mergesInfoDocumentsFirstDocumentFirst() {
        String hosted = "---\n1.0 |checksum:aa\n2.0 dep:>= 1|checksum:bb\n";
        String proxy = "---\n0.9 |checksum:00\n1.0 |checksum:cc\n2.0-java |checksum:dd\n";
        assertThat(CompactIndex.mergeInfo(List.of(hosted, proxy)))
                .isEqualTo("---\n1.0 |checksum:aa\n2.0 dep:>= 1|checksum:bb\n0.9 |checksum:00\n2.0-java |checksum:dd\n");
        assertThat(CompactIndex.lineKey("2.0-java a:>= 1|checksum:x")).isEqualTo("2.0-java");
        assertThat(CompactIndex.lineKey("2.0|checksum:x")).isEqualTo("2.0");
        assertThat(CompactIndex.lineKey("2.0")).isEqualTo("2.0");
        assertThat(CompactIndex.lines("1.0 |x\n\n2.0 |y\n")).containsExactly("1.0 |x", "2.0 |y");
    }

    @Test
    void writesAndParsesNames() {
        String names = CompactIndex.names(List.of("rake", "acme_hello", "Zeta", "rake"));
        assertThat(names).isEqualTo("---\nZeta\nacme_hello\nrake\n");
        assertThat(CompactIndex.parseNames(names)).containsExactly("Zeta", "acme_hello", "rake");
        assertThat(CompactIndex.parseNames("---\n")).isEmpty();
    }

    // --- compact index: versions --------------------------------------------------------------------

    @Test
    void writesVersionsFiles() {
        String doc = CompactIndex.versions(Instant.parse("2026-10-10T12:00:00.5Z"), List.of(
                entry("acme_hello", "md5a", "0.1.0", "0.2.0"), entry("empty", "md5e"), entry("rake", "md5r", "13.4.2")));
        assertThat(doc).isEqualTo("created_at: 2026-10-10T12:00:00Z\n---\nacme_hello 0.1.0,0.2.0 md5a\nrake 13.4.2 md5r\n");
    }

    @Test
    void parsesVersionsFilesWithAppendsAndYanks() throws IOException {
        String doc = "created_at: 2024-04-01T00:00:05Z\n---\n"
                + "rake 0.8.7,0.9.0 aaa\n"
                + "nokogiri 1.0,1.1-java bbb\n"
                + "rake 0.9.1 ccc\n"
                + "rake -0.9.0 ddd\r\n"
                + "nokogiri  1.2   eee\n"
                + "\n"
                + "broken\n";
        Map<String, CompactIndex.Entry> parsed = CompactIndex.parseVersions(stream(doc));
        assertThat(parsed.keySet()).containsExactly("rake", "nokogiri");
        assertThat(parsed.get("rake").versions()).containsExactly("0.8.7", "0.9.1");
        assertThat(parsed.get("rake").md5()).isEqualTo("ddd");
        assertThat(parsed.get("nokogiri").versions()).containsExactly("1.0", "1.1-java", "1.2");
        assertThat(parsed.get("nokogiri").md5()).isEqualTo("eee");
    }

    @Test
    void mergesVersionsByAppendingToTheBase() throws IOException {
        String base = "created_at: 2024-04-01T00:00:05Z\n---\nrake 13.4.1 r1\nrake 13.4.2 r2\nzeta 1.0 z1";
        Map<String, CompactIndex.Entry> others = new LinkedHashMap<>();
        others.put("acme_hello", entry("acme_hello", "a1", "0.1.0"));
        others.put("rake", entry("rake", "h1", "13.4.2", "99.0"));
        others.put("zeta", entry("zeta", "z9", "1.0"));
        others.put("same", entry("same", "s1"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompactIndex.mergeVersions(stream(base), others, name -> switch (name) {
            case "rake" -> "merged-rake";
            case "zeta" -> "merged-zeta";
            default -> null;
        }, out);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(base + "\n"
                + "acme_hello 0.1.0 a1\n"
                + "rake 99.0 merged-rake\n"
                + "zeta 1.0 merged-zeta\n");

        Map<String, CompactIndex.Entry> reparsed = CompactIndex.parseVersions(stream(out.toString(StandardCharsets.UTF_8)));
        assertThat(reparsed.get("rake").versions()).containsExactly("13.4.1", "13.4.2", "99.0");
        assertThat(reparsed.get("rake").md5()).isEqualTo("merged-rake");
        assertThat(reparsed.get("zeta").versions()).containsExactly("1.0");
        assertThat(reparsed.get("zeta").md5()).isEqualTo("merged-zeta");
    }

    @Test
    void mergeLeavesTheBaseAloneWhenNothingChanges() throws IOException {
        String base = "created_at: 2024-04-01T00:00:05Z\n---\nrake 13.4.2 r2\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompactIndex.mergeVersions(stream(base), Map.of("rake", entry("rake", "x", "13.4.2")), name -> null, out);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(base);
    }

    @Test
    void unitesMembersAndAsksForMergedChecksums() {
        Map<String, CompactIndex.Entry> hosted = Map.of("acme", entry("acme", "h", "1.0"), "rake", entry("rake", "hr", "99"));
        Map<String, CompactIndex.Entry> other = new LinkedHashMap<>();
        other.put("rake", entry("rake", "pr", "13.4.2"));
        other.put("gone", entry("gone", "g"));
        Map<String, CompactIndex.Entry> union = CompactIndex.union(List.of(hosted, other),
                name -> name.equals("rake") ? "merged" : "unexpected");
        assertThat(union.keySet()).containsExactlyInAnyOrder("acme", "rake");
        assertThat(union.get("acme").md5()).isEqualTo("h");
        assertThat(union.get("rake").versions()).containsExactly("99", "13.4.2");
        assertThat(union.get("rake").md5()).isEqualTo("merged");
    }

    @Test
    void hashesLikeRubygemsOrg() {
        assertThat(CompactIndex.md5("---\n")).isEqualTo("6105347ebb9825ac754615ca55ff3b0c");
        assertThat(CompactIndex.md5("")).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
    }

    // --- hosted documents ---------------------------------------------------------------------------

    @Test
    void hostedDocumentsLeaveYankedVersionsOut() {
        GemRecord a1 = record("acme", "1.0", "ruby", T0);
        GemRecord a2 = record("acme", "2.0", "ruby", T0.plusSeconds(60)).withYanked(true, T0.plusSeconds(120));
        GemRecord b1 = record("beta", "0.1", "java", T0.plusSeconds(30));
        String info = RubygemsHosted.infoDocument(List.of(a1, a2));
        assertThat(info).isEqualTo("---\n1.0 |checksum:" + SHA + ",created_at:2026-10-01T10:00:00Z\n");
        String versions = RubygemsHosted.versionsDocument(List.of(a1, a2, b1));
        assertThat(versions).isEqualTo("created_at: 2026-10-01T10:02:00Z\n---\n"
                + "acme 1.0 " + CompactIndex.md5(info) + "\n"
                + "beta 0.1-java " + CompactIndex.md5(RubygemsHosted.infoDocument(List.of(b1))) + "\n");
        String allYanked = RubygemsHosted.versionsDocument(List.of(a2));
        assertThat(allYanked).isEqualTo("created_at: 2026-10-01T10:02:00Z\n---\n");
    }

    // --- records ------------------------------------------------------------------------------------

    @Test
    void recordsRoundTripThroughJson() {
        GemRecord r = new GemRecord("acme", "1.0.0.rc1", "x86_64-linux", SHA, 4096,
                List.of(new GemRecord.Dependency("rake", ">= 12.0", "runtime"), new GemRecord.Dependency("rspec", "~> 3", "development")),
                ">= 2.7", ">= 3.0", "Summary", "Description ünïcödé", List.of("A", "B"), List.of("MIT", "Apache-2.0"),
                "https://acme.example", Map.of("source_code_uri", "https://acme.example/src"), "3.5.22", T0, true,
                T0.plusSeconds(5), "alice");
        GemRecord back = GemRecord.fromJson(r.toJson());
        assertThat(back).isEqualTo(r);
        assertThat(back.fullName()).isEqualTo("acme-1.0.0.rc1-x86_64-linux");
        assertThat(back.versionKey()).isEqualTo("1.0.0.rc1-x86_64-linux");
        assertThat(back.prerelease()).isTrue();
        assertThat(back.dependencies().get(1).runtime()).isFalse();
        assertThat(back.withYanked(false, T0).yankedAt()).isNull();
    }

    @Test
    void recordsFromJsonTolerateMissingFieldsAndRefuseGarbage() {
        GemRecord minimal = GemRecord.fromJson("{\"name\":\"x\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(minimal.platform()).isEqualTo("ruby");
        assertThat(minimal.requiredRubyVersion()).isEqualTo(">= 0");
        assertThat(minimal.createdAt()).isNull();
        GemRecord badDate = GemRecord.fromJson("{\"name\":\"x\",\"version\":\"1\",\"createdAt\":\"yesterday\"}"
                .getBytes(StandardCharsets.UTF_8));
        assertThat(badDate.createdAt()).isNull();
        for (String garbage : List.of("", "[]", "{\"name\":\"x\"}", "{not json", "null")) {
            assertThatThrownBy(() -> GemRecord.fromJson(garbage.getBytes(StandardCharsets.UTF_8)))
                    .as(garbage).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static CompactIndex.Entry entry(String name, String md5, String... versions) {
        return new CompactIndex.Entry(name, new LinkedHashSet<>(List.of(versions)), md5);
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> orderedIvars(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static byte[] gzipped(byte[] raw) {
        return RubygemsTestSupport.gzip(raw);
    }

    private static byte[] gunzip(byte[] gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        }
    }

    private static byte[] gunzipQuietly(byte[] gz) {
        try {
            return gunzip(gz);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static int occurrences(byte[] haystack, String needle) {
        String text = new String(haystack, StandardCharsets.ISO_8859_1);
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
