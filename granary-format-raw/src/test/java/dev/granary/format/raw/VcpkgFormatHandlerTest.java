package dev.granary.format.raw;

import static dev.granary.format.raw.RawTestSupport.context;
import static dev.granary.format.raw.RawTestSupport.hosted;
import static dev.granary.format.raw.RawTestSupport.properties;
import static dev.granary.format.raw.RawTestSupport.proxy;
import static dev.granary.format.raw.RawTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.granary.core.GranaryException;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class VcpkgFormatHandlerTest {

    static final Map<String, String> AZURE = Map.of("x-ms-blob-type", "BlockBlob", "x-ms-version", "2020-04-08");
    static final String SHA512 = "a".repeat(128);

    @TempDir
    Path dir;

    final FakeStorage storage = new FakeStorage();
    final Repository cache = hosted("vcpkg-cache", "vcpkg");
    VcpkgFormatHandler handler;

    @BeforeEach
    void setUp() {
        handler = new VcpkgFormatHandler(storage.mock, mock(ProxyService.class), mock(GroupResolver.class), properties(dir));
    }

    @Test
    void hostedOnly() {
        assertThat(handler.format()).isEqualTo("vcpkg");
        assertThat(handler.supportedTypes()).containsExactly(RepositoryType.HOSTED);
        assertThat(handler.templates()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("vcpkg-cache");
            assertThat(t.type()).isEqualTo(RepositoryType.HOSTED);
        });
        handler.validate(cache);
        assertThatThrownBy(() -> handler.validate(proxy("vcpkg-proxy", "vcpkg", "https://example.com/")))
                .isInstanceOf(GranaryException.class)
                .extracting(e -> ((GranaryException) e).code()).isEqualTo("repository.type.unsupported");
        assertThat(handler.isMetadataPath("assets/" + SHA512)).isFalse();
    }

    @Test
    void assetCachePutWithAzureHeadersAnswers201AndAcceptsIdenticalReuploads() throws Exception {
        byte[] bytes = "asset-bytes".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<?> put = handler.handle(request(cache, "PUT", "assets/" + SHA512, bytes, "application/octet-stream", AZURE));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<?> same = handler.handle(request(cache, "PUT", "assets/" + SHA512, bytes, "application/octet-stream", AZURE));
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // like Azure blob storage: no body (vcpkg copies upload response bodies into its console output), an ETag
        assertThat(same.getBody()).isNull();
        assertThat(same.getHeaders().getETag()).isEqualTo("\"" + storage.asset(cache, "assets/" + SHA512).getBlobSha256() + "\"");
        assertThat(storage.paths(cache)).containsExactly("assets/" + SHA512);

        ResponseEntity<?> different = handler.handle(request(cache, "PUT", "assets/" + SHA512, "other".getBytes(StandardCharsets.UTF_8), "application/octet-stream", AZURE));
        assertThat(different.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(storage.text(cache, "assets/" + SHA512)).isEqualTo("asset-bytes");

        assertThat(RawTestSupport.body(handler.handle(request(cache, "GET", "assets/" + SHA512)))).isEqualTo("asset-bytes");
        assertThat(handler.handle(request(cache, "HEAD", "assets/" + SHA512)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(handler.handle(request(cache, "HEAD", "assets/" + "b".repeat(128))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Path spool = dir.resolve("tmp").resolve("vcpkg");
        assertThat(Files.isDirectory(spool)).isTrue();
        try (var files = Files.list(spool)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void binaryCacheRecordsNameAndVersion() throws Exception {
        ResponseEntity<?> put = handler.handle(request(cache, "PUT", "binaries/zlib/1.3.1/0123abcd", "zip".getBytes(StandardCharsets.UTF_8), "application/zip"));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.coordinates(cache, "binaries/zlib/1.3.1/0123abcd"))
                .isEqualTo(new StorageService.ComponentCoordinates("", "zlib", "1.3.1"));
        assertThat(handler.coordinates("assets/" + SHA512)).isEqualTo(new StorageService.ComponentCoordinates("", "assets", SHA512));
        assertThat(storage.asset(cache, "binaries/zlib/1.3.1/0123abcd").getContentType()).isEqualTo("application/zip");
    }

    @Test
    void snippetsCarryBothEnvironmentVariables() {
        var snippets = handler.connectSnippets(context(cache, true));
        assertThat(snippets).extracting(ConnectSnippet::id).contains("bash", "powershell", "cmd", "cli", "manifest");
        String bash = snippet(snippets, "bash");
        // uploads: credentials in the asset cache URL, the token as a header for the binary cache
        assertThat(bash)
                .contains("export X_VCPKG_ASSET_SOURCES=\"x-azurl,http://<username>:<api-token>@granary.test/repository/vcpkg-cache/assets/,,readwrite\"")
                .contains("export VCPKG_BINARY_SOURCES=\"clear;http,http://granary.test/repository/vcpkg-cache/binaries/{name}/{version}/{sha},readwrite,Authorization: Bearer <api-token>\"")
                // read-only without credentials while anonymous read is on
                .contains("export X_VCPKG_ASSET_SOURCES=\"x-azurl,http://granary.test/repository/vcpkg-cache/assets/,,read\"")
                .contains("export VCPKG_BINARY_SOURCES=\"clear;http,http://granary.test/repository/vcpkg-cache/binaries/{name}/{version}/{sha},read\"")
                .contains("vcpkg\n# prints asset cache URLs");
        assertThat(snippet(snippets, "powershell"))
                .contains("$env:VCPKG_BINARY_SOURCES = \"clear;http,http://granary.test/repository/vcpkg-cache/binaries/{name}/{version}/{sha},readwrite,Authorization: Bearer <api-token>\"");
        assertThat(snippet(snippets, "cmd"))
                .contains("set \"X_VCPKG_ASSET_SOURCES=x-azurl,http://<username>:<api-token>@granary.test/repository/vcpkg-cache/assets/,,readwrite\"")
                .contains("rem uploads need credentials")
                .doesNotContain("# ");
        assertThat(snippet(snippets, "cli")).contains("--binarysource=\"clear;http,http://granary.test/repository/vcpkg-cache/binaries/{name}/{version}/{sha},readwrite,Authorization: Bearer <api-token>\"");
        assertThat(snippet(snippets, "manifest")).contains("default registry").contains("asset cache");
    }

    @Test
    void readOnlySnippetsCarryCredentialsWhenAnonymousReadIsOff() {
        String bash = snippet(handler.connectSnippets(context(cache, false)), "bash");
        assertThat(bash)
                .contains("export X_VCPKG_ASSET_SOURCES=\"x-azurl,http://<username>:<api-token>@granary.test/repository/vcpkg-cache/assets/,,read\"")
                .contains("export VCPKG_BINARY_SOURCES=\"clear;http,http://granary.test/repository/vcpkg-cache/binaries/{name}/{version}/{sha},read,Authorization: Bearer <api-token>\"")
                .contains("anonymous read is off");
    }

    private static String snippet(java.util.List<ConnectSnippet> snippets, String id) {
        return snippets.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow().code();
    }
}
