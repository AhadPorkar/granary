package dev.granary.format.cocoapods;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VersionFileTest {

    /** https://cdn.cocoapods.org/CocoaPods-version.yml, byte for byte. */
    private static final String CDN = "---\nmin: 1.0.0\nlast: 1.16.1\nprefix_lengths:\n- 1\n- 1\n- 1\n";

    @Test
    void parsesTheCdnFile() {
        VersionFile file = VersionFile.parse(CDN).orElseThrow();
        assertThat(file.min()).isEqualTo("1.0.0");
        assertThat(file.max()).isNull();
        assertThat(file.last()).isEqualTo("1.16.1");
        assertThat(file.prefixLengths()).containsExactly(1, 1, 1);
        assertThat(file.layout()).isEqualTo(ShardLayout.STANDARD);
    }

    @Test
    void theStandardFileLooksLikeTheCdnOne() {
        assertThat(VersionFile.standard().render()).isEqualTo("---\nmin: 1.0.0\nlast: 1.16.2\nprefix_lengths:\n- 1\n- 1\n- 1\n");
        assertThat(new String(VersionFile.standard().toBytes(), StandardCharsets.UTF_8)).isEqualTo(VersionFile.standard().render());
        assertThat(VersionFile.parse(VersionFile.standard().render())).contains(VersionFile.standard());
    }

    @Test
    void readsFlowListsQuotesCommentsCrlfAndIndentedItems() {
        String yaml = "# spec repo metadata\r\nmin: '1.8.0'\r\nmax: \"2.0.0\" # inclusive\r\nlast: 1.16.2\r\nprefix_lengths: [2, 1]\r\n";
        VersionFile file = VersionFile.parse(yaml).orElseThrow();
        assertThat(file.min()).isEqualTo("1.8.0");
        assertThat(file.max()).isEqualTo("2.0.0");
        assertThat(file.prefixLengths()).containsExactly(2, 1);
        assertThat(file.layout().prefixLengths()).containsExactly(2, 1);

        VersionFile indented = VersionFile.parse("last: 1.16.2\nprefix_lengths:\n  - 1\n  - 2\n").orElseThrow();
        assertThat(indented.prefixLengths()).containsExactly(1, 2);
    }

    @Test
    void readsTheJsonForm() {
        VersionFile file = VersionFile.parse("{\"min\": \"1.0.0\", \"last\": \"1.16.2\", \"prefix_lengths\": [1,1,1]}").orElseThrow();
        assertThat(file.last()).isEqualTo("1.16.2");
        assertThat(file.layout()).isEqualTo(ShardLayout.STANDARD);
        assertThat(VersionFile.parse("{\"last\": 1.16}").orElseThrow().last()).isEqualTo("1.16");
        assertThat(VersionFile.parse("{not json")).isEmpty();
        assertThat(VersionFile.parse("[1, 2]")).isEmpty();
    }

    @Test
    void noPrefixLengthsMeansAnUnshardedRepository() {
        VersionFile file = VersionFile.parse("min: 1.0.0\nlast: 1.16.2\n").orElseThrow();
        assertThat(file.prefixLengths()).isNull();
        assertThat(file.layout().prefixLengths()).isEmpty();
        assertThat(file.render()).doesNotContain("prefix_lengths");
    }

    @Test
    void unusablePrefixLengthsAreIgnored() {
        assertThat(VersionFile.parse("last: 1.0\nprefix_lengths: [1, x]\n").orElseThrow().prefixLengths()).isNull();
        assertThat(VersionFile.parse("last: 1.0\nprefix_lengths: [0]\n").orElseThrow().prefixLengths()).isNull();
        assertThat(VersionFile.parse("last: 1.0\nprefix_lengths: [99]\n").orElseThrow().prefixLengths()).isNull();
        // each entry valid but together longer than a digest
        VersionFile tooLong = new VersionFile(null, null, "1.0", List.of(20, 20));
        assertThat(tooLong.layout()).isEqualTo(ShardLayout.STANDARD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "---\n", "just text", "- 1\n- 2\n", ": no key\n"})
    void documentsThatAreNotAMappingAreRefused(String yaml) {
        assertThat(VersionFile.parse(yaml)).isEmpty();
    }

    @Test
    void nullIsNotADocument() {
        assertThat(VersionFile.parse(null)).isEmpty();
    }

    @Test
    void nullValuesAndUnknownKeysAreTolerated() {
        VersionFile file = VersionFile.parse("min: ~\nmax: null\nlast: 1.16.2\nlast_compatible_versions:\n- 0.32.1\nfoo:\n  bar: 1\n").orElseThrow();
        assertThat(file.min()).isNull();
        assertThat(file.max()).isNull();
        assertThat(file.last()).isEqualTo("1.16.2");
        assertThat(file.prefixLengths()).isNull();
    }
}
