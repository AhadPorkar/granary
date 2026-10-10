package dev.granary.format.terraform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class Sha256SumsTest {

    private static final String DIGEST_A = "7b9c7b16f118fbc2b05a983817b8ce2f86df125857966ad356353baf4bff5c0a";
    private static final String DIGEST_B = "C43723E8CC65BCDF5E0C92581DCBBDCBDCF18B8D2037406A5F2033B1E22DE442";

    @Test
    void parsesTheSha256sumFormat() {
        Sha256Sums sums = Sha256Sums.parse(DIGEST_A + "  terraform-provider-null_3.2.4_darwin_amd64.zip\n"
                + DIGEST_B + " *terraform-provider-null_3.2.4_darwin_arm64.zip\r\n");
        assertThat(sums.entries()).hasSize(2);
        assertThat(sums.digestOf("terraform-provider-null_3.2.4_darwin_amd64.zip")).contains(DIGEST_A);
        assertThat(sums.digestOf("terraform-provider-null_3.2.4_darwin_arm64.zip")).contains(DIGEST_B.toLowerCase());
        assertThat(sums.digestOf("other.zip")).isEmpty();
        assertThat(sums.malformedLines()).isZero();
        assertThat(sums.isEmpty()).isFalse();
    }

    @Test
    void toleratesBlankLinesCommentsAndOddWhitespace() {
        Sha256Sums sums = Sha256Sums.parse("\n# generated\n   " + DIGEST_A + "   a.zip   \n\n");
        assertThat(sums.entries()).containsExactly(new Sha256Sums.Entry(DIGEST_A, "a.zip"));
    }

    @Test
    void countsMalformedLinesWithoutFailing() {
        Sha256Sums sums = Sha256Sums.parse("notadigest  a.zip\n" + DIGEST_A + "\n" + DIGEST_A + "  ../escape.zip\n"
                + DIGEST_A + "  sub/dir.zip\n" + DIGEST_A + "  ok.zip\n");
        assertThat(sums.entries()).extracting(Sha256Sums.Entry::filename).containsExactly("ok.zip");
        assertThat(sums.malformedLines()).isEqualTo(4);
    }

    @Test
    void emptyAndNullInputs() {
        assertThat(Sha256Sums.parse("").isEmpty()).isTrue();
        assertThat(Sha256Sums.parse(null).isEmpty()).isTrue();
        assertThat(Sha256Sums.parse("   \n\n").entries()).isEmpty();
    }

    @Test
    void firstEntryWinsForDuplicateNamesAndUnicodeNamesSurvive() {
        Sha256Sums sums = Sha256Sums.parse(DIGEST_A + "  dup.zip\n" + DIGEST_B + "  dup.zip\n" + DIGEST_A + "  \u2603\u2602.zip\n");
        assertThat(sums.digestOf("dup.zip")).contains(DIGEST_A);
        assertThat(sums.digestOf("\u2603\u2602.zip")).contains(DIGEST_A);
    }

    @Test
    void rendersCanonically() {
        String text = Sha256Sums.render(List.of(new Sha256Sums.Entry(DIGEST_A, "a.zip"), new Sha256Sums.Entry(DIGEST_A, "b.zip")));
        assertThat(text).isEqualTo(DIGEST_A + "  a.zip\n" + DIGEST_A + "  b.zip\n");
        assertThat(Sha256Sums.parse(text).entries()).hasSize(2);
    }
}
