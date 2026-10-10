package dev.granary.format.composer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ComposerRewriterTest {

    private static final String BASE = "https://granary.test/repository/php";

    @Test
    void hostedDistUrlsBecomeAbsolute() {
        P2Document doc = doc("acme/lib", entry("1.0.0", "1.0.0.0", "{\"type\":\"zip\",\"url\":\"dist/acme/lib/1.0.0/abc.zip\",\"reference\":\"abc\"}"));

        P2Document out = ComposerRewriter.hosted(doc, BASE);

        assertThat(out.versions().getFirst().get("dist").get("url").stringValue()).isEqualTo(BASE + "/dist/acme/lib/1.0.0/abc.zip");
        assertThat(doc.versions().getFirst().get("dist").get("url").stringValue()).isEqualTo("dist/acme/lib/1.0.0/abc.zip");
    }

    @Test
    void proxiedDistUrlsPointAtTheRepository() {
        P2Document doc = P2Document.parse(ComposerTestSupport.JSON, ComposerTestSupport.resource("psr-log.json"), "psr/log", null).orElseThrow();

        P2Document out = ComposerRewriter.proxied(doc, BASE, true);

        ObjectNode latest = out.versions().getFirst();
        assertThat(latest.get("dist").get("url").stringValue())
                .isEqualTo(BASE + "/dist/psr/log/3.0.2/f16e1d5863e37f8d8c2a01719f5b34baa2b714d3.zip");
        assertThat(latest.get("dist").get("reference").stringValue()).isEqualTo("f16e1d5863e37f8d8c2a01719f5b34baa2b714d3");
        assertThat(latest.get("source").get("url").stringValue()).isEqualTo("https://github.com/php-fig/log.git");
        assertThat(out.versions()).allSatisfy(e -> assertThat(e.get("dist").get("url").stringValue()).startsWith(BASE + "/dist/psr/log/"));
    }

    @Test
    void distRewriteCanBeTurnedOff() {
        P2Document doc = doc("acme/lib", entry("1.0.0", "1.0.0.0", "{\"type\":\"zip\",\"url\":\"https://codeload.example/a.zip\",\"reference\":\"abc\"}"));
        doc.versions().getFirst().put("notification-url", "https://packagist.org/downloads/");

        P2Document out = ComposerRewriter.proxied(doc, BASE, false);

        assertThat(out.versions().getFirst().get("dist").get("url").stringValue()).isEqualTo("https://codeload.example/a.zip");
        assertThat(out.versions().getFirst().has("notification-url")).isFalse();
    }

    @Test
    void mirrorsAreDroppedWithTheOriginalUrl() {
        P2Document doc = doc("acme/lib", entry("1.0.0", "1.0.0.0",
                "{\"type\":\"zip\",\"url\":\"https://x/a.zip\",\"reference\":\"abc\",\"mirrors\":[{\"url\":\"https://m/%package%\",\"preferred\":true}]}"));
        ObjectNode dist = (ObjectNode) ComposerRewriter.proxied(doc, BASE, true).versions().getFirst().get("dist");
        assertThat(dist.has("mirrors")).isFalse();
        assertThat(dist.get("url").stringValue()).isEqualTo(BASE + "/dist/acme/lib/1.0.0/abc.zip");
    }

    @Test
    void versionsThatCannotBePathsKeepTheirOriginalUrl() {
        P2Document doc = doc("acme/lib", entry("dev-100%", "dev-100%", "{\"type\":\"zip\",\"url\":\"https://x/a.zip\",\"reference\":\"abc\"}"),
                entry("1.0.0", "1.0.0.0", "{\"type\":\"path\",\"url\":\"../lib\"}"),
                entry("dev-fix;quote", "dev-fix;quote", "{\"type\":\"zip\",\"url\":\"https://x/b.zip\",\"reference\":\"def\"}"));
        P2Document out = ComposerRewriter.proxied(doc, BASE, true);
        assertThat(out.versions().get(0).get("dist").get("url").stringValue()).isEqualTo("https://x/a.zip");
        assertThat(out.versions().get(1).get("dist").get("url").stringValue()).isEqualTo("../lib");
        assertThat(out.versions().get(2).get("dist").get("url").stringValue()).isEqualTo("https://x/b.zip");
    }

    @Test
    void branchVersionsKeepTheirSlashes() {
        P2Document doc = doc("acme/lib", entry("dev-feature/login", "dev-feature/login",
                "{\"type\":\"zip\",\"url\":\"https://x/a.zip\",\"reference\":\"abc\"}"));
        assertThat(ComposerRewriter.proxied(doc, BASE, true).versions().getFirst().get("dist").get("url").stringValue())
                .isEqualTo(BASE + "/dist/acme/lib/dev-feature/login/abc.zip");
    }

    @Test
    void mergeLetsTheFirstMemberWinPerVersion() {
        P2Document privateDoc = doc("acme/lib", entry("1.0.0", "1.0.0.0", "{\"url\":\"private\"}"),
                entry("1.5.0", "1.5.0.0", "{\"url\":\"private\"}")).withLastModified(Instant.parse("2026-01-01T00:00:00Z"));
        P2Document publicDoc = doc("acme/lib", entry("v1.0.0", "1.0.0.0", "{\"url\":\"public\"}"),
                entry("2.0.0", "2.0.0.0", "{\"url\":\"public\"}")).withLastModified(Instant.parse("2026-02-01T00:00:00Z"));

        P2Document merged = ComposerRewriter.merge("acme/lib", List.of(privateDoc, publicDoc));

        assertThat(merged.versions()).extracting(P2Document::version).containsExactly("2.0.0", "1.5.0", "1.0.0");
        assertThat(merged.versions().get(2).get("dist").get("url").stringValue()).isEqualTo("private");
        assertThat(merged.lastModified()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    void mergeCombinesSecurityAdvisoriesOnce() {
        P2Document a = new P2Document("acme/lib", List.of(), ComposerTestSupport.object(
                "{\"security-advisories\":[{\"advisoryId\":\"PKSA-1\"},{\"advisoryId\":\"PKSA-2\"}]}"), null);
        P2Document b = new P2Document("acme/lib", List.of(), ComposerTestSupport.object(
                "{\"security-advisories\":[{\"advisoryId\":\"PKSA-2\"},{\"advisoryId\":\"PKSA-3\"}],\"other\":1}"), null);

        P2Document merged = ComposerRewriter.merge("acme/lib", List.of(a, b));

        assertThat(merged.extras().get("security-advisories")).hasSize(3);
        assertThat(merged.extras().get("other").intValue()).isEqualTo(1);
    }

    @Test
    void lastModifiedCoversTheRepositoryConfiguration() {
        Instant doc = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(ComposerRewriter.latest(doc, null)).isEqualTo(doc);
        assertThat(ComposerRewriter.latest(null, doc)).isEqualTo(doc);
        assertThat(ComposerRewriter.latest(doc, doc.plusSeconds(1))).isEqualTo(doc.plusSeconds(1));
        assertThat(ComposerRewriter.latest(null, null)).isNull();
    }

    private static P2Document doc(String name, ObjectNode... entries) {
        return new P2Document(name, List.of(entries), ComposerTestSupport.object("{}"), null);
    }

    private static ObjectNode entry(String version, String normalized, String dist) {
        return ComposerTestSupport.object("{\"name\":\"acme/lib\",\"version\":\"" + version + "\",\"version_normalized\":\""
                + normalized + "\",\"dist\":" + dist + "}");
    }
}
