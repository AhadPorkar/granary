package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StoredContent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class RawResponsesTest {

    private final FakeStorage storage = new FakeStorage();
    private final Repository repo = RawTestSupport.hosted("raw-hosted", "raw");

    @Test
    void attachmentDispositionQuotesAsciiAndEncodesUnicode() {
        assertThat(RawResponses.attachmentDisposition("app-1.2.3.zip")).isEqualTo("attachment; filename=\"app-1.2.3.zip\"");
        assertThat(RawResponses.attachmentDisposition("we\"ird\\name.txt")).isEqualTo("attachment; filename=\"we_ird_name.txt\"");
        assertThat(RawResponses.attachmentDisposition("日本 語.zip"))
                .isEqualTo("attachment; filename=\"__ _.zip\"; filename*=UTF-8''%E6%97%A5%E6%9C%AC%20%E8%AA%9E.zip");
    }

    @Test
    void malformedMediaTypesFallBackToOctetStream() {
        assertThat(RawResponses.mediaType("text/plain; charset=utf-8")).isEqualTo(MediaType.parseMediaType("text/plain;charset=utf-8"));
        assertThat(RawResponses.mediaType("not a type")).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(RawResponses.mediaType("")).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(RawResponses.mediaType(null)).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(RawResponses.isValidMediaType("application/json")).isTrue();
        assertThat(RawResponses.isValidMediaType("garbage")).isFalse();
    }

    @Test
    void streamHonoursConditionalRequestsHeadAndDisposition() {
        StoredContent content = storage.put(repo, "dir/file.txt", "hello");
        content.asset().setContentType("nonsense type");
        RepositoryRequest get = RawTestSupport.request(repo, "GET", "dir/file.txt");
        ResponseEntity<?> response = RawResponses.stream(get, content, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(5);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"" + content.asset().getBlobSha256() + "\"");
        assertThat(response.getHeaders().getFirst("X-Checksum-Sha256")).isEqualTo(content.asset().getBlobSha256());
        assertThat(response.getHeaders().getCacheControl()).contains("max-age=31536000");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).isEqualTo("inline; filename=\"file.txt\"");
        assertThat(RawTestSupport.body(response)).isEqualTo("hello");

        ResponseEntity<?> attachment = RawResponses.stream(get, content, true);
        assertThat(attachment.getHeaders().getFirst("Content-Disposition")).isEqualTo("attachment; filename=\"file.txt\"");

        RepositoryRequest conditional = RawTestSupport.request(repo, "GET", "dir/file.txt", null, null,
                Map.of("If-None-Match", "W/\"x\", \"" + content.asset().getBlobSha256() + "\""));
        assertThat(RawResponses.stream(conditional, content, false).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        RepositoryRequest head = RawTestSupport.request(repo, "HEAD", "dir/file.txt");
        ResponseEntity<?> headResponse = RawResponses.stream(head, content, false);
        assertThat(headResponse.getBody()).isNull();
        assertThat(headResponse.getHeaders().getContentLength()).isEqualTo(5);

        content.asset().setMetadata(true);
        assertThat(RawResponses.stream(get, content, false).getHeaders().getCacheControl()).isEqualTo("no-cache");
    }

    @Test
    void generatedDocumentsHaveDigestEtagsAndSupport304() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        RepositoryRequest get = RawTestSupport.request(repo, "GET", "all-packages");
        ResponseEntity<?> response = RawResponses.document(get, body, "application/json", true);
        String etag = response.getHeaders().getETag();
        assertThat(etag).matches("\"[0-9a-f]{64}\"");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
        assertThat(RawTestSupport.body(response)).isEqualTo("{\"a\":1}");

        RepositoryRequest conditional = RawTestSupport.request(repo, "GET", "all-packages", null, null, Map.of("If-None-Match", etag));
        assertThat(RawResponses.document(conditional, body, "application/json", true).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        RepositoryRequest head = RawTestSupport.request(repo, "HEAD", "all-packages");
        assertThat(RawResponses.document(head, body, "application/json", false).getBody()).isNull();
        assertThat(RawResponses.document(head, body, "application/json", false).getHeaders().getCacheControl()).contains("max-age");
    }

    @Test
    void createdAndErrorsAreJson() {
        StoredContent content = storage.put(repo, "f", "x");
        ResponseEntity<?> created = RawResponses.created("f", content);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(RawTestSupport.json(created).path("sha256").asString()).isEqualTo(content.asset().getBlobSha256());
        assertThat(RawTestSupport.json(created).path("size").asLong()).isEqualTo(1);
        assertThat(RawTestSupport.json(RawResponses.conflict("f")).path("code").asString()).isEqualTo("repository.raw.exists");
        assertThat(RawResponses.conflict("f").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(RawResponses.methodNotAllowed("PATCH").getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(RawResponses.badRequest("x", "y").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void pagesABrowserWouldRenderAreSandboxed() {
        for (String type : java.util.List.of("text/html", "text/html;charset=UTF-8", "application/xhtml+xml", "image/svg+xml",
                "application/xml", "text/xml", "application/rss+xml")) {
            StoredContent page = storage.put(repo, "site/page", "<html><script>steal()</script></html>".getBytes(StandardCharsets.UTF_8), type, false);
            ResponseEntity<?> response = RawResponses.stream(RawTestSupport.request(repo, "GET", "site/page"), page, false);
            assertThat(response.getHeaders().getFirst("Content-Security-Policy")).as(type)
                    .startsWith("sandbox ").doesNotContain("allow-same-origin");
        }
        for (String type : java.util.List.of("text/javascript", "application/json", "application/zip", "text/css", "image/png",
                "application/octet-stream", "text/plain")) {
            StoredContent file = storage.put(repo, "site/file", "x".getBytes(StandardCharsets.UTF_8), type, false);
            ResponseEntity<?> response = RawResponses.stream(RawTestSupport.request(repo, "GET", "site/file"), file, false);
            assertThat(response.getHeaders().getFirst("Content-Security-Policy")).as(type).isNull();
        }
        ResponseEntity<?> listing = RawResponses.document(RawTestSupport.request(repo, "GET", "site/"),
                "<html></html>".getBytes(StandardCharsets.UTF_8), "text/html;charset=UTF-8", true);
        assertThat(listing.getHeaders().getFirst("Content-Security-Policy")).startsWith("sandbox");
    }

    @Test
    void ifNoneMatchStarMatchesAnyExistingFile() {
        StoredContent content = storage.put(repo, "dir/file.txt", "hello");
        RepositoryRequest star = RawTestSupport.request(repo, "GET", "dir/file.txt", null, null, Map.of("If-None-Match", "*"));
        assertThat(RawResponses.stream(star, content, false).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(RawResponses.document(star, new byte[] {1}, "application/json", true).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        RepositoryRequest other = RawTestSupport.request(repo, "GET", "dir/file.txt", null, null, Map.of("If-None-Match", "\"other\""));
        assertThat(RawResponses.stream(other, content, false).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void methodNotAllowedNamesTheAllowedMethods() {
        assertThat(RawResponses.methodNotAllowed("PATCH").getHeaders().getFirst("Allow")).isEqualTo("GET, HEAD, PUT, POST, DELETE");
        assertThat(RawResponses.methodNotAllowed("PUT", RawResponses.ALLOW_READ_POST).getHeaders().getFirst("Allow")).isEqualTo("GET, HEAD, POST");
        assertThat(RawTestSupport.json(RawResponses.methodNotAllowed("PATCH")).path("code").asString()).isEqualTo("method.notAllowed");
    }
}
