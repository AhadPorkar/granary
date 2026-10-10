package dev.granary.format.swift;

import static dev.granary.format.swift.SwiftTestSupport.MAPPER;
import static dev.granary.format.swift.SwiftTestSupport.METADATA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.node.ObjectNode;

class SwiftMetadataTest {

    private static ObjectNode parse(String json) {
        return SwiftMetadata.parse(MAPPER, json == null ? null : json.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInvalid(String json, String detail) {
        assertThatThrownBy(() -> parse(json)).isInstanceOfSatisfying(SwiftProblem.class, p -> {
            assertThat(p.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(p.code()).isEqualTo("swift.metadata.invalid");
            assertThat(p.detail()).contains(detail);
        });
    }

    @Test
    void keepsAValidDocumentAsItIs() {
        ObjectNode metadata = parse(METADATA);
        assertThat(metadata.path("description").stringValue()).isEqualTo("A demo package");
        assertThat(metadata.path("author").path("organization").path("name").stringValue()).isEqualTo("Acme");
        assertThat(SwiftMetadata.repositoryUrls(metadata))
                .containsExactly("https://github.com/acme/demo", "git@github.com:acme/demo.git");
    }

    @Test
    void missingOrBlankMetadataIsAnEmptyObject() {
        assertThat(parse(null).isEmpty()).isTrue();
        assertThat(parse("  \n").isEmpty()).isTrue();
        assertThat(parse("{}").isEmpty()).isTrue();
    }

    @Test
    void unknownMembersAreKept() {
        assertThat(parse("{\"keywords\":[\"a\"],\"x\":{\"y\":1}}").path("x").path("y").intValue()).isEqualTo(1);
    }

    @Test
    void nullMembersAreAllowed() {
        assertThat(parse("{\"author\":null,\"description\":null,\"repositoryURLs\":null,\"originalPublicationTime\":null}")).isNotNull();
    }

    @Test
    void rejectsInvalidJsonAndNonObjects() {
        assertInvalid("{", "invalid JSON provided for release metadata");
        assertInvalid("[]", "must be a JSON object");
        assertInvalid("\"text\"", "must be a JSON object");
    }

    @Test
    void checksTheTypesSwiftPmDecodes() {
        assertInvalid("{\"author\":\"Jane\"}", "author must be an object");
        assertInvalid("{\"author\":{}}", "author.name is required");
        assertInvalid("{\"author\":{\"name\":1}}", "author.name is required");
        assertInvalid("{\"author\":{\"name\":\"J\",\"email\":5}}", "author.email must be a string");
        assertInvalid("{\"author\":{\"name\":\"J\",\"organization\":\"Acme\"}}", "author.organization must be an object");
        assertInvalid("{\"author\":{\"name\":\"J\",\"organization\":{}}}", "author.organization.name is required");
        assertInvalid("{\"author\":{\"name\":\"J\",\"organization\":{\"name\":\"A\",\"url\":[]}}}", "author.organization.url must be a string");
        assertInvalid("{\"description\":1}", "description must be a string");
        assertInvalid("{\"licenseURL\":true}", "licenseURL must be a string");
        assertInvalid("{\"readmeURL\":{}}", "readmeURL must be a string");
        assertInvalid("{\"repositoryURLs\":\"https://x\"}", "repositoryURLs must be an array of strings");
        assertInvalid("{\"repositoryURLs\":[1]}", "repositoryURLs must be an array of strings");
        assertInvalid("{\"originalPublicationTime\":1700000000}", "originalPublicationTime must be an ISO 8601");
        assertInvalid("{\"originalPublicationTime\":\"yesterday\"}", "'yesterday' is not an ISO 8601 date-time");
        assertInvalid("{\"originalPublicationTime\":\"2024-01-31T12:00:00\"}", "is not an ISO 8601 date-time");
    }

    @Test
    void publicationTimeBecomesWholeSecondsInUtc() {
        assertThat(parse("{\"originalPublicationTime\":\"2024-01-31T14:00:00.123+02:00\"}").path("originalPublicationTime").stringValue())
                .isEqualTo("2024-01-31T12:00:00Z");
        assertThat(SwiftMetadata.normaliseTime("2024-01-31T12:00:00Z")).contains("2024-01-31T12:00:00Z");
        assertThat(SwiftMetadata.normaliseTime("x".repeat(65))).isEmpty();
        assertThat(SwiftMetadata.normaliseTime(" ")).isEmpty();
        assertThat(SwiftMetadata.timestamp(Instant.parse("2025-05-01T10:11:12.999Z"))).isEqualTo("2025-05-01T10:11:12Z");
    }

    @Test
    void repositoryUrlsSkipsJunk() {
        assertThat(SwiftMetadata.repositoryUrls(parse("{\"repositoryURLs\":[\" https://a \",\"\"]}"))).containsExactly("https://a");
        assertThat(SwiftMetadata.repositoryUrls(null)).isEmpty();
        assertThat(SwiftMetadata.repositoryUrls(MAPPER.createObjectNode())).isEmpty();
    }
}
