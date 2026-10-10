package dev.granary.format.docker;

import static dev.granary.format.docker.DockerTestSupport.body;
import static dev.granary.format.docker.DockerTestSupport.get;
import static dev.granary.format.docker.DockerTestSupport.header;
import static dev.granary.format.docker.DockerTestSupport.hosted;
import static dev.granary.format.docker.DockerTestSupport.request;
import static dev.granary.format.docker.DockerTestSupport.user;
import static dev.granary.format.docker.DockerTestSupport.withHeader;
import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StoredContent;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class DockerResponsesTest {

    private final FakeStorage storage = new FakeStorage();
    private final Repository repo = hosted("docker-hosted");
    private final byte[] data = "0123456789".getBytes(StandardCharsets.UTF_8);
    private final Digest digest = Digest.of(data);
    private final StoredContent blob = storage.put(repo, DockerLayout.blobPath(digest), data, MediaTypes.OCTET_STREAM, false);

    @Test
    void fullContent() {
        ResponseEntity<?> r = DockerResponses.content(get(repo, "v2/x/blobs/" + digest), blob, digest, MediaTypes.OCTET_STREAM, true);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(header(r, DockerResponses.API_VERSION_HEADER)).isEqualTo("registry/2.0");
        assertThat(header(r, DockerResponses.DIGEST_HEADER)).isEqualTo(digest.toString());
        assertThat(header(r, HttpHeaders.ETAG)).isEqualTo("\"" + digest + "\"");
        assertThat(header(r, HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(header(r, "X-Checksum-Sha256")).isEqualTo(digest.hex());
        assertThat(r.getHeaders().getContentLength()).isEqualTo(10);
        assertThat(r.getHeaders().getContentType().toString()).isEqualTo(MediaTypes.OCTET_STREAM);
        assertThat(r.getHeaders().getCacheControl()).contains("max-age");
        assertThat(r.getHeaders().getLastModified()).isPositive();
        assertThat(body(r)).isEqualTo("0123456789");
    }

    @Test
    void headHasHeadersButNoBody() {
        ResponseEntity<?> r = DockerResponses.content(request(repo, "HEAD", "v2/x/blobs/" + digest, null, user("u")),
                blob, digest, MediaTypes.OCTET_STREAM, true);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentLength()).isEqualTo(10);
        assertThat(r.getBody()).isNull();
    }

    @Test
    void ifNoneMatchByDigestOrHex() {
        RepositoryRequest byDigest = withHeader(get(repo, "v2/x/blobs/" + digest), HttpHeaders.IF_NONE_MATCH, "\"" + digest + "\"");
        ResponseEntity<?> r = DockerResponses.content(byDigest, blob, digest, MediaTypes.OCTET_STREAM, true);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(header(r, DockerResponses.DIGEST_HEADER)).isEqualTo(digest.toString());

        RepositoryRequest byHex = withHeader(get(repo, "v2/x/blobs/" + digest), HttpHeaders.IF_NONE_MATCH, "\"" + digest.hex() + "\"");
        assertThat(DockerResponses.content(byHex, blob, digest, MediaTypes.OCTET_STREAM, true).getStatusCode())
                .isEqualTo(HttpStatus.NOT_MODIFIED);

        RepositoryRequest weakInList = withHeader(get(repo, "v2/x/blobs/" + digest), HttpHeaders.IF_NONE_MATCH,
                "\"sha256:other\", W/\"" + digest + "\"");
        assertThat(DockerResponses.content(weakInList, blob, digest, MediaTypes.OCTET_STREAM, true).getStatusCode())
                .isEqualTo(HttpStatus.NOT_MODIFIED);
        RepositoryRequest any = withHeader(get(repo, "v2/x/blobs/" + digest), HttpHeaders.IF_NONE_MATCH, "*");
        assertThat(DockerResponses.content(any, blob, digest, MediaTypes.OCTET_STREAM, true).getStatusCode())
                .isEqualTo(HttpStatus.NOT_MODIFIED);

        RepositoryRequest other = withHeader(get(repo, "v2/x/blobs/" + digest), HttpHeaders.IF_NONE_MATCH, "\"sha256:other\"");
        assertThat(DockerResponses.content(other, blob, digest, MediaTypes.OCTET_STREAM, true).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void listValuedHeadersSentAsSeveralLinesAreJoined() {
        RepositoryRequest twoLines = withHeader(withHeader(get(repo, "v2/x/blobs/" + digest), HttpHeaders.IF_NONE_MATCH,
                "\"sha256:other\""), HttpHeaders.IF_NONE_MATCH, "\"" + digest + "\"");
        assertThat(DockerResponses.content(twoLines, blob, digest, MediaTypes.OCTET_STREAM, true).getStatusCode())
                .isEqualTo(HttpStatus.NOT_MODIFIED);

        RepositoryRequest accept = withHeader(withHeader(get(repo, "v2/x/manifests/1"), HttpHeaders.ACCEPT,
                MediaTypes.OCI_MANIFEST), HttpHeaders.ACCEPT, MediaTypes.OCI_INDEX);
        assertThat(DockerResponses.listHeader(accept, HttpHeaders.ACCEPT))
                .isEqualTo(MediaTypes.OCI_MANIFEST + ", " + MediaTypes.OCI_INDEX);
        assertThat(MediaTypes.variantKey(DockerResponses.listHeader(accept, HttpHeaders.ACCEPT))).isEmpty();
        assertThat(DockerResponses.listHeader(get(repo, "v2/x/manifests/1"), HttpHeaders.ACCEPT)).isNull();
    }

    @Test
    void rangeRequests() {
        ResponseEntity<?> middle = DockerResponses.content(withHeader(get(repo, "p"), HttpHeaders.RANGE, "bytes=2-5"),
                blob, digest, MediaTypes.OCTET_STREAM, true);
        assertThat(middle.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(header(middle, HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
        assertThat(middle.getHeaders().getContentLength()).isEqualTo(4);
        assertThat(body(middle)).isEqualTo("2345");

        ResponseEntity<?> open = DockerResponses.content(withHeader(get(repo, "p"), HttpHeaders.RANGE, "bytes=7-"),
                blob, digest, MediaTypes.OCTET_STREAM, true);
        assertThat(open.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(body(open)).isEqualTo("789");

        ResponseEntity<?> suffix = DockerResponses.content(withHeader(get(repo, "p"), HttpHeaders.RANGE, "bytes=-3"),
                blob, digest, MediaTypes.OCTET_STREAM, true);
        assertThat(header(suffix, HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 7-9/10");
        assertThat(body(suffix)).isEqualTo("789");

        ResponseEntity<?> beyond = DockerResponses.content(withHeader(get(repo, "p"), HttpHeaders.RANGE, "bytes=3-99"),
                blob, digest, MediaTypes.OCTET_STREAM, true);
        assertThat(header(beyond, HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 3-9/10");

        ResponseEntity<?> whole = DockerResponses.content(withHeader(get(repo, "p"), HttpHeaders.RANGE, "bytes=0-9"),
                blob, digest, MediaTypes.OCTET_STREAM, true);
        assertThat(whole.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unsatisfiableAndIgnoredRanges() {
        ResponseEntity<?> bad = DockerResponses.content(withHeader(get(repo, "p"), HttpHeaders.RANGE, "bytes=20-30"),
                blob, digest, MediaTypes.OCTET_STREAM, true);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(header(bad, HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */10");

        ResponseEntity<?> notRangeable = DockerResponses.content(withHeader(get(repo, "p"), HttpHeaders.RANGE, "bytes=2-5"),
                blob, digest, MediaTypes.OCI_MANIFEST, false);
        assertThat(notRangeable.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notRangeable.getHeaders().containsHeader(HttpHeaders.ACCEPT_RANGES)).isFalse();

        assertThat(DockerResponses.parseRange("bytes=1-2,4-5", 10)).isEmpty();
        assertThat(DockerResponses.parseRange("items=1-2", 10)).isEmpty();
        assertThat(DockerResponses.parseRange("bytes=x-y", 10)).isEmpty();
        assertThat(DockerResponses.parseRange("bytes=-", 10)).isEmpty();
        assertThat(DockerResponses.parseRange("bytes=5-2", 10)).contains(new long[] {-1, -1});
        assertThat(DockerResponses.parseRange("bytes=-0", 10)).contains(new long[] {-1, -1});
        assertThat(DockerResponses.parseRange("bytes=0-1", 0)).isEmpty();
    }

    @Test
    void metadataIsNotCached() {
        StoredContent tag = storage.put(repo, "tags/x/latest", data, "text/plain", true);
        ResponseEntity<?> r = DockerResponses.content(get(repo, "p"), tag, digest, MediaTypes.OCI_MANIFEST, false);
        assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-cache");
    }

    @Test
    void jsonAndEmpty() {
        ResponseEntity<?> json = DockerResponses.json("{}".getBytes(StandardCharsets.UTF_8), MediaTypes.JSON);
        assertThat(json.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.getHeaders().getContentLength()).isEqualTo(2);
        assertThat(header(json, DockerResponses.API_VERSION_HEADER)).isEqualTo("registry/2.0");
        ResponseEntity<?> empty = DockerResponses.empty(HttpStatus.ACCEPTED);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(empty.getBody()).isNull();
        assertThat(header(empty, DockerResponses.API_VERSION_HEADER)).isEqualTo("registry/2.0");
    }
}
