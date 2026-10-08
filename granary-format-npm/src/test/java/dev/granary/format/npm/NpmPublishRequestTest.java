package dev.granary.format.npm;

import static dev.granary.format.npm.NpmTestSupport.MAPPER;
import static dev.granary.format.npm.NpmTestSupport.bytes;
import static dev.granary.format.npm.NpmTestSupport.publishDocument;
import static dev.granary.format.npm.NpmTestSupport.tarball;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.node.ObjectNode;

class NpmPublishRequestTest {

    @Test
    void parsesVersionsTagsAndDecodesAttachments() {
        byte[] tgz = tarball("demo-1.0.0");
        NpmPublishRequest request = NpmPublishRequest.parse(MAPPER, bytes(publishDocument("demo", "1.0.0", tgz, "latest")));
        assertThat(request.name()).isEqualTo("demo");
        assertThat(request.hasAttachments()).isTrue();
        assertThat(request.attachments()).containsOnlyKeys("demo-1.0.0.tgz");
        assertThat(request.attachments().get("demo-1.0.0.tgz")).isEqualTo(tgz);
        assertThat(NpmPackument.versionNames(request.document())).containsExactly("1.0.0");
        assertThat(request.versions().has("1.0.0")).isTrue();
        assertThat(request.distTags().path("latest").stringValue()).isEqualTo("1.0.0");
        assertThat(request.document().has("_attachments")).isFalse();
    }

    @Test
    void scopedAttachmentKeysAreKeptVerbatim() {
        NpmPublishRequest request = NpmPublishRequest.parse(MAPPER,
                bytes(publishDocument("@acme/ui", "1.0.0", tarball("x"), "latest")));
        assertThat(request.attachments()).containsOnlyKeys("@acme/ui-1.0.0.tgz");
    }

    @Test
    void fallsBackToTheIdWhenNameIsMissing() {
        NpmPublishRequest request = NpmPublishRequest.parse(MAPPER, "{\"_id\":\"demo\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(request.name()).isEqualTo("demo");
        assertThat(request.hasAttachments()).isFalse();
        assertThat(request.versions().isEmpty()).isTrue();
    }

    @Test
    void rejectsDocumentsWithoutAName() {
        assertThatThrownBy(() -> NpmPublishRequest.parse(MAPPER, "{\"versions\":{}}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NpmError.class)
                .extracting(e -> ((NpmError) e).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsMalformedBodies() {
        assertThatThrownBy(() -> NpmPublishRequest.parse(MAPPER, "{not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NpmError.class).hasMessageContaining("malformed");
        assertThatThrownBy(() -> NpmPublishRequest.parse(MAPPER, new byte[0]))
                .isInstanceOf(NpmError.class).hasMessageContaining("JSON object");
        assertThatThrownBy(() -> NpmPublishRequest.parse(MAPPER, "[1,2]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NpmError.class).hasMessageContaining("JSON object");
    }

    @Test
    void rejectsInvalidPackageNames() {
        assertThatThrownBy(() -> NpmPublishRequest.parse(MAPPER, "{\"name\":\"../x\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NpmError.class).hasMessageContaining("invalid package name");
    }

    @Test
    void rejectsAttachmentsWithoutUsableData() {
        ObjectNode doc = publishDocument("demo", "1.0.0", tarball("x"), "latest");
        ((ObjectNode) doc.path("_attachments").path("demo-1.0.0.tgz")).remove("data");
        assertThatThrownBy(() -> NpmPublishRequest.parse(MAPPER, bytes(doc)))
                .isInstanceOf(NpmError.class).hasMessageContaining("has no data");

        ObjectNode bad = publishDocument("demo", "1.0.0", tarball("x"), "latest");
        ((ObjectNode) bad.path("_attachments").path("demo-1.0.0.tgz")).put("data", "***not base64***");
        assertThatThrownBy(() -> NpmPublishRequest.parse(MAPPER, bytes(bad)))
                .isInstanceOf(NpmError.class).hasMessageContaining("base64");
    }
}
