package dev.granary.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

class AssetSanitiserTest {

    @Test
    void keepsOrdinaryContentTypesUnchanged() {
        assertThat(AssetSanitiser.contentType("application/java-archive", "a.jar")).isEqualTo("application/java-archive");
        assertThat(AssetSanitiser.contentType("text/plain; charset=utf-8", "a.txt")).isEqualTo("text/plain; charset=utf-8");
        assertThat(AssetSanitiser.contentType("application/vnd.oci.image.manifest.v1+json", "m"))
                .isEqualTo("application/vnd.oci.image.manifest.v1+json");
        assertThat(AssetSanitiser.contentType("  text/html  ", "x")).isEqualTo("text/html");
    }

    @Test
    void missingOrBlankContentTypeIsGuessedFromThePath() {
        assertThat(AssetSanitiser.contentType(null, "lib/a-1.0.jar")).isEqualTo("application/java-archive");
        assertThat(AssetSanitiser.contentType("", "index.json")).isEqualTo("application/json");
        assertThat(AssetSanitiser.contentType("   ", "noextension")).isEqualTo(ContentTypes.OCTET_STREAM);
        assertThat(AssetSanitiser.contentType(null, null)).isEqualTo(ContentTypes.OCTET_STREAM);
    }

    @Test
    void overlongContentTypeLosesItsParametersFirst() {
        String value = "application/json; profile=\"" + "x".repeat(300) + "\"";

        assertThat(AssetSanitiser.contentType(value, "file.bin")).isEqualTo("application/json");
    }

    @Test
    void overlongBareContentTypeFallsBackToTheGuess() {
        String value = "application/" + "x".repeat(300);

        assertThat(AssetSanitiser.contentType(value, "lib.tgz")).isEqualTo("application/gzip");
        assertThat(AssetSanitiser.contentType(value, "unknown.thing")).isEqualTo(ContentTypes.OCTET_STREAM);
    }

    @Test
    void exactlyTheColumnSizeIsKept() {
        String value = "application/" + "y".repeat(AssetSanitiser.MAX_CONTENT_TYPE_LENGTH - "application/".length());
        assertThat(value).hasSize(AssetSanitiser.MAX_CONTENT_TYPE_LENGTH);

        assertThat(AssetSanitiser.contentType(value, "f")).isEqualTo(value);
        assertThat(AssetSanitiser.contentType(value + "y", "f")).isEqualTo(ContentTypes.OCTET_STREAM);
    }

    @Test
    void controlCharactersAreRemoved() {
        assertThat(AssetSanitiser.contentType("text/\u0000pla\rin\n", "f")).isEqualTo("text/plain");
        assertThat(AssetSanitiser.contentType("\u0007application/zip\u007f", "f")).isEqualTo("application/zip");
        assertThat(AssetSanitiser.contentType("application/zip\u0085", "f")).isEqualTo("application/zip");
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "text/", "/plain", "*/*", "text/*", "a b/c", "text/plain;;=", "tëxt/plain",
            "application/zip; name=\"unterminated", "\u0000\u0001"})
    void implausibleValuesNeverReachTheDatabase(String value) {
        String sanitised = AssetSanitiser.contentType(value, "archive.zip");

        assertThat(sanitised).hasSizeLessThanOrEqualTo(AssetSanitiser.MAX_CONTENT_TYPE_LENGTH);
        assertThat(AssetSanitiser.isPlausibleContentType(sanitised)).isTrue();
        // whatever comes out must be usable as a response header later
        assertThat(MediaType.parseMediaType(sanitised).isConcrete()).isTrue();
    }

    @Test
    void brokenParametersAreDroppedButTheTypeIsKept() {
        assertThat(AssetSanitiser.contentType("application/zip; name=\"unterminated", "f")).isEqualTo("application/zip");
        assertThat(AssetSanitiser.contentType("text/plain; charset=é", "f")).isEqualTo("text/plain");
        assertThat(AssetSanitiser.contentType("*/*", "x.json")).isEqualTo("application/json");
    }

    @Test
    void etagsThatFitAreKeptVerbatim() {
        assertThat(AssetSanitiser.etag("\"abc\"")).isEqualTo("\"abc\"");
        assertThat(AssetSanitiser.etag("W/\"weak\"")).isEqualTo("W/\"weak\"");
        String max = "\"" + "e".repeat(AssetSanitiser.MAX_ETAG_LENGTH - 2) + "\"";
        assertThat(AssetSanitiser.etag(max)).isEqualTo(max);
    }

    @Test
    void oversizedEtagIsDroppedNotTruncated() {
        String tooLong = "\"" + "e".repeat(AssetSanitiser.MAX_ETAG_LENGTH) + "\"";

        assertThat(AssetSanitiser.etag(tooLong)).isNull();
    }

    @Test
    void lastModifiedFollowsTheSameRule() {
        assertThat(AssetSanitiser.lastModified("Wed, 01 Jan 2026 00:00:00 GMT")).isEqualTo("Wed, 01 Jan 2026 00:00:00 GMT");
        assertThat(AssetSanitiser.lastModified("x".repeat(AssetSanitiser.MAX_LAST_MODIFIED_LENGTH)))
                .hasSize(AssetSanitiser.MAX_LAST_MODIFIED_LENGTH);
        assertThat(AssetSanitiser.lastModified("x".repeat(AssetSanitiser.MAX_LAST_MODIFIED_LENGTH + 1))).isNull();
    }

    @Test
    void validatorsThatCannotBeSentBackAreDropped() {
        assertThat(AssetSanitiser.etag(null)).isNull();
        assertThat(AssetSanitiser.etag("")).isNull();
        assertThat(AssetSanitiser.etag("   ")).isNull();
        assertThat(AssetSanitiser.etag("\"a\r\nInjected: yes\"")).isNull();
        assertThat(AssetSanitiser.etag("\"a\u0000\"")).isNull();
        assertThat(AssetSanitiser.etag("\"中\"")).isNull();
        assertThat(AssetSanitiser.lastModified("Wed,\u007f 01 Jan")).isNull();
        // a tab and Latin-1 are legal in a header value
        assertThat(AssetSanitiser.etag("\"a\tbé\"")).isEqualTo("\"a\tbé\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "plain/ascii.txt", "é", "ü/ß", "中文/包", "\uD83D\uDE00", "a\uD83D\uDE00b中é", "\u07FF\u0800\uFFFF"})
    void utf8LengthMatchesTheEncoder(String value) {
        assertThat(AssetSanitiser.utf8Length(value))
                .isEqualTo(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
}
