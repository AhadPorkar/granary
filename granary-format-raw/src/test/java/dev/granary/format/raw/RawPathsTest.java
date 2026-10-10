package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class RawPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "a/b.txt", "dir/", "deep/er/path/file.tar.gz", "ü/ñ.txt", "a b/c d.txt",
            "@scope/pkg@1.0.0/file.js", "src/contrib/PACKAGES", "x.y.z", "a-b_c.d", "日本語/ファイル.zip", "..a/b..", "a./.b"})
    void acceptsOrdinaryPaths(String path) {
        assertThat(RawPaths.validate(path)).isEqualTo(path);
    }

    @Test
    void theRootIsTheEmptyPath() {
        assertThat(RawPaths.validate(null)).isEmpty();
        assertThat(RawPaths.validate("")).isEmpty();
        assertThat(RawPaths.isDirectory("")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "a/../b", "../a", "a/..", ".", "./a", "a/./b", "a\\b", "\\", "a\u0000b", "a\u001fb",
            "a\u007fb", "/a", "a//b", "//", "\n", "a/\tb"})
    void refusesTraversalBackslashesControlCharactersAndEmptySegments(String path) {
        assertThatThrownBy(() -> RawPaths.validate(path)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesOverlongPaths() {
        String path = "a/".repeat(600);
        assertThatThrownBy(() -> RawPaths.validate(path)).hasMessageContaining("longer than");
        assertThat(RawPaths.validate("b/".repeat(500) + "x")).hasSize(1001);
    }

    @Test
    void directoryDetectionAndSplitting() {
        assertThat(RawPaths.isDirectory("dir/")).isTrue();
        assertThat(RawPaths.isDirectory("dir/file")).isFalse();
        assertThat(RawPaths.fileName("dir/sub/file.txt")).isEqualTo("file.txt");
        assertThat(RawPaths.fileName("file.txt")).isEqualTo("file.txt");
        assertThat(RawPaths.directory("dir/sub/file.txt")).isEqualTo("dir/sub/");
        assertThat(RawPaths.directory("file.txt")).isEmpty();
        assertThat(RawPaths.segments("a/b//c/")).containsExactly("a", "b", "c");
        assertThat(RawPaths.segments("")).isEmpty();
        assertThat(RawPaths.withoutTrailingSlash("a/b//")).isEqualTo("a/b");
        assertThat(RawPaths.join("", "f")).isEqualTo("f");
        assertThat(RawPaths.join("d/", "f")).isEqualTo("d/f");
        assertThat(RawPaths.join("d", "f")).isEqualTo("d/f");
    }

    @Test
    void encodedSeparatorsAreDetectedInTheRawUri() {
        assertThat(RawPaths.hasEncodedSeparator("/repository/r/a%2Fb.txt")).isTrue();
        assertThat(RawPaths.hasEncodedSeparator("/repository/r/a%2fb.txt")).isTrue();
        assertThat(RawPaths.hasEncodedSeparator("/repository/r/a%5Cb.txt")).isTrue();
        assertThat(RawPaths.hasEncodedSeparator("/repository/r/a%5cb.txt")).isTrue();
        assertThat(RawPaths.hasEncodedSeparator("/repository/r/a/b%20c%3F.txt")).isFalse();
        assertThat(RawPaths.hasEncodedSeparator("/repository/r/%40scope/pkg@1.0.0/x.js")).isFalse();
        assertThat(RawPaths.hasEncodedSeparator(null)).isFalse();
    }
}
