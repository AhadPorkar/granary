package dev.granary.format.docker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DockerPathTest {

    private static final String DIGEST = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void ping() {
        assertThat(DockerPath.parse("v2").kind()).isEqualTo(DockerPath.Kind.PING);
        assertThat(DockerPath.parse("v2/").kind()).isEqualTo(DockerPath.Kind.PING);
        assertThat(DockerPath.parse("/v2/").kind()).isEqualTo(DockerPath.Kind.PING);
        assertThat(DockerPath.parse("").kind()).isEqualTo(DockerPath.Kind.UNKNOWN);
        assertThat(DockerPath.parse("v1/").kind()).isEqualTo(DockerPath.Kind.UNKNOWN);
        assertThat(DockerPath.parse("v2x").kind()).isEqualTo(DockerPath.Kind.UNKNOWN);
    }

    @Test
    void catalog() {
        assertThat(DockerPath.parse("v2/_catalog").kind()).isEqualTo(DockerPath.Kind.CATALOG);
        assertThat(DockerPath.parse("v2/_catalog/").kind()).isEqualTo(DockerPath.Kind.CATALOG);
    }

    @Test
    void manifestsWithNestedNames() {
        DockerPath p = DockerPath.parse("v2/acme/team/app/manifests/1.0");
        assertThat(p.kind()).isEqualTo(DockerPath.Kind.MANIFEST);
        assertThat(p.name()).isEqualTo("acme/team/app");
        assertThat(p.reference()).isEqualTo("1.0");
        assertThat(p.referenceIsDigest()).isFalse();

        DockerPath d = DockerPath.parse("v2/library/alpine/manifests/" + DIGEST);
        assertThat(d.name()).isEqualTo("library/alpine");
        assertThat(d.reference()).isEqualTo(DIGEST);
        assertThat(d.referenceIsDigest()).isTrue();
    }

    @Test
    void blobsAndUploads() {
        DockerPath blob = DockerPath.parse("v2/alpine/blobs/" + DIGEST);
        assertThat(blob.kind()).isEqualTo(DockerPath.Kind.BLOB);
        assertThat(blob.name()).isEqualTo("alpine");
        assertThat(blob.reference()).isEqualTo(DIGEST);

        DockerPath start = DockerPath.parse("v2/acme/app/blobs/uploads/");
        assertThat(start.kind()).isEqualTo(DockerPath.Kind.UPLOAD_START);
        assertThat(start.name()).isEqualTo("acme/app");

        DockerPath upload = DockerPath.parse("v2/acme/app/blobs/uploads/1f2e3d4c-aaaa-bbbb-cccc-000000000000");
        assertThat(upload.kind()).isEqualTo(DockerPath.Kind.UPLOAD);
        assertThat(upload.name()).isEqualTo("acme/app");
        assertThat(upload.uuid()).isEqualTo("1f2e3d4c-aaaa-bbbb-cccc-000000000000");
    }

    @Test
    void tagsAndReferrers() {
        DockerPath tags = DockerPath.parse("v2/acme/app/tags/list");
        assertThat(tags.kind()).isEqualTo(DockerPath.Kind.TAGS);
        assertThat(tags.name()).isEqualTo("acme/app");

        DockerPath referrers = DockerPath.parse("v2/acme/app/referrers/" + DIGEST);
        assertThat(referrers.kind()).isEqualTo(DockerPath.Kind.REFERRERS);
        assertThat(referrers.name()).isEqualTo("acme/app");
        assertThat(referrers.reference()).isEqualTo(DIGEST);
    }

    @Test
    void imageNamedLikeAMarkerStillParses() {
        // the LAST marker wins, so an image called "tags" or "blobs" is addressable
        DockerPath p = DockerPath.parse("v2/tags/list/manifests/latest");
        assertThat(p.kind()).isEqualTo(DockerPath.Kind.MANIFEST);
        assertThat(p.name()).isEqualTo("tags/list");
    }

    @Test
    void oddPathsAreUnknown() {
        assertThat(DockerPath.parse("v2/alpine").kind()).isEqualTo(DockerPath.Kind.UNKNOWN);
        assertThat(DockerPath.parse("v2/alpine/manifests").kind()).isEqualTo(DockerPath.Kind.UNKNOWN);
        assertThat(DockerPath.parse("v2/alpine//manifests/latest").kind()).isEqualTo(DockerPath.Kind.UNKNOWN);
        assertThat(DockerPath.parse("v2/../manifests/latest").kind()).isEqualTo(DockerPath.Kind.UNKNOWN);
        assertThat(DockerPath.parse("v2/./x/manifests/latest").kind()).isEqualTo(DockerPath.Kind.UNKNOWN);
        assertThat(DockerPath.parse("v2/alpine/blobs").kind()).isEqualTo(DockerPath.Kind.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alpine", "library/alpine", "acme/team/app", "a.b_c__d-e", "a--b", "a/b/c/d/e/f", "0123"})
    void validNames(String name) {
        assertThat(DockerPath.isValidName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Alpine", "-alpine", "alpine-", "a..b", "a___b", "a/", "/a", "a//b", "a b", "a/B", "a.", "a@b"})
    void invalidNames(String name) {
        assertThat(DockerPath.isValidName(name)).isFalse();
    }

    @Test
    void nameLengthIsLimited() {
        assertThat(DockerPath.isValidName("a".repeat(255))).isTrue();
        assertThat(DockerPath.isValidName("a".repeat(256))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "1.0", "v1.2.3-rc.1", "_x", "Tag_With.Mixed-Case"})
    void validTags(String tag) {
        assertThat(DockerPath.isValidTag(tag)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".hidden", "-x", "a/b", "a:b", "a b"})
    void invalidTags(String tag) {
        assertThat(DockerPath.isValidTag(tag)).isFalse();
    }

    @Test
    void tagLengthIsLimited() {
        assertThat(DockerPath.isValidTag("a".repeat(128))).isTrue();
        assertThat(DockerPath.isValidTag("a".repeat(129))).isFalse();
    }

    @Test
    void uploadIds() {
        assertThat(DockerPath.isValidUploadId("1f2e3d4c-aaaa-bbbb-cccc-000000000000")).isTrue();
        assertThat(DockerPath.isValidUploadId("..")).isFalse();
        assertThat(DockerPath.isValidUploadId("a/b")).isFalse();
        assertThat(DockerPath.isValidUploadId("")).isFalse();
        assertThat(DockerPath.isValidUploadId(null)).isFalse();
    }
}
