package dev.granary.format.pub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.storage.StorageService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PubProxyTest {

    private static final byte[] ARCHIVE = PubTestSupport.archive("path", "1.9.1");

    private final ObjectMapper json = JsonMapper.builder().build();
    private final PubTestSupport.FakeStorage storage = new PubTestSupport.FakeStorage();
    private final ProxyService proxyService = mock(ProxyService.class);
    private PubProxy proxy;
    private Repository repo;

    @BeforeEach
    void setUp() {
        proxy = new PubProxy(proxyService, storage.mock, new UpstreamClient(new GranaryProperties()), json);
        repo = PubTestSupport.repository("pub-dev", RepositoryType.PROXY);
        when(proxyService.fetch(any(), anyString(), any(FetchOptions.class), anyMap())).thenReturn(Optional.empty());
        when(proxyService.fetch(any(), anyString(), any(URI.class), any(), anyMap())).thenReturn(Optional.empty());
        when(proxyService.status(any())).thenReturn(new UpstreamHealth.Status(true, 0, null, Instant.now(), null, null));
    }

    private String listing(String archiveUrl, String sha) {
        return "{\"name\":\"path\",\"versions\":[{\"version\":\"1.9.1\",\"pubspec\":{\"name\":\"path\"},"
                + "\"archive_url\":\"" + archiveUrl + "\",\"archive_sha256\":\"" + sha + "\"}]}";
    }

    private void cacheListing(String body) {
        storage.put(repo, "api/packages/path", body.getBytes(StandardCharsets.UTF_8), "application/json", true);
    }

    private void upstreamServes(URI uri, byte[] bytes) {
        when(proxyService.fetch(eq(repo), eq("packages/path/versions/1.9.1.tar.gz"), eq(uri), eq(FetchOptions.artifact()), anyMap()))
                .thenAnswer(inv -> Optional.of(storage.put(repo, "packages/path/versions/1.9.1.tar.gz", bytes,
                        "application/octet-stream", false)));
    }

    @Test
    void listingsAreFetchedAsMetadataWithThePubAcceptHeader() {
        when(proxyService.fetch(eq(repo), eq("api/packages/path"), eq(FetchOptions.index()), eq(PubProxy.ACCEPT)))
                .thenAnswer(inv -> Optional.of(storage.put(repo, "api/packages/path",
                        listing("https://pub.dev/api/archives/path-1.9.1.tar.gz", "x").getBytes(StandardCharsets.UTF_8),
                        "application/json", true)));
        assertThat(proxy.listing(repo, "path")).hasValueSatisfying(doc ->
                assertThat(doc.get("versions").get(0).get("version").stringValue()).isEqualTo("1.9.1"));
        assertThat(PubProxy.ACCEPT).containsEntry("Accept", "application/vnd.pub.v2+json");
    }

    @Test
    void anUnparsableUpstreamListingCountsAsMissing() {
        when(proxyService.fetch(eq(repo), eq("api/packages/path"), any(FetchOptions.class), anyMap()))
                .thenAnswer(inv -> Optional.of(storage.put(repo, "api/packages/path",
                        "<html>maintenance</html>".getBytes(StandardCharsets.UTF_8), "text/html", true)));
        assertThat(proxy.listing(repo, "path")).isEmpty();
    }

    @Test
    void advisoriesPassThroughUnlessSwitchedOff() {
        when(proxyService.fetch(eq(repo), eq("api/packages/http/advisories"), any(FetchOptions.class), anyMap()))
                .thenAnswer(inv -> Optional.of(storage.put(repo, "api/packages/http/advisories",
                        "{\"advisories\":[{\"id\":\"GHSA-x\"}],\"advisoriesUpdated\":\"2026-01-01T00:00:00Z\"}"
                                .getBytes(StandardCharsets.UTF_8), "application/json", true)));
        assertThat(proxy.advisories(repo, "http")).hasValueSatisfying(doc -> assertThat(doc.get("advisories")).hasSize(1));

        repo.setFormatSettings(Map.of(PubSettings.ADVISORIES, "false"));
        assertThat(proxy.advisories(repo, "other")).contains(PubListing.noAdvisories());
        verify(proxyService, never()).fetch(any(), eq("api/packages/other/advisories"), any(FetchOptions.class), anyMap());
    }

    @Test
    void advisoriesAreAnnouncedOnlyWhenTheyCanBeServed() {
        assertThat(proxy.advisoriesAvailable(repo, "http")).isTrue(); // upstream healthy
        when(proxyService.status(repo)).thenReturn(new UpstreamHealth.Status(false, 3, "down", null, Instant.now(), null));
        assertThat(proxy.advisoriesAvailable(repo, "http")).isFalse();
        storage.put(repo, "api/packages/http/advisories", "{}".getBytes(StandardCharsets.UTF_8), "application/json", true);
        assertThat(proxy.advisoriesAvailable(repo, "http")).isTrue(); // cached
        when(proxyService.isOffline(repo)).thenReturn(true);
        assertThat(proxy.advisoriesAvailable(repo, "http")).isTrue();
        assertThat(proxy.advisoriesAvailable(repo, "path")).isFalse(); // offline and not cached
        repo.setFormatSettings(Map.of(PubSettings.ADVISORIES, "false"));
        assertThat(proxy.advisoriesAvailable(repo, "http")).isFalse();
    }

    @Test
    void archivesComeFromTheListingsArchiveUrlAndAreLinkedToTheirComponent() throws Exception {
        URI upstream = URI.create("https://pub.dev/api/archives/path-1.9.1.tar.gz");
        cacheListing(listing(upstream.toString(), PubTestSupport.sha256(ARCHIVE)));
        upstreamServes(upstream, ARCHIVE);
        var content = proxy.archive(repo, "path", "1.9.1").orElseThrow();
        assertThat(content.readAllBytes()).isEqualTo(ARCHIVE);
        assertThat(content.asset().getComponentId()).isNotNull();
        assertThat(storage.components).containsKey(new StorageService.ComponentCoordinates("", "path", "1.9.1"));
    }

    @Test
    void cachedArchivesNeverTouchTheProxy() throws Exception {
        storage.put(repo, "packages/path/versions/1.9.1.tar.gz", ARCHIVE, "application/gzip", false);
        assertThat(proxy.archive(repo, "path", "1.9.1")).isPresent();
        verify(proxyService, never()).fetch(any(), anyString(), any(URI.class), any(), anyMap());
        verify(proxyService, never()).fetch(any(), anyString(), any(FetchOptions.class), anyMap());
    }

    @Test
    void anArchiveThatDoesNotMatchItsChecksumIsNotCached() {
        URI upstream = URI.create("https://pub.dev/api/archives/path-1.9.1.tar.gz");
        cacheListing(listing(upstream.toString(), "0".repeat(64)));
        upstreamServes(upstream, ARCHIVE);
        assertThatThrownBy(() -> proxy.archive(repo, "path", "1.9.1"))
                .isInstanceOfSatisfying(PubError.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).contains("does not match its archive_sha256");
                });
        assertThat(storage.find(repo, "packages/path/versions/1.9.1.tar.gz")).isEmpty();
    }

    @Test
    void withoutAListingTheConventionalLocationIsUsed() throws Exception {
        URI conventional = URI.create("https://pub.dev/packages/path/versions/1.9.1.tar.gz");
        upstreamServes(conventional, ARCHIVE);
        assertThat(proxy.archive(repo, "path", "1.9.1")).isPresent();
    }

    @Test
    void aMissingVersionInTheCachedListingRefreshesTheListing() throws Exception {
        cacheListing("{\"name\":\"path\",\"versions\":[]}");
        URI upstream = URI.create("https://pub.dev/api/archives/path-1.9.1.tar.gz");
        when(proxyService.fetch(eq(repo), eq("api/packages/path"), any(FetchOptions.class), anyMap()))
                .thenAnswer(inv -> Optional.of(storage.put(repo, "api/packages/path",
                        listing(upstream.toString(), PubTestSupport.sha256(ARCHIVE)).getBytes(StandardCharsets.UTF_8),
                        "application/json", true)));
        upstreamServes(upstream, ARCHIVE);
        assertThat(proxy.archive(repo, "path", "1.9.1")).isPresent();
    }

    @Test
    void nothingFetchedMeansEmpty() {
        assertThat(proxy.archive(repo, "path", "1.9.1")).isEmpty();
    }

    @Test
    void archiveUrlsAreOnlyFollowedWhereItIsSafe() {
        URI conventional = URI.create("https://pub.dev/packages/path/versions/1.9.1.tar.gz");
        // same host: always
        assertThat(proxy.upstreamArchiveUri(repo, "path", "1.9.1", "https://pub.dev/api/archives/path-1.9.1.tar.gz"))
                .isEqualTo(URI.create("https://pub.dev/api/archives/path-1.9.1.tar.gz"));
        // another host without upstream credentials: followed (signed CDN URLs)
        assertThat(proxy.upstreamArchiveUri(repo, "path", "1.9.1", "https://cdn.example.com/a.tar.gz?sig=1"))
                .isEqualTo(URI.create("https://cdn.example.com/a.tar.gz?sig=1"));
        // relative URLs resolve against the remote
        assertThat(proxy.upstreamArchiveUri(repo, "path", "1.9.1", "/files/path-1.9.1.tar.gz"))
                .isEqualTo(URI.create("https://pub.dev/files/path-1.9.1.tar.gz"));
        // nonsense falls back
        assertThat(proxy.upstreamArchiveUri(repo, "path", "1.9.1", "ftp://pub.dev/x")).isEqualTo(conventional);
        assertThat(proxy.upstreamArchiveUri(repo, "path", "1.9.1", "http://[broken")).isEqualTo(conventional);
        assertThat(proxy.upstreamArchiveUri(repo, "path", "1.9.1", "")).isEqualTo(conventional);
        assertThat(proxy.upstreamArchiveUri(repo, "path", "1.9.1", null)).isEqualTo(conventional);

        // with credentials, never to another host (or port)
        repo.setRemoteToken("secret");
        assertThat(proxy.upstreamArchiveUri(repo, "path", "1.9.1", "https://cdn.example.com/a.tar.gz")).isEqualTo(conventional);
        assertThat(proxy.upstreamArchiveUri(repo, "path", "1.9.1", "https://pub.dev:8443/a.tar.gz")).isEqualTo(conventional);
        assertThat(proxy.upstreamArchiveUri(repo, "path", "1.9.1", "https://PUB.DEV/a.tar.gz"))
                .isEqualTo(URI.create("https://PUB.DEV/a.tar.gz"));
        repo.setRemoteToken(null);
        repo.setRemoteUsername("user");
        assertThat(proxy.upstreamArchiveUri(repo, "path", "1.9.1", "https://cdn.example.com/a.tar.gz")).isEqualTo(conventional);
    }
}
