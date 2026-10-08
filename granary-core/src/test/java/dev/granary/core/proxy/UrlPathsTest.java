package dev.granary.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlPathsTest {

    @Test
    void joinsBaseAndPathWithExactlyOneSlash() {
        assertThat(UrlPaths.join("https://repo1.maven.org/maven2/", "junit/junit/maven-metadata.xml"))
                .isEqualTo("https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml");
        assertThat(UrlPaths.join("https://repo1.maven.org/maven2", "junit/junit/maven-metadata.xml"))
                .isEqualTo("https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml");
    }

    @Test
    void emptyPathYieldsBaseWithTrailingSlash() {
        assertThat(UrlPaths.join("https://registry.npmjs.org", "")).isEqualTo("https://registry.npmjs.org/");
        assertThat(UrlPaths.join("https://registry.npmjs.org/", null)).isEqualTo("https://registry.npmjs.org/");
    }

    @Test
    void keepsScopedNpmNamesAndPlusSignsIntact() {
        assertThat(UrlPaths.join("https://registry.npmjs.org/", "@types/node/-/node-22.0.0.tgz"))
                .isEqualTo("https://registry.npmjs.org/@types/node/-/node-22.0.0.tgz");
        assertThat(UrlPaths.encodeSegment("lib-1.0+build.jar")).isEqualTo("lib-1.0+build.jar");
    }

    @Test
    void percentEncodesSpacesAndNonAscii() {
        assertThat(UrlPaths.encodeSegment("a b")).isEqualTo("a%20b");
        assertThat(UrlPaths.encodeSegment("ü")).isEqualTo("%C3%BC");
        assertThat(UrlPaths.encodeSegment("50%")).isEqualTo("50%25");
        assertThat(UrlPaths.encodeSegment("a?b#c")).isEqualTo("a%3Fb%23c");
    }

    @Test
    void preservesEmptySegmentsSoDoubleSlashesAreNotSilentlyCollapsed() {
        assertThat(UrlPaths.join("http://h", "a//b")).isEqualTo("http://h/a//b");
    }
}
