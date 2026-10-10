package dev.granary.format.docker;

import static dev.granary.format.docker.DockerTestSupport.MAPPER;
import static dev.granary.format.docker.DockerTestSupport.proxy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.OngoingStubbing;

class DockerUpstreamTest {

    private static final String HUB = "https://registry-1.docker.io";
    private static final String BEARER = "Bearer realm=\"https://auth.docker.io/token\",service=\"registry.docker.io\"";

    private final UpstreamClient client = mock(UpstreamClient.class);
    private final ProxyService proxy = mock(ProxyService.class);
    private final StorageService storage = mock(StorageService.class);
    private final UpstreamHealth health = mock(UpstreamHealth.class);
    private final NegativeCache negativeCache = mock(NegativeCache.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final DockerUpstream upstream = new DockerUpstream(client, proxy, storage, health, negativeCache, MAPPER, clock);

    {
        when(health.status(any())).thenReturn(new UpstreamHealth.Status(true, 0, null, null, null, null));
    }
    private final Repository hub = proxy("docker-hub", HUB);

    private OngoingStubbing<UpstreamClient.Response> stubGet(URI uri, UpstreamClient.Response first, UpstreamClient.Response... rest)
            throws Exception {
        return when(client.getAbsolute(any(), eq(uri), anyMap(), anyMap())).thenReturn(first, rest);
    }

    private OngoingStubbing<UpstreamClient.Response> stubAny(UpstreamClient.Response first, UpstreamClient.Response... rest)
            throws Exception {
        return when(client.getAbsolute(any(), any(), anyMap(), anyMap())).thenReturn(first, rest);
    }

    private static UpstreamClient.Response response(int status, String body, Map<String, String> headers) {
        UpstreamClient.Response r = mock(UpstreamClient.Response.class);
        when(r.status()).thenReturn(status);
        when(r.body()).thenAnswer(inv -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(r.header(any())).thenAnswer(inv -> Optional.ofNullable(headers.get(inv.getArgument(0))));
        return r;
    }

    @Test
    void libraryPrefixDefaultsToHubOnly() {
        assertThat(upstream.libraryPrefix(hub)).isTrue();
        assertThat(upstream.upstreamName(hub, "alpine")).isEqualTo("library/alpine");
        assertThat(upstream.upstreamName(hub, "acme/app")).isEqualTo("acme/app");
        assertThat(upstream.upstreamName(hub, "library/alpine")).isEqualTo("library/alpine");

        Repository ghcr = proxy("ghcr", "https://ghcr.io");
        assertThat(upstream.libraryPrefix(ghcr)).isFalse();
        assertThat(upstream.upstreamName(ghcr, "alpine")).isEqualTo("alpine");
        ghcr.setFormatSettings(Map.of(DockerUpstream.LIBRARY_PREFIX_SETTING, "true"));
        assertThat(upstream.upstreamName(ghcr, "alpine")).isEqualTo("library/alpine");
        hub.setFormatSettings(Map.of(DockerUpstream.LIBRARY_PREFIX_SETTING, "false"));
        assertThat(upstream.upstreamName(hub, "alpine")).isEqualTo("alpine");

        assertThat(DockerUpstream.isDockerHub(proxy("x", "https://index.docker.io/v1/"))).isTrue();
        assertThat(DockerUpstream.isDockerHub(proxy("x", "https://docker.io"))).isTrue();
        assertThat(DockerUpstream.isDockerHub(proxy("x", "https://notdocker.io"))).isFalse();
        assertThat(DockerUpstream.isDockerHub(proxy("x", "::bad::"))).isFalse();
        assertThat(DockerUpstream.isDockerHub(proxy("x", null))).isFalse();
    }

    @Test
    void v2BaseToleratesTrailingSlashAndV2() {
        assertThat(upstream.v2Base(proxy("a", "https://ghcr.io")).toString()).isEqualTo("https://ghcr.io/v2/");
        assertThat(upstream.v2Base(proxy("a", "https://ghcr.io/")).toString()).isEqualTo("https://ghcr.io/v2/");
        assertThat(upstream.v2Base(proxy("a", "https://ghcr.io/v2")).toString()).isEqualTo("https://ghcr.io/v2/");
        assertThat(upstream.v2Base(proxy("a", "https://ghcr.io/v2/")).toString()).isEqualTo("https://ghcr.io/v2/");
        assertThat(upstream.v2Base(proxy("a", "http://registry.test:5000/prefix/")).toString()).isEqualTo("http://registry.test:5000/prefix/v2/");
        assertThat(upstream.uri(hub, "library/alpine/manifests/3.20").toString())
                .isEqualTo("https://registry-1.docker.io/v2/library/alpine/manifests/3.20");
    }

    @Test
    void parsesChallenges() {
        DockerUpstream.Challenge c = DockerUpstream.parseChallenge(BEARER);
        assertThat(c.scheme()).isEqualTo(DockerUpstream.Scheme.BEARER);
        assertThat(c.realm()).isEqualTo("https://auth.docker.io/token");
        assertThat(c.service()).isEqualTo("registry.docker.io");

        DockerUpstream.Challenge ghcr = DockerUpstream.parseChallenge(
                "Bearer realm=\"https://ghcr.io/token\",service=\"ghcr.io\",scope=\"repository:user/image:pull\"");
        assertThat(ghcr.realm()).isEqualTo("https://ghcr.io/token");
        assertThat(ghcr.service()).isEqualTo("ghcr.io");

        assertThat(DockerUpstream.parseChallenge("bearer realm=https://t.test/token, service=svc").service()).isEqualTo("svc");
        assertThat(DockerUpstream.parseChallenge("Basic realm=\"Registry\"").scheme()).isEqualTo(DockerUpstream.Scheme.BASIC);
        assertThat(DockerUpstream.parseChallenge("Bearer service=\"x\"")).isEqualTo(DockerUpstream.Challenge.NONE);
        assertThat(DockerUpstream.parseChallenge("Digest realm=\"x\"")).isEqualTo(DockerUpstream.Challenge.NONE);
        assertThat(DockerUpstream.parseChallenge("")).isEqualTo(DockerUpstream.Challenge.NONE);
        assertThat(DockerUpstream.parseChallenge(null)).isEqualTo(DockerUpstream.Challenge.NONE);
    }

    @Test
    void tokenUris() {
        DockerUpstream.Challenge c = DockerUpstream.parseChallenge(BEARER);
        assertThat(DockerUpstream.tokenUri(c, "repository:library/alpine:pull").toString())
                .isEqualTo("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Falpine%3Apull");
        DockerUpstream.Challenge withQuery = new DockerUpstream.Challenge(DockerUpstream.Scheme.BEARER, "https://t.test/token?x=1", null);
        assertThat(DockerUpstream.tokenUri(withQuery, "s").toString()).isEqualTo("https://t.test/token?x=1&scope=s");
        assertThat(DockerUpstream.tokenUri(DockerUpstream.Challenge.NONE, "s")).isNull();
    }

    @Test
    void bearerFlowProbesExchangesAndCachesTheToken() throws Exception {
        stubGet(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        stubGet(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Falpine%3Apull"), response(200, "{\"token\":\"tok-1\",\"expires_in\":300}", Map.of()));
        StoredContent cached = mock(StoredContent.class);
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.of(cached));

        Optional<StoredContent> first = upstream.fetch(hub, "tags/library/alpine/3.20", "library/alpine/manifests/3.20",
                "library/alpine", FetchOptions.index(), MediaTypes.DEFAULT_ACCEPT);
        assertThat(first).contains(cached);

        ArgumentCaptor<Repository> repoUsed = ArgumentCaptor.forClass(Repository.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(proxy).fetch(repoUsed.capture(), eq("tags/library/alpine/3.20"),
                eq(URI.create(HUB + "/v2/library/alpine/manifests/3.20")), eq(FetchOptions.index()), headers.capture());
        assertThat(headers.getValue()).containsEntry("Authorization", "Bearer tok-1")
                .containsEntry("Accept", MediaTypes.DEFAULT_ACCEPT);
        assertThat(repoUsed.getValue().getId()).isEqualTo(hub.getId());
        assertThat(repoUsed.getValue().getRemoteUrl()).isEqualTo(HUB);

        // second fetch for the same image: no new probe, no new token request
        upstream.fetch(hub, "blobs/sha256/abc", "library/alpine/blobs/sha256:abc", "library/alpine", FetchOptions.artifact(), "*/*");
        verify(client, times(1)).getAbsolute(any(), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap());
        verify(client, times(1)).getAbsolute(any(), eq(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Falpine%3Apull")), anyMap(), anyMap());

        // after the token expired a fresh one is requested
        now.set(now.get().plus(Duration.ofSeconds(300)));
        upstream.fetch(hub, "blobs/sha256/abc", "library/alpine/blobs/sha256:abc", "library/alpine", FetchOptions.artifact(), "*/*");
        verify(client, times(2)).getAbsolute(any(), eq(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Falpine%3Apull")), anyMap(), anyMap());
        verify(client, times(1)).getAbsolute(any(), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap());

        // and after the challenge TTL the upstream is probed again
        now.set(now.get().plus(DockerUpstream.CHALLENGE_TTL));
        upstream.fetch(hub, "blobs/sha256/abc", "library/alpine/blobs/sha256:abc", "library/alpine", FetchOptions.artifact(), "*/*");
        verify(client, times(2)).getAbsolute(any(), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap());
    }

    @Test
    void credentialsAreStrippedWhenABearerTokenIsSent() throws Exception {
        hub.setRemoteUsername("user");
        hub.setRemotePassword("secret");
        stubGet(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        stubGet(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Aacme%2Fapp%3Apull"), response(200, "{\"access_token\":\"tok-2\"}", Map.of()));
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());

        upstream.fetch(hub, "p", "acme/app/manifests/1", "acme/app", FetchOptions.index(), null);

        ArgumentCaptor<Repository> probeRepo = ArgumentCaptor.forClass(Repository.class);
        verify(client).getAbsolute(probeRepo.capture(), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap());
        assertThat(probeRepo.getValue().getRemoteUsername()).isNull(); // the probe must not leak Basic credentials
        ArgumentCaptor<Repository> tokenRepo = ArgumentCaptor.forClass(Repository.class);
        verify(client).getAbsolute(tokenRepo.capture(), eq(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Aacme%2Fapp%3Apull")), anyMap(), anyMap());
        assertThat(tokenRepo.getValue().getRemoteUsername()).isEqualTo("user"); // the token request carries them
        ArgumentCaptor<Repository> fetchRepo = ArgumentCaptor.forClass(Repository.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(proxy).fetch(fetchRepo.capture(), eq("p"), any(), any(), headers.capture());
        assertThat(fetchRepo.getValue().getRemoteUsername()).isNull();
        assertThat(fetchRepo.getValue().getRemotePassword()).isNull();
        assertThat(headers.getValue()).containsEntry("Authorization", "Bearer tok-2").containsEntry("Accept", "*/*");
    }

    @Test
    void noChallengeMeansPlainFetchWithRepositoryCredentials() throws Exception {
        Repository internal = proxy("internal", "http://registry.test:5000");
        internal.setRemoteUsername("u");
        internal.setRemotePassword("p");
        stubGet(URI.create("http://registry.test:5000/v2/"), response(200, "{}", Map.of()));
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());

        upstream.fetch(internal, "p", "app/manifests/1", "app", FetchOptions.index(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(proxy).fetch(eq(internal), eq("p"), eq(URI.create("http://registry.test:5000/v2/app/manifests/1")), any(), headers.capture());
        assertThat(headers.getValue()).doesNotContainKey("Authorization");
        verify(client, times(1)).getAbsolute(any(), any(), anyMap(), anyMap());
    }

    @Test
    void basicChallengeLeavesAuthorisationToTheClient() throws Exception {
        stubAny(response(401, "", Map.of("WWW-Authenticate", "Basic realm=\"Registry\"")));
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());
        upstream.fetch(hub, "p", "x/manifests/1", "x", FetchOptions.index(), null);
        verify(proxy).fetch(eq(hub), eq("p"), any(), any(), anyMap());
        verify(client, times(1)).getAbsolute(any(), any(), anyMap(), anyMap());
    }

    @Test
    void preIssuedRemoteTokenSkipsTheExchange() throws Exception {
        hub.setRemoteToken("pre-issued");
        stubGet(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());
        upstream.fetch(hub, "p", "x/manifests/1", "x", FetchOptions.index(), null);
        verify(proxy).fetch(eq(hub), eq("p"), any(), any(), anyMap());
        verify(client, never()).getAbsolute(any(), eq(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Ax%3Apull")), anyMap(), anyMap());
    }

    @Test
    void tokenFailuresFallBackToAnAnonymousFetch() throws Exception {
        stubGet(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        UpstreamClient.Response serverError = response(500, "boom", Map.of());
        UpstreamClient.Response noToken = response(200, "{\"nope\":true}", Map.of());
        UpstreamClient.Response notJson = response(200, "not json", Map.of());
        stubGet(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Ax%3Apull"),
                serverError, noToken, notJson).thenThrow(new IOException("down"));
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());
        for (int i = 0; i < 4; i++) {
            upstream.fetch(hub, "p", "x/manifests/1", "x", FetchOptions.index(), null);
        }
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(proxy, times(4)).fetch(eq(hub), eq("p"), any(), any(), headers.capture());
        assertThat(headers.getAllValues()).allSatisfy(h -> assertThat(h).doesNotContainKey("Authorization"));
    }

    @Test
    void unreachableUpstreamIsRetriedSoon() throws Exception {
        UpstreamClient.Response unavailable = response(503, "", Map.of());
        UpstreamClient.Response challenge = response(401, "", Map.of("WWW-Authenticate", BEARER));
        when(client.getAbsolute(any(), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap()))
                .thenThrow(new IOException("connect refused"))
                .thenReturn(unavailable, challenge);
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());

        assertThat(upstream.challenge(hub)).isEqualTo(DockerUpstream.Challenge.NONE);
        assertThat(upstream.challenge(hub)).isEqualTo(DockerUpstream.Challenge.NONE); // cached for a minute
        now.set(now.get().plus(DockerUpstream.CHALLENGE_RETRY));
        assertThat(upstream.challenge(hub)).isEqualTo(DockerUpstream.Challenge.NONE); // 503 -> retry soon too
        now.set(now.get().plus(DockerUpstream.CHALLENGE_RETRY));
        assertThat(upstream.challenge(hub).scheme()).isEqualTo(DockerUpstream.Scheme.BEARER);
        verify(client, times(3)).getAbsolute(any(), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap());
    }

    @Test
    void changedRemoteUrlAndForgetDropTheCache() throws Exception {
        stubAny(response(200, "{}", Map.of()));
        upstream.challenge(hub);
        hub.setRemoteUrl("https://ghcr.io");
        upstream.challenge(hub);
        verify(client, times(2)).getAbsolute(any(), any(), anyMap(), anyMap());
        upstream.forget(hub);
        upstream.challenge(hub);
        verify(client, times(3)).getAbsolute(any(), any(), anyMap(), anyMap());
    }

    @Test
    void offlineRepositoriesAreNeverProbed() throws Exception {
        when(proxy.isOffline(hub)).thenReturn(true);
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());
        upstream.fetch(hub, "p", "x/manifests/1", "x", FetchOptions.index(), null);
        verify(client, never()).getAbsolute(any(), any(), anyMap(), anyMap());
        verify(proxy).fetch(eq(hub), eq("p"), any(), any(), anyMap());
    }

    @Test
    void shortLivedTokensAreKeptForAMinimum() throws Exception {
        stubGet(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        stubGet(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Ax%3Apull"), response(200, "{\"token\":\"t\",\"expires_in\":5}", Map.of()));
        DockerUpstream.Challenge challenge = upstream.challenge(hub);
        assertThat(upstream.token(hub, challenge, "x")).contains("t");
        now.set(now.get().plus(Duration.ofSeconds(9)));
        assertThat(upstream.token(hub, challenge, "x")).contains("t");
        verify(client, times(1)).getAbsolute(any(), eq(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Ax%3Apull")), anyMap(), anyMap());
        now.set(now.get().plus(Duration.ofSeconds(2)));
        assertThat(upstream.token(hub, challenge, "x")).contains("t");
        verify(client, times(2)).getAbsolute(any(), eq(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Ax%3Apull")), anyMap(), anyMap());
    }

    @Test
    void copyWithoutCredentialsKeepsEverythingElse() {
        hub.setRemoteUsername("u");
        hub.setRemotePassword("p");
        hub.setRemoteToken("t");
        hub.setOfflineMode(true);
        hub.setReadTimeoutSeconds(7);
        hub.setMetadataMaxAgeMinutes(3);
        hub.setFormatSettings(Map.of("docker.libraryPrefix", "false"));
        Repository copy = DockerUpstream.withoutCredentials(hub);
        assertThat(copy.getId()).isEqualTo(hub.getId());
        assertThat(copy.getName()).isEqualTo(hub.getName());
        assertThat(copy.getType()).isEqualTo(hub.getType());
        assertThat(copy.getBlobStore()).isEqualTo(hub.getBlobStore());
        assertThat(copy.getRemoteUrl()).isEqualTo(HUB);
        assertThat(copy.isOfflineMode()).isTrue();
        assertThat(copy.getReadTimeoutSeconds()).isEqualTo(7);
        assertThat(copy.getMetadataMaxAgeMinutes()).isEqualTo(3);
        assertThat(copy.getFormatSettings()).containsEntry("docker.libraryPrefix", "false");
        assertThat(copy.getRemoteUsername()).isNull();
        assertThat(copy.getRemotePassword()).isNull();
        assertThat(copy.getRemoteToken()).isNull();
    }

    @Test
    void deniedImagesAreMissesNotOutages() throws Exception {
        stubGet(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        stubGet(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Fghost%3Apull"),
                response(200, "{\"token\":\"t\"}", Map.of()));
        when(proxy.negativeTtl(hub)).thenReturn(Duration.ofMinutes(5));
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());
        // ProxyService recorded "HTTP 401 for <path>" during the fetch
        when(health.status(hub))
                .thenReturn(new UpstreamHealth.Status(true, 0, null, null, null, null))
                .thenReturn(new UpstreamHealth.Status(true, 1, "HTTP 401 for tags/library/ghost/latest", null, Instant.now(), null));

        assertThat(upstream.fetch(hub, "tags/library/ghost/latest", "library/ghost/manifests/latest", "library/ghost",
                FetchOptions.index(), null)).isEmpty();
        verify(health).recordSuccess(hub);
        verify(negativeCache).put(hub.getId(), "tags/library/ghost/latest", Duration.ofMinutes(5));
        // the challenge and the token are forgotten so the next request negotiates afresh
        upstream.fetch(hub, "tags/library/ghost/latest", "library/ghost/manifests/latest", "library/ghost", FetchOptions.index(), null);
        verify(client, times(2)).getAbsolute(any(), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap());
    }

    @Test
    void realOutagesStayOutages() throws Exception {
        stubGet(URI.create(HUB + "/v2/"), response(200, "{}", Map.of()));
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());
        when(health.status(hub))
                .thenReturn(new UpstreamHealth.Status(true, 0, null, null, null, null))
                .thenReturn(new UpstreamHealth.Status(true, 1, "HTTP 500 for p", null, Instant.now(), null))
                .thenReturn(new UpstreamHealth.Status(true, 1, "HTTP 500 for p", null, Instant.now(), null))
                .thenReturn(new UpstreamHealth.Status(true, 1, "HTTP 500 for p", null, Instant.now(), null));
        upstream.fetch(hub, "p", "x/manifests/1", "x", FetchOptions.index(), null);
        upstream.fetch(hub, "p", "x/manifests/1", "x", FetchOptions.index(), null); // 404 remembered: failures unchanged
        verify(health, never()).recordSuccess(any());
        verify(negativeCache, never()).put(any(), any(), any());

        // a 401 without a bearer token (basic credentials refused) restores the health but remembers nothing
        when(health.status(hub))
                .thenReturn(new UpstreamHealth.Status(true, 1, "HTTP 500 for p", null, Instant.now(), null))
                .thenReturn(new UpstreamHealth.Status(true, 2, "HTTP 401 for p", null, Instant.now(), null));
        upstream.fetch(hub, "p", "x/manifests/1", "x", FetchOptions.index(), null);
        verify(health).recordSuccess(hub);
        verify(negativeCache, never()).put(any(), any(), any());
    }

    private StoredContent cachedAsset(String path, Instant verified) {
        Asset asset = new Asset();
        asset.setPath(path);
        asset.setLastVerifiedAt(verified);
        StoredContent content = mock(StoredContent.class);
        when(content.asset()).thenReturn(asset);
        when(storage.find(hub, path)).thenReturn(Optional.of(content));
        return content;
    }

    @Test
    void cachedContentNeedsNeitherProbeNorToken() throws Exception {
        // the registry and its token service are unreachable: the cache must not wait for either of them
        when(client.getAbsolute(any(), any(), anyMap(), anyMap())).thenThrow(new IOException("connect timed out"));
        when(proxy.metadataMaxAge(hub)).thenReturn(Duration.ofMinutes(60));
        StoredContent blob = cachedAsset("blobs/sha256/abc", now.get().minus(Duration.ofDays(400)));
        StoredContent manifest = cachedAsset("manifests/library/alpine/sha256/def", now.get().minus(Duration.ofDays(400)));
        StoredContent freshTag = cachedAsset("tags/library/alpine/3.20", now.get().minus(Duration.ofMinutes(5)));
        when(proxy.fetch(any(), eq("blobs/sha256/abc"), any(), any(), anyMap())).thenReturn(Optional.of(blob));
        when(proxy.fetch(any(), eq("manifests/library/alpine/sha256/def"), any(), any(), anyMap())).thenReturn(Optional.of(manifest));
        when(proxy.fetch(any(), eq("tags/library/alpine/3.20"), any(), any(), anyMap())).thenReturn(Optional.of(freshTag));

        assertThat(upstream.fetch(hub, "blobs/sha256/abc", "library/alpine/blobs/sha256:abc", "library/alpine",
                FetchOptions.artifact(), "*/*")).contains(blob);
        assertThat(upstream.fetch(hub, "manifests/library/alpine/sha256/def", "library/alpine/manifests/sha256:def",
                "library/alpine", FetchOptions.artifact(), MediaTypes.DEFAULT_ACCEPT)).contains(manifest);
        assertThat(upstream.fetch(hub, "tags/library/alpine/3.20", "library/alpine/manifests/3.20", "library/alpine",
                FetchOptions.index(), MediaTypes.DEFAULT_ACCEPT)).contains(freshTag);
        verify(client, never()).getAbsolute(any(), any(), anyMap(), anyMap());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(proxy, times(3)).fetch(eq(hub), anyString(), any(), any(), headers.capture());
        assertThat(headers.getAllValues()).allSatisfy(h -> assertThat(h).doesNotContainKey("Authorization"));
    }

    @Test
    void staleMetadataIsRevalidatedWithAToken() throws Exception {
        stubGet(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        stubGet(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Falpine%3Apull"),
                response(200, "{\"token\":\"tok-1\",\"expires_in\":300}", Map.of()));
        when(proxy.metadataMaxAge(hub)).thenReturn(Duration.ofMinutes(60));
        StoredContent staleTag = cachedAsset("tags/library/alpine/3.20", now.get().minus(Duration.ofMinutes(61)));
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.of(staleTag));

        upstream.fetch(hub, "tags/library/alpine/3.20", "library/alpine/manifests/3.20", "library/alpine",
                FetchOptions.index(), MediaTypes.DEFAULT_ACCEPT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(proxy).fetch(any(), eq("tags/library/alpine/3.20"), any(), any(), headers.capture());
        assertThat(headers.getValue()).containsEntry("Authorization", "Bearer tok-1");
    }

    @Test
    void rememberedMissesAndAnOpenCircuitSkipTheRegistry() throws Exception {
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());
        when(negativeCache.contains(hub.getId(), "tags/library/ghost/latest")).thenReturn(true);
        assertThat(upstream.fetch(hub, "tags/library/ghost/latest", "library/ghost/manifests/latest", "library/ghost",
                FetchOptions.index(), null)).isEmpty();

        when(health.status(hub)).thenReturn(new UpstreamHealth.Status(false, 5, "IOException: down", null, Instant.now(),
                Instant.now().plus(Duration.ofMinutes(5))));
        assertThat(upstream.fetch(hub, "tags/library/alpine/3.21", "library/alpine/manifests/3.21", "library/alpine",
                FetchOptions.index(), null)).isEmpty();
        verify(client, never()).getAbsolute(any(), any(), anyMap(), anyMap());
        verify(health, never()).recordSuccess(any());
        verify(proxy, times(2)).fetch(eq(hub), anyString(), any(), any(), anyMap());
    }

    @Test
    void aConcurrentlyCachedPathIsReadBackInsteadOfFailing() throws Exception {
        stubGet(URI.create(HUB + "/v2/"), response(200, "{}", Map.of()));
        StoredContent cached = mock(StoredContent.class);
        when(proxy.fetch(any(), any(), any(), any(), anyMap()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_asset_path"))
                .thenReturn(Optional.of(cached));
        assertThat(upstream.fetch(hub, "blobs/sha256/abc", "library/alpine/blobs/sha256:abc", "library/alpine",
                FetchOptions.artifact(), "*/*")).contains(cached);
        verify(proxy, times(2)).fetch(eq(hub), eq("blobs/sha256/abc"), any(), any(), anyMap());
    }
}
