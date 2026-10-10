package dev.granary.format.composer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ComposerDistTest {

    @Test
    void namesGithubZipballsAfterTheirCommit() {
        assertThat(ComposerDist.fileName(ComposerTestSupport.object("""
                {"version":"3.0.2","dist":{"type":"zip","url":"https://api.github.com/repos/php-fig/log/zipball/f16e1d5863e37f8d8c2a01719f5b34baa2b714d3",
                "reference":"f16e1d5863e37f8d8c2a01719f5b34baa2b714d3","shasum":""}}
                """))).contains("f16e1d5863e37f8d8c2a01719f5b34baa2b714d3.zip");
    }

    @Test
    void keepsTheExtensionOfTheOriginalUrl() {
        assertThat(ComposerDist.fileName(ComposerTestSupport.object("""
                {"dist":{"type":"tar","url":"https://example.com/acme-lib-1.0.0.tar.gz","reference":"v1.0.0"}}
                """))).contains("v1.0.0.tar.gz");
        assertThat(ComposerDist.fileName(ComposerTestSupport.object("""
                {"dist":{"type":"phar","url":"https://example.com/tool.phar"}}
                """))).hasValueSatisfying(name -> assertThat(name).matches("[0-9a-f]{40}\\.phar"));
        assertThat(ComposerDist.extension("https://gitlab.com/api/v4/projects/1/repository/archive.zip?sha=abc", "zip")).isEqualTo("zip");
        assertThat(ComposerDist.extension("https://example.com/download", "gzip")).isEqualTo("gz");
        assertThat(ComposerDist.extension("https://example.com/download", "xz")).isEqualTo("xz");
        assertThat(ComposerDist.extension("https://example.com/.zip", "tar")).isEqualTo("tar");
    }

    @Test
    void usesADigestOfTheUrlForUnusableReferences() {
        String withoutReference = ComposerDist.fileName(ComposerTestSupport.object("""
                {"dist":{"type":"zip","url":"https://example.com/a.zip"}}
                """)).orElseThrow();
        String unsafeReference = ComposerDist.fileName(ComposerTestSupport.object("""
                {"dist":{"type":"zip","url":"https://example.com/a.zip","reference":"../../etc/passwd"}}
                """)).orElseThrow();
        assertThat(withoutReference).isEqualTo(ComposerTestSupport.sha1("https://example.com/a.zip".getBytes()) + ".zip");
        assertThat(unsafeReference).isEqualTo(withoutReference);
    }

    @Test
    void leavesNonArchivesAndMissingDistsAlone() {
        assertThat(ComposerDist.fileName(ComposerTestSupport.object("{\"dist\":{\"type\":\"path\",\"url\":\"../lib\"}}"))).isEmpty();
        assertThat(ComposerDist.fileName(ComposerTestSupport.object("{\"dist\":{\"type\":\"file\",\"url\":\"https://x/y\"}}"))).isEmpty();
        assertThat(ComposerDist.fileName(ComposerTestSupport.object("{\"dist\":{\"type\":\"zip\",\"url\":\"\"}}"))).isEmpty();
        assertThat(ComposerDist.fileName(ComposerTestSupport.object("{\"dist\":null}"))).isEmpty();
        assertThat(ComposerDist.fileName(ComposerTestSupport.object("{\"source\":{\"type\":\"git\"}}"))).isEmpty();
        // no type means zip, as Composer assumes
        assertThat(ComposerDist.fileName(ComposerTestSupport.object("{\"dist\":{\"url\":\"https://x/y.zip\",\"reference\":\"abc\"}}")))
                .contains("abc.zip");
    }

    @Test
    void decidesWhichVersionsCanBecomePathSegments() {
        assertThat(ComposerDist.isPathSafe("1.0.0")).isTrue();
        assertThat(ComposerDist.isPathSafe("dev-feature/login")).isTrue();
        assertThat(ComposerDist.isPathSafe("1.0.0+build.5")).isTrue();
        assertThat(ComposerDist.isPathSafe("dev-a b")).isTrue();
        assertThat(ComposerDist.isPathSafe("dev-x/../y")).isFalse();
        assertThat(ComposerDist.isPathSafe("dev-x//y")).isFalse();
        assertThat(ComposerDist.isPathSafe("dev-x/")).isFalse();
        assertThat(ComposerDist.isPathSafe("dev-100%")).isFalse();
        assertThat(ComposerDist.isPathSafe("dev-a?b")).isFalse();
        assertThat(ComposerDist.isPathSafe("dev-a#b")).isFalse();
        assertThat(ComposerDist.isPathSafe("dev-a\\b")).isFalse();
        // a path parameter: the HTTP firewall rejects ';' and '%3B', so the archive URL would be unreachable
        assertThat(ComposerDist.isPathSafe("dev-foo;bar")).isFalse();
        assertThat(ComposerDist.isPathSafe("")).isFalse();
        assertThat(ComposerDist.isPathSafe(null)).isFalse();
        assertThat(ComposerDist.isPathSafe("x".repeat(256))).isFalse();
    }

    @Test
    void buildsEncodedUrls() {
        assertThat(ComposerDist.url("https://granary.test/repository/php/", "acme/lib", "dev-feature/a b", "abc.zip"))
                .isEqualTo("https://granary.test/repository/php/dist/acme/lib/dev-feature/a%20b/abc.zip");
        assertThat(ComposerDist.absolute("http://h/repository/php", "dist/acme/lib/1.0.0/abc.zip"))
                .isEqualTo("http://h/repository/php/dist/acme/lib/1.0.0/abc.zip");
        assertThat(ComposerDist.isAbsolute("HTTPS://x/y")).isTrue();
        assertThat(ComposerDist.isAbsolute("dist/acme/lib/1.0.0/abc.zip")).isFalse();
    }
}
