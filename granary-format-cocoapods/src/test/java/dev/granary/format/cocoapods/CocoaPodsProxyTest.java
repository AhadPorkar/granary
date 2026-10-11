package dev.granary.format.cocoapods;

import static dev.granary.format.cocoapods.CocoaPodsTestSupport.httpPodspec;
import static dev.granary.format.cocoapods.CocoaPodsTestSupport.podspec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import dev.granary.format.cocoapods.CocoaPodsTestSupport.FakeStorage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class CocoaPodsProxyTest {

    private final ProxyService proxyService = mock(ProxyService.class);
    private final UpstreamClient client = mock(UpstreamClient.class);
    private FakeStorage storage;
    private CocoaPodsProxy proxy;
    private Repository repo;

    @BeforeEach
    void setUp() {
        storage = new FakeStorage();
        proxy = new CocoaPodsProxy(proxyService, storage.mock, client, new NegativeCache());
        repo = CocoaPodsTestSupport.repository("cocoapods-cdn", RepositoryType.PROXY);
        when(proxyService.fetch(any(), any(String.class), any(FetchOptions.class))).thenReturn(Optional.empty());
    }

    private StoredContent cached(String path, String body) {
        return storage.put(repo, path, body);
    }

    private void upstreamHas(String path, String body, FetchOptions options) {
        StoredContent content = cached(path, body);
        when(proxyService.fetch(repo, path, options)).thenReturn(Optional.of(content));
    }

    @Test
    void theLayoutComesFromTheUpstreamVersionFileAndIsCachedByContent() {
        assertThat(proxy.layout(repo)).isEqualTo(ShardLayout.STANDARD);
        upstreamHas(VersionFile.PATH, "---\nmin: 1.0.0\nlast: 1.16.2\nprefix_lengths: [2]\n", FetchOptions.index());
        assertThat(proxy.layout(repo).prefixLengths()).containsExactly(2);
        assertThat(proxy.layout(repo).prefixLengths()).containsExactly(2);
        upstreamHas(VersionFile.PATH, "garbage", FetchOptions.index());
        assertThat(proxy.layout(repo)).isEqualTo(ShardLayout.STANDARD);
        upstreamHas(VersionFile.PATH, "last: 1.16.2\n", FetchOptions.index());
        assertThat(proxy.layout(repo).prefixLengths()).isEmpty();
    }

    @Test
    void podspecsAreFetchedAtTheUpstreamPathAsArtifactsAndLinkedToTheirComponent() {
        String path = "Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json";
        upstreamHas(path, podspec("AFNetworking", "4.0.1"), FetchOptions.artifact());
        StoredContent content = proxy.podspec(repo, "AFNetworking", "4.0.1").orElseThrow();
        assertThat(content.asset().getComponentId()).isNotNull();
        assertThat(storage.components).containsOnlyKeys(new StorageService.ComponentCoordinates("", "AFNetworking", "4.0.1"));
        // a second request does not create a second component
        proxy.podspec(repo, "AFNetworking", "4.0.1");
        assertThat(storage.components).hasSize(1);
        assertThat(proxy.podspec(repo, "Missing", "1.0")).isEmpty();
    }

    @Test
    void podspecsOfAnotherLayoutAreFetchedThere() {
        upstreamHas(VersionFile.PATH, "last: 1.16.2\n", FetchOptions.index());
        String path = "Specs/AFNetworking/4.0.1/AFNetworking.podspec.json";
        upstreamHas(path, podspec("AFNetworking", "4.0.1"), FetchOptions.artifact());
        assertThat(proxy.podspec(repo, "AFNetworking", "4.0.1")).isPresent();
    }

    @Test
    void deprecatedPodspecsAreRevalidatedLikeMetadata() {
        String deprecated = "Specs/a/7/5/AFNetworking/1.0/AFNetworking.podspec.json";
        String current = "Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json";
        cached(CocoaPodsPath.DEPRECATED, deprecated + "\nSpecs/0/0/4/AFCategory/0.1.0/AFCategory.podspec.json\n");
        upstreamHas(deprecated, podspec("AFNetworking", "1.0"), FetchOptions.index());
        upstreamHas(current, podspec("AFNetworking", "4.0.1"), FetchOptions.artifact());
        assertThat(proxy.podspec(repo, "AFNetworking", "1.0")).isPresent();
        assertThat(proxy.podspec(repo, "AFNetworking", "4.0.1")).isPresent();
        verify(proxyService).fetch(repo, deprecated, FetchOptions.index());
        verify(proxyService).fetch(repo, current, FetchOptions.artifact());
        // a new list is picked up
        cached(CocoaPodsPath.DEPRECATED, current + "\n");
        upstreamHas(current, podspec("AFNetworking", "4.0.1"), FetchOptions.index());
        assertThat(proxy.podspec(repo, "AFNetworking", "4.0.1")).isPresent();
        verify(proxyService).fetch(repo, current, FetchOptions.index());
    }

    @Test
    void sourcesOnTheUpstreamAreFetchedThroughTheProxyWithThePodspecsHeaders() {
        String url = "https://cdn.cocoapods.org/archives/Zipped-1.2.0.zip";
        String spec = httpPodspec("Zipped", "1.2.0", url).replace("\"flatten\": true",
                "\"headers\": [\"Accept: application/octet-stream\", \"Host: evil\", \"bad header: x\"]");
        upstreamHas(ShardLayout.STANDARD.podspecPath("Zipped", "1.2.0"), spec, FetchOptions.artifact());
        String path = "sources/Zipped/1.2.0/Zipped-1.2.0.zip";
        StoredContent archive = CocoaPodsTestSupport.content(storage.blobs, path, CocoaPodsTestSupport.zip("a", "b"), "application/zip");
        when(proxyService.fetch(eq(repo), eq(path), eq(URI.create(url)), eq(FetchOptions.artifact()), any())).thenReturn(Optional.of(archive));

        assertThat(proxy.source(repo, "Zipped", "1.2.0", "Zipped-1.2.0.zip")).contains(archive);
        verify(proxyService).fetch(repo, path, URI.create(url), FetchOptions.artifact(), Map.of("Accept", "application/octet-stream"));
        assertThat(archive.asset().getComponentId()).isNotNull();
        verifyNoInteractions(client);
    }

    /** An upstream response the mocked client hands out. */
    private static UpstreamClient.Response response(int status, byte[] body, Map<String, String> headers) {
        UpstreamClient.Response response = mock(UpstreamClient.Response.class);
        when(response.status()).thenReturn(status);
        when(response.body()).thenReturn(new ByteArrayInputStream(body));
        when(response.header(any())).thenAnswer(inv -> Optional.ofNullable(headers.get(inv.<String>getArgument(0))));
        return response;
    }

    private void binaryPodOn(String url, String extraSource) {
        String spec = httpPodspec("Zipped", "1.2.0", url).replace("\"flatten\": true", extraSource);
        upstreamHas(ShardLayout.STANDARD.podspecPath("Zipped", "1.2.0"), spec, FetchOptions.artifact());
    }

    @Test
    void sourcesOnOtherHostsAreDownloadedDirectlyAndTheClientKeepsTheCredentialsAtHome() throws Exception {
        repo.setRemoteUsername("mirror");
        repo.setRemotePassword("secret");
        repo.setReadTimeoutSeconds(7);
        String url = "https://github.com/acme/Zipped/releases/download/1.2.0/Zipped-1.2.0.zip";
        byte[] zip = CocoaPodsTestSupport.zip("Zipped.xcframework/Info.plist", "<plist/>");
        binaryPodOn(url, "\"sha256\": \"" + Checksums.hex("SHA-256", zip) + "\", \"headers\": [\"Accept: application/octet-stream\"]");
        UpstreamClient.Response ok = response(200, zip, Map.of("Content-Type", "application/zip; charset=binary", "ETag", "\"gh\""));
        when(client.getAbsolute(any(), eq(URI.create(url)), any(), any())).thenReturn(ok);

        StoredContent archive = proxy.source(repo, "Zipped", "1.2.0", "Zipped-1.2.0.zip").orElseThrow();
        assertThat(archive.readAllBytes()).isEqualTo(zip);
        assertThat(archive.contentType()).isEqualTo("application/zip");
        assertThat(archive.asset().getComponentId()).isNotNull();
        assertThat(archive.asset().getUpstreamEtag()).isEqualTo("\"gh\"");
        // the repository itself: UpstreamClient sends its credentials to the upstream's origin only (and to hosts
        // listed in upstream.credentialHosts), which CocoaPodsArchiveHostIntegrationTest checks on the wire
        ArgumentCaptor<Repository> asked = ArgumentCaptor.forClass(Repository.class);
        verify(client).getAbsolute(asked.capture(), eq(URI.create(url)), eq(Map.of()), eq(Map.of("Accept", "application/octet-stream")));
        assertThat(asked.getValue()).isSameAs(repo);
        assertThat(asked.getValue().getReadTimeoutSeconds()).isEqualTo(7);
        verify(proxyService, never()).fetch(any(), any(String.class), any(URI.class), any(), any());
        verify(ok).close();

        // cached from now on
        assertThat(proxy.source(repo, "Zipped", "1.2.0", "Zipped-1.2.0.zip")).isPresent();
        verify(client, times(1)).getAbsolute(any(), any(), any(), any());
    }

    @Test
    void aFailingArchiveHostIsABadGatewayAndA404IsRemembered() throws Exception {
        String url = "https://downloads.example.com/Zipped-1.2.0.zip";
        binaryPodOn(url, "\"type\": \"zip\"");
        UpstreamClient.Response serverError = response(500, new byte[0], Map.of());
        UpstreamClient.Response notFound = response(404, new byte[0], Map.of());
        when(client.getAbsolute(any(), eq(URI.create(url)), any(), any()))
                .thenReturn(serverError)
                .thenThrow(new java.net.ConnectException("Connection refused"))
                .thenReturn(notFound);
        for (String reason : List.of("HTTP 500", "ConnectException: Connection refused")) {
            assertThatThrownBy(() -> proxy.source(repo, "Zipped", "1.2.0", "Zipped-1.2.0.zip"))
                    .isInstanceOfSatisfying(CocoaPodsError.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(e.code()).isEqualTo("upstream.unavailable");
                        assertThat(e.getMessage()).contains(url).endsWith(reason);
                    });
        }
        when(proxyService.negativeTtl(repo)).thenReturn(Duration.ofMinutes(10));
        assertThat(proxy.source(repo, "Zipped", "1.2.0", "Zipped-1.2.0.zip")).isEmpty();
        assertThat(proxy.source(repo, "Zipped", "1.2.0", "Zipped-1.2.0.zip")).isEmpty();
        verify(client, times(3)).getAbsolute(any(), any(), any(), any());
        assertThat(storage.paths(repo)).noneMatch(p -> p.startsWith("sources/"));
    }

    @Test
    void offlineProxiesDoNotDownloadArchives() throws Exception {
        binaryPodOn("https://downloads.example.com/Zipped-1.2.0.zip", "\"type\": \"zip\"");
        when(proxyService.isOffline(repo)).thenReturn(true);
        assertThat(proxy.source(repo, "Zipped", "1.2.0", "Zipped-1.2.0.zip")).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void anArchiveThatDoesNotMatchThePodspecsSha256IsNotCached() throws Exception {
        String url = "https://downloads.example.com/Zipped-1.2.0.zip";
        byte[] zip = CocoaPodsTestSupport.zip("a", "b");
        binaryPodOn(url, "\"sha256\": \"" + "0".repeat(64) + "\"");
        UpstreamClient.Response wrong = response(200, zip, Map.of());
        when(client.getAbsolute(any(), eq(URI.create(url)), any(), any())).thenReturn(wrong);
        assertThatThrownBy(() -> proxy.source(repo, "Zipped", "1.2.0", "Zipped-1.2.0.zip"))
                .isInstanceOfSatisfying(CocoaPodsError.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.code()).isEqualTo("upstream.checksumMismatch");
                    assertThat(e.getMessage()).contains(Checksums.hex("SHA-256", zip)).contains("0".repeat(64));
                });
        assertThat(storage.paths(repo)).noneMatch(p -> p.startsWith("sources/"));

        // the same check guards archives fetched through the proxy's own upstream
        String own = "https://cdn.cocoapods.org/archives/Own-1.0.zip";
        String spec = httpPodspec("Own", "1.0", own).replace("\"flatten\": true", "\"sha256\": \"" + "1".repeat(64) + "\"");
        upstreamHas(ShardLayout.STANDARD.podspecPath("Own", "1.0"), spec, FetchOptions.artifact());
        String ownPath = "sources/Own/1.0/Own-1.0.zip";
        when(proxyService.fetch(eq(repo), eq(ownPath), eq(URI.create(own)), eq(FetchOptions.artifact()), any()))
                .thenAnswer(inv -> Optional.of(storage.put(repo, ownPath, zip, "application/zip", false)));
        assertThatThrownBy(() -> proxy.source(repo, "Own", "1.0", "Own-1.0.zip"))
                .isInstanceOfSatisfying(CocoaPodsError.class, e -> assertThat(e.code()).isEqualTo("upstream.checksumMismatch"));
        assertThat(storage.find(repo, ownPath)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "https://cdn.cocoapods.org/, https://cdn.cocoapods.org/x.zip, true",
            "https://cdn.cocoapods.org/, https://CDN.CocoaPods.org:443/x.zip, true",
            "https://cdn.cocoapods.org, https://cdn.cocoapods.org/a/b.zip, true",
            "http://mirror.local:8081/repository/pods/, http://mirror.local:8081/repository/pods/sources/A/1/a.zip, true",
            "http://mirror.local/, http://mirror.local:80/a.zip, true",
            "https://cdn.cocoapods.org/, http://cdn.cocoapods.org/x.zip, false",
            "https://cdn.cocoapods.org/, https://cdn.cocoapods.org:8443/x.zip, false",
            "https://cdn.cocoapods.org/, https://github.com/x.zip, false",
            "https://cdn.cocoapods.org/, https://cdn.cocoapods.org.evil.example/x.zip, false",
            "https://cdn.cocoapods.org/, https://cdn.cocoapods.org@github.com/x.zip, false",
            "https://cdn.cocoapods.org/, https://cdn.cocoapods.org./x.zip, true",
            "'', https://cdn.cocoapods.org/x.zip, false"
    })
    void onlyTheUpstreamsOwnOriginGoesThroughTheProxyCache(String remote, String url, boolean same) {
        assertThat(CocoaPodsProxy.sameOrigin(remote, URI.create(url))).isEqualTo(same);
    }

    @Test
    void cachedSourcesAreServedWithoutLookingAtThePodspec() {
        String path = "sources/Zipped/1.2.0/Zipped-1.2.0.zip";
        storage.put(repo, path, CocoaPodsTestSupport.zip("a", "b"), "application/zip", false);
        assertThat(proxy.source(repo, "Zipped", "1.2.0", "Zipped-1.2.0.zip")).isPresent();
        verify(proxyService, never()).fetch(any(), any(String.class), any(FetchOptions.class));
    }

    @Test
    void aSourceIsOnlyFetchedForTheFileTheHttpSourceNames() {
        String url = "https://example.com/Zipped-1.2.0.zip";
        upstreamHas(ShardLayout.STANDARD.podspecPath("Zipped", "1.2.0"), httpPodspec("Zipped", "1.2.0", url), FetchOptions.artifact());
        upstreamHas(ShardLayout.STANDARD.podspecPath("Git", "1.0"), podspec("Git", "1.0"), FetchOptions.artifact());
        upstreamHas(ShardLayout.STANDARD.podspecPath("Local", "1.0"), httpPodspec("Local", "1.0", "file:///etc/passwd"), FetchOptions.artifact());
        upstreamHas(ShardLayout.STANDARD.podspecPath("Broken", "1.0"), "not json", FetchOptions.artifact());

        assertThat(proxy.source(repo, "Zipped", "1.2.0", "other.zip")).isEmpty();
        assertThat(proxy.source(repo, "Git", "1.0", "Git.zip")).isEmpty();
        assertThat(proxy.source(repo, "Local", "1.0", "passwd")).isEmpty();
        assertThat(proxy.source(repo, "Broken", "1.0", "x.zip")).isEmpty();
        assertThat(proxy.source(repo, "Unknown", "1.0", "x.zip")).isEmpty();
        verify(proxyService, never()).fetch(any(), any(String.class), any(URI.class), any(), any());
    }

    @Test
    void onlySafeHeadersAreForwarded() {
        Map<String, String> headers = CocoaPodsProxy.forwardedHeaders(List.of(
                "Accept: application/octet-stream", "Authorization: token abc", "Host: evil.example", "Connection: close",
                "Content-Length: 5", "User-Agent: x", "Bad Name: x", "NoColon", ": empty", "X-Split: a\r\nInjected: b",
                "X-Twice: 1", "X-Twice: 2", "If-None-Match: *"));
        assertThat(headers).containsExactly(Map.entry("Accept", "application/octet-stream"), Map.entry("Authorization", "token abc"),
                Map.entry("X-Twice", "1"));
    }

    @Test
    void rewritingIsOnUnlessSwitchedOff() {
        assertThat(CocoaPodsProxy.rewritesSources(repo)).isTrue();
        repo.setFormatSettings(Map.of(CocoaPodsProxy.REWRITE_SETTING, "false"));
        assertThat(CocoaPodsProxy.rewritesSources(repo)).isFalse();
        repo.setFormatSettings(Map.of(CocoaPodsProxy.REWRITE_SETTING, "true"));
        assertThat(CocoaPodsProxy.rewritesSources(repo)).isTrue();
    }

    @Test
    void indexDocumentsAreFetchedAsMetadata() {
        upstreamHas(CocoaPodsPath.ALL_PODS, "A\nB\n", FetchOptions.index());
        assertThat(proxy.index(repo, CocoaPodsPath.ALL_PODS)).isPresent();
        verify(proxyService, times(1)).fetch(repo, CocoaPodsPath.ALL_PODS, FetchOptions.index());
    }
}
