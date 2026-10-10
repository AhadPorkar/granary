package dev.granary.format.pub;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.pub.PubPath.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PubPathTest {

    @ParameterizedTest
    @CsvSource({
            "api/packages/path,                                PACKAGE,         path,         ''",
            "api/packages/path/,                               PACKAGE,         path,         ''",
            "api/packages/flutter_bloc/versions/8.1.3,         VERSION,         flutter_bloc, 8.1.3",
            "api/packages/foo/versions/1.0.0+1,                VERSION,         foo,          1.0.0+1",
            "api/packages/foo/advisories,                      ADVISORIES,      foo,          ''",
            "api/packages/foo/options,                         PACKAGE_OPTIONS, foo,          ''",
            "api/packages/foo/versions/2.0.0-dev.1/options,    VERSION_OPTIONS, foo,          2.0.0-dev.1",
            "api/packages/foo/versions/1.0.0/retract,          RETRACT,         foo,          1.0.0",
            "packages/foo/versions/1.0.0.tar.gz,               ARCHIVE,         foo,          1.0.0",
            "packages/_private/versions/0.0.1-a.b+c.tar.gz,    ARCHIVE,         _private,     0.0.1-a.b+c",
            "api/packages/versions,                            PACKAGE,         versions,     ''",
            "api/packages/versions/versions/1.0.0,             VERSION,         versions,     1.0.0",
    })
    void classifiesEndpoints(String raw, Kind kind, String name, String version) {
        PubPath path = PubPath.parse(raw);
        assertThat(path.kind()).isEqualTo(kind);
        assertThat(path.name()).isEqualTo(name);
        assertThat(path.version()).isEqualTo(version.isEmpty() ? null : version);
    }

    @Test
    void publishingEndpointsWinOverAPackageCalledVersions() {
        assertThat(PubPath.parse("api/packages/versions/new").kind()).isEqualTo(Kind.PUBLISH_NEW);
        assertThat(PubPath.parse("api/packages/versions/newUpload").kind()).isEqualTo(Kind.PUBLISH_UPLOAD);
        assertThat(PubPath.parse("api/packages/versions/newUploadFinish").kind()).isEqualTo(Kind.PUBLISH_FINISH);
        assertThat(PubPath.parse("api/packages/versions/newUploadFinish/").kind()).isEqualTo(Kind.PUBLISH_FINISH);
    }

    @Test
    void rootIsTheEmptyPath() {
        assertThat(PubPath.parse("").kind()).isEqualTo(Kind.ROOT);
        assertThat(PubPath.parse(null).kind()).isEqualTo(Kind.ROOT);
        assertThat(PubPath.parse("/").kind()).isEqualTo(Kind.ROOT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "api/packages/Not-A-Name", "api/packages/1abc", "api/packages/foo.bar", "api/packages/ü",
            "api/packages/foo/versions/1.0", "api/packages/foo/versions/latest", "api/packages/foo/versions/..",
            "packages/foo/versions/1.0.tar.gz", "packages/foo bar/versions/1.0.0.tar.gz",
            "packages/foo/versions/.tar.gz", "api/packages//versions/1.0.0", "api/packages/foo/versions/1.0.0%2F1",
            "api/packages/foo/versions/1.0.0\\x"})
    void malformedNamesAndVersionsAreInvalid(String raw) {
        assertThat(PubPath.parse(raw).kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void overlongNamesAreInvalid() {
        assertThat(PubPath.parse("api/packages/" + "a".repeat(64)).kind()).isEqualTo(Kind.PACKAGE);
        assertThat(PubPath.parse("api/packages/" + "a".repeat(65)).kind()).isEqualTo(Kind.INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "api", "api/packages", "api/packages/foo/bar", "api/packages/foo/versions", "api/packages/foo/versions/1.0.0/x",
            "api/packages/foo/versions/1.0.0/retract/x", "api/packages/foo/x/1.0.0", "api/packages/foo/x/1.0.0/retract",
            "packages/foo/versions/1.0.0.zip", "packages/foo/versions/1.0.0", "packages/foo/1.0.0.tar.gz",
            "packages/foo/versions/a/1.0.0.tar.gz", "api/archives/foo-1.0.0.tar.gz", "index.html", "simple/foo/",
            "API/packages/foo"})
    void otherPathsAreUnknown(String raw) {
        assertThat(PubPath.parse(raw).kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void anEncodedSlashInTheRequestUriMakesThePathInvalid() {
        // the server decodes %2F before the handler sees the path; the raw URI tells the two apart
        String decoded = "api/packages/foo/versions/1.0.0";
        assertThat(PubPath.parse(decoded, "/repository/pub/api/packages/foo/versions/1.0.0").kind()).isEqualTo(Kind.VERSION);
        assertThat(PubPath.parse(decoded, "/repository/pub/api/packages/foo%2Fversions%2F1.0.0").kind()).isEqualTo(Kind.INVALID);
        assertThat(PubPath.parse(decoded, "/repository/pub/api/packages/foo%2fversions/1.0.0").kind()).isEqualTo(Kind.INVALID);
        assertThat(PubPath.parse("packages/foo/versions/1.0.0.tar.gz", "/repository/pub/packages/foo/versions%2F1.0.0.tar.gz")
                .kind()).isEqualTo(Kind.INVALID);
        // other escapes are fine: a + in a version may arrive encoded
        assertThat(PubPath.parse("packages/foo/versions/1.0.0+1.tar.gz", "/repository/pub/packages/foo/versions/1.0.0%2B1.tar.gz")
                .kind()).isEqualTo(Kind.ARCHIVE);
        assertThat(PubPath.parse(decoded, null).kind()).isEqualTo(Kind.VERSION);
    }

    @Test
    void onlyArchivesAreImmutable() {
        assertThat(PubPath.parse("packages/foo/versions/1.0.0.tar.gz").isMetadata()).isFalse();
        assertThat(PubPath.parse("api/packages/foo").isMetadata()).isTrue();
        assertThat(PubPath.parse("api/packages/foo/versions/1.0.0").isMetadata()).isTrue();
        assertThat(PubPath.parse("api/packages/foo/advisories").isMetadata()).isTrue();
        assertThat(PubPath.parse("nonsense").isMetadata()).isFalse();
    }

    @Test
    void storagePathsMirrorTheUrlLayout() {
        assertThat(PubPath.listingPath("foo")).isEqualTo("api/packages/foo");
        assertThat(PubPath.advisoriesPath("foo")).isEqualTo("api/packages/foo/advisories");
        assertThat(PubPath.archivePrefix("foo")).isEqualTo("packages/foo/versions/");
        assertThat(PubPath.archivePath("foo", "1.0.0+1")).isEqualTo("packages/foo/versions/1.0.0+1.tar.gz");
        // the archive prefix of one package is never the prefix of another
        assertThat(PubPath.archivePath("foo_bar", "1.0.0")).doesNotStartWith(PubPath.archivePrefix("foo"));
    }
}
