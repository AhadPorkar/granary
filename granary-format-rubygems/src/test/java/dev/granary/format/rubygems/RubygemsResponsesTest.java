package dev.granary.format.rubygems;

import static dev.granary.format.rubygems.RubygemsTestSupport.request;
import static dev.granary.format.rubygems.RubygemsTestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class RubygemsResponsesTest {

    private final FakeStorage storage = new FakeStorage();
    private final Repository repo = RubygemsTestSupport.hosted("gems-hosted");

    @TempDir
    java.nio.file.Path temp;

    // --- responses ----------------------------------------------------------------------------------

    @Test
    void compactIndexOfABuiltDocument() throws IOException {
        String doc = "---\n1.0 |checksum:abc\n";
        ResponseEntity<?> response = GemResponses.compactIndex(request(repo, "GET", "info/x"), doc);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"" + CompactIndex.md5(doc) + "\"");
        assertThat(response.getHeaders().getFirst("Repr-Digest")).startsWith("sha-256=:").endsWith(":");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
        assertThat(((Resource) response.getBody()).getContentAsString(StandardCharsets.UTF_8)).isEqualTo(doc);
        assertThat(response.getHeaders().getLastModified()).isEqualTo(-1);
    }

    @Test
    void compactIndexOfAStoredDocumentUsesTheRememberedMd5() throws IOException {
        StoredContent stored = storage.put(repo, "versions", "created_at: x\n---\n");
        GemResponses.rememberMd5(stored.asset().getBlobSha256(), "0123456789abcdef0123456789abcdef");
        ResponseEntity<?> response = GemResponses.compactIndex(request(repo, "GET", "versions"), stored);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"0123456789abcdef0123456789abcdef\"");
    }

    @Test
    void md5OfAStoredDocumentIsComputedOnce() {
        StoredContent stored = storage.put(repo, "info/fresh", "---\nfresh " + System.nanoTime() + "\n");
        String md5 = GemResponses.md5(stored);
        assertThat(md5).isEqualTo(CompactIndex.md5(storage.bytes(repo, "info/fresh")));
        assertThat(GemResponses.md5(stored)).isEqualTo(md5);
    }

    @Test
    void filesAreImmutableOrRevalidated() throws IOException {
        StoredContent gem = storage.put(repo, "gems/a-1.0.gem", "gem");
        ResponseEntity<?> immutable = GemResponses.file(request(repo, "GET", "gems/a-1.0.gem"), gem,
                GemResponses.OCTET_STREAM, true);
        assertThat(immutable.getHeaders().getCacheControl()).isEqualTo("max-age=31536000, public");
        try (InputStream in = ((Resource) immutable.getBody()).getInputStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("gem");
        }
        Resource resource = (Resource) immutable.getBody();
        assertThat(resource.contentLength()).isEqualTo(3);
        assertThat(resource.getFilename()).isEqualTo("a-1.0.gem");
        assertThat(resource.getDescription()).contains("gems/a-1.0.gem");
        assertThat(resource).isEqualTo(new GemResponses.BlobResource(gem)).hasSameHashCodeAs(new GemResponses.BlobResource(gem));

        ResponseEntity<?> index = GemResponses.file(request(repo, "GET", "specs.4.8.gz"), gem, GemResponses.OCTET_STREAM, false);
        assertThat(index.getHeaders().getCacheControl()).isEqualTo("no-cache");
    }

    @Test
    void jsonAndTextResponses() {
        RepositoryRequest get = request(repo, "GET", "api/v1/gems/a.json");
        ResponseEntity<?> json = GemResponses.json(get, "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(json.getHeaders().getContentLength()).isEqualTo(2);
        assertThat(GemResponses.json(request(repo, "HEAD", "api/v1/gems/a.json"), "{}".getBytes(StandardCharsets.UTF_8))
                .getBody()).isNull();
        ResponseEntity<?> notModified = GemResponses.json(RubygemsTestSupport.request(repo, "GET", "api/v1/gems/a.json",
                Map.of("If-None-Match", "*"), null, user("u")), "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        ResponseEntity<?> text = GemResponses.text(GemError.conflict("taken"));
        assertThat(text.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(text.getBody()).isEqualTo("taken");
        ResponseEntity<?> error = GemResponses.json(GemError.notFound("gone"));
        assertThat(error.getBody()).isEqualTo(Map.of("code", "rubygems.notFound", "message", "gone"));
    }

    @Test
    void matchesEntityTags() {
        assertThat(GemResponses.matches(null, "\"a\"")).isFalse();
        assertThat(GemResponses.matches(" ", "\"a\"")).isFalse();
        assertThat(GemResponses.matches("\"a\"", "\"a\"")).isTrue();
        assertThat(GemResponses.matches("W/\"a\"", "\"a\"")).isTrue();
        assertThat(GemResponses.matches("\"b\", \"a\"", "\"a\"")).isTrue();
        assertThat(GemResponses.matches("*", "\"a\"")).isTrue();
        assertThat(GemResponses.matches("\"ab\"", "\"a\"")).isFalse();
    }

    @Test
    void errorsCarryStatusAndCode() {
        assertThat(GemError.badRequest("x").status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(GemError.invalidGem("x").status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(GemError.unprocessable("x").status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(GemError.readOnly("x").status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(GemError.readOnly("x").code()).isEqualTo("repository.readOnly");
        assertThat(GemError.unauthorized("x").status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(GemError.forbidden("x").status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(GemError.tooLarge("x").status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    // --- derived documents --------------------------------------------------------------------------

    @Test
    void derivedDocumentsAreBuiltOncePerFingerprint() throws IOException {
        DerivedDocuments derived = new DerivedDocuments(storage.mock, temp);
        AtomicInteger builds = new AtomicInteger();
        DerivedDocuments.Writer writer = out -> {
            builds.incrementAndGet();
            out.write("doc".getBytes(StandardCharsets.UTF_8));
        };
        StoredContent first = derived.get(repo, "names", "fp1", GemResponses.TEXT_UTF8, writer);
        StoredContent second = derived.get(repo, "names", "fp1", GemResponses.TEXT_UTF8, writer);
        assertThat(builds).hasValue(1);
        assertThat(second.asset().getBlobSha256()).isEqualTo(first.asset().getBlobSha256());
        assertThat(first.asset().getOrigin()).isEqualTo(dev.granary.core.model.Asset.Origin.GENERATED);
        assertThat(first.asset().isMetadata()).isTrue();
        assertThat(GemResponses.md5(first)).isEqualTo(CompactIndex.md5("doc"));

        derived.get(repo, "names", "fp2", GemResponses.TEXT_UTF8, writer);
        assertThat(builds).hasValue(2);
        assertThat(java.nio.file.Files.list(temp)).as("temporary files are removed").isEmpty();
    }

    @Test
    void aFailedBuildLeavesNothingBehind() {
        DerivedDocuments derived = new DerivedDocuments(storage.mock, temp);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> derived.get(repo, "names", "fp", GemResponses.TEXT_UTF8,
                out -> {
                    out.write(1);
                    throw new IOException("boom");
                })).hasMessage("boom");
        assertThat(storage.find(repo, "names")).isEmpty();
    }

    @Test
    void concurrentRequestsWaitForOneBuild() throws Exception {
        DerivedDocuments derived = new DerivedDocuments(storage.mock, temp);
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return derived.get(repo, "versions", "same", GemResponses.TEXT_UTF8, out -> {
                        builds.incrementAndGet();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        out.write("v".getBytes(StandardCharsets.UTF_8));
                    }).asset().getBlobSha256();
                }));
            }
            start.countDown();
            for (Future<String> f : results) {
                assertThat(f.get()).isNotBlank();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(builds).hasValue(1);
    }

    @Test
    void fingerprintsDependOnEveryPartAndTheGeneration() {
        String a = DerivedDocuments.fingerprint(List.of("x", "y"));
        assertThat(a).hasSize(64).isEqualTo(DerivedDocuments.fingerprint(List.of("x", "y")));
        assertThat(DerivedDocuments.fingerprint(List.of("x", "z"))).isNotEqualTo(a);
        assertThat(DerivedDocuments.fingerprint(List.of("xy"))).isNotEqualTo(a);
        assertThat(DerivedDocuments.GENERATION).isNotBlank();
    }
}
