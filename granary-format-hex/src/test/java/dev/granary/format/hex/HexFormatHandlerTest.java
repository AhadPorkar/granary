package dev.granary.format.hex;

import static dev.granary.format.hex.HexTestSupport.anonymous;
import static dev.granary.format.hex.HexTestSupport.body;
import static dev.granary.format.hex.HexTestSupport.bodyBytes;
import static dev.granary.format.hex.HexTestSupport.get;
import static dev.granary.format.hex.HexTestSupport.goldenKey;
import static dev.granary.format.hex.HexTestSupport.goldenText;
import static dev.granary.format.hex.HexTestSupport.group;
import static dev.granary.format.hex.HexTestSupport.hosted;
import static dev.granary.format.hex.HexTestSupport.http;
import static dev.granary.format.hex.HexTestSupport.proxy;
import static dev.granary.format.hex.HexTestSupport.request;
import static dev.granary.format.hex.HexTestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The repository API through {@link HexFormatHandler#handle}: routing, content types, caching headers, errors. */
class HexFormatHandlerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path tempDir;

    private HexFixture fx;
    private HexFormatHandler handler;
    private final Repository hostedRepo = hosted("hex-hosted");
    private final Repository proxyRepo = proxy("hexpm", HexFormatHandler.DEFAULT_REMOTE);
    private final Repository groupRepo = group("hex-group", "hex-hosted", "hexpm");

    @BeforeEach
    void setUp() {
        fx = new HexFixture(goldenKey(tempDir));
        fx.members(groupRepo, hostedRepo, proxyRepo);
        handler = fx.handler;
    }

    private ResponseEntity<?> handle(RepositoryRequest request) throws Exception {
        return handler.handle(request);
    }

    @Test
    void identityTemplatesAndSettings() {
        assertThat(handler.format()).isEqualTo("hex");
        assertThat(handler.displayName()).isEqualTo("Hex (Elixir / Erlang)");
        List<RepositoryTemplate> templates = handler.templates();
        assertThat(templates).extracting(RepositoryTemplate::name).containsExactly("hexpm", "hex-hosted", "hex-group");
        assertThat(templates.get(0).type()).isEqualTo(RepositoryType.PROXY);
        assertThat(templates.get(0).remoteUrl()).isEqualTo("https://repo.hex.pm/");
        assertThat(templates.get(1).type()).isEqualTo(RepositoryType.HOSTED);
        assertThat(templates.get(2).type()).isEqualTo(RepositoryType.GROUP);
        assertThat(templates.get(2).members()).containsExactly("hex-hosted", "hexpm");
        assertThat(templates).allSatisfy(t -> assertThat(t.format()).isEqualTo("hex"));
        assertThat(handler.settings()).hasSize(3);
        handler.validate(hostedRepo);
    }

    @Test
    void metadataPaths() {
        assertThat(handler.isMetadataPath("names")).isTrue();
        assertThat(handler.isMetadataPath("versions")).isTrue();
        assertThat(handler.isMetadataPath("packages/acme")).isTrue();
        assertThat(handler.isMetadataPath("public_key")).isTrue();
        assertThat(handler.isMetadataPath("installs/hex-1.x.csv")).isTrue();
        assertThat(handler.isMetadataPath(".hex/packages/acme.json")).isTrue();
        assertThat(handler.isMetadataPath("tarballs/acme-1.0.0.tar")).isFalse();
        assertThat(handler.isMetadataPath("docs/acme-1.0.0.tar.gz")).isFalse();
        assertThat(handler.isMetadataPath(null)).isFalse();
    }

    @Test
    void requiredAccess() {
        assertThat(handler.requiredAccess(get(hostedRepo, "packages/acme"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "HEAD", "names", null, null, anonymous()))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "POST", "api/packages/acme/releases", null, null, anonymous())))
                .isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hostedRepo, "DELETE", "api/packages/acme/releases/1.0.0", null, null, anonymous())))
                .isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hostedRepo, "POST", "api/keys", null, null, anonymous()))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "DELETE", "api/keys/x", null, null, anonymous()))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(get(hostedRepo, "api/users/me"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(get(hostedRepo, "api/auth?domain=api&resource=write"))).isEqualTo(AccessLevel.READ);
    }

    @Test
    void rootDescribesTheRepository() throws Exception {
        ResponseEntity<?> response = handle(get(hostedRepo, ""));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = JSON.readTree(body(response));
        assertThat(json.path("format").asString()).isEqualTo("hex");
        assertThat(json.path("type").asString()).isEqualTo("hosted");
        assertThat(json.path("repository").asString()).isEqualTo("hex-hosted");
        assertThat(json.path("api_url").asString()).isEqualTo("http://granary.test/repository/hex-hosted/api");
        assertThat(json.path("public_key_fingerprint").asString()).isEqualTo(goldenText("fingerprint.txt").strip());

        JsonNode proxyRoot = JSON.readTree(body(handle(get(proxyRepo, ""))));
        assertThat(proxyRoot.path("repository").isNull()).isTrue();
        assertThat(proxyRoot.path("public_key_fingerprint").isNull()).isTrue();
    }

    @Test
    void publicKeys() throws Exception {
        ResponseEntity<?> hostedKey = handle(get(hostedRepo, "public_key"));
        assertThat(hostedKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hostedKey.getHeaders().getContentType().toString()).isEqualTo("application/x-pem-file");
        assertThat(body(hostedKey)).isEqualTo(fx.key.publicKeyPem());
        assertThat(body(handle(get(groupRepo, "public_key")))).isEqualTo(fx.key.publicKeyPem());

        assertThat(handle(get(proxyRepo, "public_key")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        fx.upstreamText(proxyRepo, "public_key", "-----BEGIN PUBLIC KEY-----\nupstream\n-----END PUBLIC KEY-----\n");
        assertThat(body(handle(get(proxyRepo, "public_key")))).contains("upstream");
    }

    @Test
    void registryDocumentsOfAHostedRepository() throws Exception {
        fx.publish(hostedRepo, "acme", "1.0.0");
        ResponseEntity<?> pkg = handle(get(hostedRepo, "packages/acme"));
        assertThat(pkg.getStatusCode()).isEqualTo(HttpStatus.OK);
        HexRegistry.Package decoded = HexRegistry.unpack(bodyBytes(pkg), HexRegistry::decodePackage);
        assertThat(decoded.name()).isEqualTo("acme");

        assertThat(handle(get(hostedRepo, "names")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(handle(get(hostedRepo, "versions")).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<?> missing = handle(get(hostedRepo, "packages/nope"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(missing)).contains("package nope does not exist");
    }

    @Test
    void groupDocumentsSupportEtagsAndHead() throws Exception {
        fx.publish(hostedRepo, "acme", "1.0.0");
        ResponseEntity<?> first = handle(get(groupRepo, "packages/acme"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getContentType().toString()).isEqualTo("application/octet-stream");
        assertThat(first.getHeaders().getCacheControl()).isEqualTo("no-cache");
        String etag = first.getHeaders().getETag();
        assertThat(etag).isNotBlank();
        assertThat(HexRegistry.unpack(bodyBytes(first), HexRegistry::decodePackage).repository()).isEqualTo("hex-group");

        RepositoryRequest conditional = get(groupRepo, "packages/acme");
        http(conditional).addHeader(HttpHeaders.IF_NONE_MATCH, etag);
        ResponseEntity<?> notModified = handle(conditional);
        assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModified.getBody()).isNull();
        for (String header : List.of("*", "W/" + etag, "\"other\", " + etag, "\"other\",W/" + etag)) {
            RepositoryRequest weak = get(groupRepo, "packages/acme");
            http(weak).addHeader(HttpHeaders.IF_NONE_MATCH, header);
            assertThat(handle(weak).getStatusCode()).as(header).isEqualTo(HttpStatus.NOT_MODIFIED);
        }
        RepositoryRequest stale = get(groupRepo, "packages/acme");
        http(stale).addHeader(HttpHeaders.IF_NONE_MATCH, "\"other\", W/\"other\"");
        assertThat(handle(stale).getStatusCode()).isEqualTo(HttpStatus.OK);
        RepositoryRequest key = get(groupRepo, "public_key");
        http(key).addHeader(HttpHeaders.IF_NONE_MATCH, "*");
        assertThat(handle(key).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        ResponseEntity<?> head = handle(request(groupRepo, "HEAD", "packages/acme", null, null, anonymous()));
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isEqualTo(bodyBytes(first).length);
    }

    @Test
    void tarballDownloadsAreCountedButHeadsAreNot() throws Exception {
        fx.publish(hostedRepo, "acme", "1.0.0");
        ResponseEntity<?> tarball = handle(get(hostedRepo, "tarballs/acme-1.0.0.tar"));
        assertThat(tarball.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyBytes(tarball)).isEqualTo(fx.storage.bytes(hostedRepo, "tarballs/acme-1.0.0.tar"));
        verify(fx.storage.mock, times(1)).recordDownload(any());
        handle(request(hostedRepo, "HEAD", "tarballs/acme-1.0.0.tar", null, null, anonymous()));
        verify(fx.storage.mock, times(1)).recordDownload(any());

        ResponseEntity<?> viaGroup = handle(get(groupRepo, "tarballs/acme-1.0.0.tar"));
        assertThat(viaGroup.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<?> missing = handle(get(hostedRepo, "tarballs/acme-9.0.0.tar"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(missing)).contains("release acme 9.0.0 does not exist");
    }

    @Test
    void documentationDownloads() throws Exception {
        fx.publish(hostedRepo, "acme", "1.0.0");
        assertThat(handle(get(hostedRepo, "docs/acme-1.0.0.tar.gz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        fx.hosted.publishDocs(hostedRepo, "acme", "1.0.0", HexTestSupport.docsTarball());
        assertThat(bodyBytes(handle(get(hostedRepo, "docs/acme-1.0.0.tar.gz")))).isEqualTo(HexTestSupport.docsTarball());
    }

    @Test
    void installsOnAHostedRepositoryAreAnEmptyList() throws Exception {
        ResponseEntity<?> csv = handle(get(hostedRepo, "installs/hex-1.x.csv"));
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(bodyBytes(csv)).isEmpty();
        assertThat(handle(get(hostedRepo, "installs/hex-1.x.csv.signed")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handle(get(proxyRepo, "installs/hex-1.x.csv")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        fx.upstreamText(proxyRepo, "installs/hex-1.x.csv", "2.1.1,x,1.18.0\n");
        assertThat(body(handle(get(proxyRepo, "installs/hex-1.x.csv")))).startsWith("2.1.1");
    }

    @Test
    void oddPathsAndMethods() throws Exception {
        ResponseEntity<?> invalid = handle(get(hostedRepo, "packages/../names"));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(invalid)).contains("path.invalid");
        assertThat(handle(get(hostedRepo, ".hex/packages/acme.json")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handle(get(hostedRepo, "favicon.ico")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<?> put = handle(request(hostedRepo, "PUT", "names", new byte[] {1}, null, user("a")));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body(put)).contains("/api");
        ResponseEntity<?> apiMissing = handle(get(hostedRepo, "api/nothing/here"));
        assertThat(apiMissing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> error = HexTestSupport.termMap(apiMissing);
        assertThat(error).containsEntry("status", 404L).containsEntry("message", "Page not found");
    }

    @Test
    void encodedSeparatorsAreRefusedBeforeTheDecodedPathIsRead() throws Exception {
        fx.publish(hostedRepo, "acme", "1.0.0");
        for (String raw : List.of("packages/acme%2Fx", "packages/acme%2fx", "tarballs/acme-1.0.0.tar%5C", "api/packages/acme%2F")) {
            RepositoryRequest request = get(hostedRepo, raw);
            http(request).setRequestURI("/repository/hex-hosted/" + raw);
            ResponseEntity<?> response = handle(request.withPath(raw.replace("%2F", "/").replace("%2f", "/")
                    .replace("%5C", "\\")));
            assertThat(response.getStatusCode()).as(raw).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        ResponseEntity<?> api = handle(withRawUri(get(hostedRepo, "api/packages/acme/releases"), "api/packages/acme%2Freleases"));
        assertThat(HexTestSupport.termMap(api)).containsEntry("status", 400L);
        assertThat(handle(withRawUri(get(hostedRepo, "packages/acme"), "packages/%61cme")).getStatusCode())
                .isEqualTo(HttpStatus.OK); // other escapes are fine
    }

    private static RepositoryRequest withRawUri(RepositoryRequest request, String raw) {
        http(request).setRequestURI("/repository/" + request.repository().getName() + "/" + raw);
        return request;
    }

    @Test
    void writesToAGroupsRepositoryApiNameTheHostedMember() throws Exception {
        ResponseEntity<?> put = handle(request(groupRepo, "PUT", "names", new byte[] {1}, null, user("a")));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body(put)).contains("publish to the hosted repository 'hex-hosted'");
        ResponseEntity<?> proxyPut = handle(request(proxyRepo, "DELETE", "tarballs/acme-1.0.0.tar", null, null, user("a")));
        assertThat(proxyPut.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body(proxyPut)).contains("proxy repository");
    }

    @Test
    void aGroupWithAProxyMemberDoesNotInventAnEmptyInstallList() throws Exception {
        assertThat(handle(get(groupRepo, "installs/hex-1.x.csv")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        fx.upstreamText(proxyRepo, "installs/hex-1.x.csv", "2.1.1,x,1.18.0\n");
        assertThat(body(handle(get(groupRepo, "installs/hex-1.x.csv")))).startsWith("2.1.1");

        Repository hostedOnly = group("hex-hosted-only", "hex-hosted");
        fx.members(hostedOnly, hostedRepo);
        ResponseEntity<?> csv = handle(get(hostedOnly, "installs/hex-1.x.csv"));
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyBytes(csv)).isEmpty();
    }

    @Test
    void connectSnippetsUseTheGroupsHostedMember() {
        var ctx = new dev.granary.core.format.ConnectContext(groupRepo, "http://granary.test/repository/hex-group",
                "http://granary.test", "alice", "<api-token>", false);
        assertThat(handler.connectSnippets(ctx)).anySatisfy(s -> assertThat(s.code())
                .contains("HEX_API_URL=http://granary.test/repository/hex-hosted/api"));
        verify(fx.groups, never()).leafMembers(hostedRepo);
    }
}
