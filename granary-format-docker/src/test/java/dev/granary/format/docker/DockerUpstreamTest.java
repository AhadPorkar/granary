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
import static org.mockito.Mockito.verifyNoInteractions;
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

    /** The anonymous {@code GET /v2/} probe. */
    private OngoingStubbing<UpstreamClient.Response> stubProbe(URI uri, UpstreamClient.Response first, UpstreamClient.Response... rest)
            throws Exception {
        return when(client.getAnonymous(any(), eq(uri), anyMap(), anyMap())).thenReturn(first, rest);
    }

    /** The token request at the realm, the one request that carries the credentials to another origin. */
    private OngoingStubbing<UpstreamClient.Response> stubToken(URI uri, UpstreamClient.Response first, UpstreamClient.Response... rest)
            throws Exception {
        return when(client.getWithCredentials(any(), eq(uri), anyMap(), anyMap())).thenReturn(first, rest);
    }

    private OngoingStubbing<UpstreamClient.Response> stubAnyProbe(UpstreamClient.Response first, UpstreamClient.Response... rest)
            throws Exception {
        return when(client.getAnonymous(any(), any(), anyMap(), anyMap())).thenReturn(first, rest);
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
        stubProbe(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        stubToken(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Falpine%3Apull"), response(200, "{\"token\":\"tok-1\",\"expires_in\":300}", Map.of()));
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
        verify(client, times(1)).getAnonymous(any(), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap());
        verify(client, times(1)).getWithCredentials(any(), eq(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Falpine%3Apull")), anyMap(), anyMap());

        // after the token expired a fresh one is requested
        now.set(now.get().plus(Duration.ofSeconds(300)));
        upstream.fetch(hub, "blobs/sha256/abc", "library/alpine/blobs/sha256:abc", "library/alpine", FetchOptions.artifact(), "*/*");
        verify(client, times(2)).getWithCredentials(any(), eq(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Falpine%3Apull")), anyMap(), anyMap());
        verify(client, times(1)).getAnonymous(any(), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap());

        // and after the challenge TTL the upstream is probed again
        now.set(now.get().plus(DockerUpstream.CHALLENGE_TTL));
        upstream.fetch(hub, "blobs/sha256/abc", "library/alpine/blobs/sha256:abc", "library/alpine", FetchOptions.artifact(), "*/*");
        verify(client, times(2)).getAnonymous(any(), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap());
    }

    @Test
    void onlyTheTokenRequestCarriesTheCredentialsToTheRealm() throws Exception {
        hub.setRemoteUsername("user");
        hub.setRemotePassword("secret");
        stubProbe(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        stubToken(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Aacme%2Fapp%3Apull"), response(200, "{\"access_token\":\"tok-2\"}", Map.of()));
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());

        upstream.fetch(hub, "p", "acme/app/manifests/1", "acme/app", FetchOptions.index(), null);

        // the probe must not leak Basic credentials: it is anonymous
        verify(client).getAnonymous(eq(hub), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap());
        // the realm the registry named gets them deliberately
        verify(client).getWithCredentials(eq(hub), eq(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Aacme%2Fapp%3Apull")), anyMap(), anyMap());
        // the registry gets the token, which replaces the credentials in UpstreamClient
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(proxy).fetch(eq(hub), eq("p"), any(), any(), headers.capture());
        assertThat(headers.getValue()).containsEntry("Authorization", "Bearer tok-2").containsEntry("Accept", "*/*");
        verify(client, never()).getAbsolute(any(), any(), anyMap(), anyMap());
    }

    @Test
    void noChallengeMeansPlainFetchWithRepositoryCredentials() throws Exception {
        Repository internal = proxy("internal", "http://registry.test:5000");
        internal.setRemoteUsername("u");
        internal.setRemotePassword("p");
        stubProbe(URI.create("http://registry.test:5000/v2/"), response(200, "{}", Map.of()));
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());

        upstream.fetch(internal, "p", "app/manifests/1", "app", FetchOptions.index(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(proxy).fetch(eq(internal), eq("p"), eq(URI.create("http://registry.test:5000/v2/app/manifests/1")), any(), headers.capture());
        assertThat(headers.getValue()).doesNotContainKey("Authorization");
        verify(client, times(1)).getAnonymous(any(), any(), anyMap(), anyMap());
        verify(client, never()).getWithCredentials(any(), any(), anyMap(), anyMap());
    }

    @Test
    void basicChallengeLeavesAuthorisationToTheClient() throws Exception {
        stubAnyProbe(response(401, "", Map.of("WWW-Authenticate", "Basic realm=\"Registry\"")));
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());
        upstream.fetch(hub, "p", "x/manifests/1", "x", FetchOptions.index(), null);
        verify(proxy).fetch(eq(hub), eq("p"), any(), any(), anyMap());
        verify(client, times(1)).getAnonymous(any(), any(), anyMap(), anyMap());
        verify(client, never()).getWithCredentials(any(), any(), anyMap(), anyMap());
    }

    @Test
    void preIssuedRemoteTokenSkipsTheExchange() throws Exception {
        hub.setRemoteToken("pre-issued");
        stubProbe(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());
        upstream.fetch(hub, "p", "x/manifests/1", "x", FetchOptions.index(), null);
        verify(proxy).fetch(eq(hub), eq("p"), any(), any(), anyMap());
        verify(client, never()).getWithCredentials(any(), eq(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Ax%3Apull")), anyMap(), anyMap());
    }

    @Test
    void tokenFailuresFallBackToAnAnonymousFetch() throws Exception {
        stubProbe(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        UpstreamClient.Response serverError = response(500, "boom", Map.of());
        UpstreamClient.Response noToken = response(200, "{\"nope\":true}", Map.of());
        UpstreamClient.Response notJson = response(200, "not json", Map.of());
        stubToken(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Ax%3Apull"),
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
        when(client.getAnonymous(any(), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap()))
                .thenThrow(new IOException("connect refused"))
                .thenReturn(unavailable, challenge);
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());

        assertThat(upstream.challenge(hub)).isEqualTo(DockerUpstream.Challenge.NONE);
        assertThat(upstream.challenge(hub)).isEqualTo(DockerUpstream.Challenge.NONE); // cached for a minute
        now.set(now.get().plus(DockerUpstream.CHALLENGE_RETRY));
        assertThat(upstream.challenge(hub)).isEqualTo(DockerUpstream.Challenge.NONE); // 503 -> retry soon too
        now.set(now.get().plus(DockerUpstream.CHALLENGE_RETRY));
        assertThat(upstream.challenge(hub).scheme()).isEqualTo(DockerUpstream.Scheme.BEARER);
        verify(client, times(3)).getAnonymous(any(), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap());
    }

    @Test
    void changedRemoteUrlAndForgetDropTheCache() throws Exception {
        stubAnyProbe(response(200, "{}", Map.of()));
        upstream.challenge(hub);
        hub.setRemoteUrl("https://ghcr.io");
        upstream.challenge(hub);
        verify(client, times(2)).getAnonymous(any(), any(), anyMap(), anyMap());
        upstream.forget(hub);
        upstream.challenge(hub);
        verify(client, times(3)).getAnonymous(any(), any(), anyMap(), anyMap());
    }

    @Test
    void aTokenNeverGoesToAnotherRegistryOrOutlivesTheCredentialsItWasIssuedFor() throws Exception {
        hub.setRemoteUsername("user");
        hub.setRemotePassword("secret");
        URI hubToken = URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Aacme%2Fapp%3Apull");
        URI ghcrToken = URI.create("https://ghcr.io/token?service=ghcr.io&scope=repository%3Aacme%2Fapp%3Apull");
        stubProbe(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        stubProbe(URI.create("https://ghcr.io/v2/"),
                response(401, "", Map.of("WWW-Authenticate", "Bearer realm=\"https://ghcr.io/token\",service=\"ghcr.io\"")));
        stubToken(hubToken, response(200, "{\"token\":\"hub-1\",\"expires_in\":3600}", Map.of()),
                response(200, "{\"token\":\"hub-2\",\"expires_in\":3600}", Map.of()));
        stubToken(ghcrToken, response(200, "{\"token\":\"ghcr-1\",\"expires_in\":3600}", Map.of()));
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);

        upstream.fetch(hub, "p1", "acme/app/manifests/1", "acme/app", FetchOptions.index(), null);
        // the administrator points the repository at another registry while Docker Hub's token is still valid
        hub.setRemoteUrl("https://ghcr.io");
        upstream.fetch(hub, "p2", "acme/app/manifests/1", "acme/app", FetchOptions.index(), null);
        verify(proxy).fetch(eq(hub), eq("p1"), eq(URI.create(HUB + "/v2/acme/app/manifests/1")), any(), headers.capture());
        verify(proxy).fetch(eq(hub), eq("p2"), eq(URI.create("https://ghcr.io/v2/acme/app/manifests/1")), any(), headers.capture());
        assertThat(headers.getAllValues()).extracting(h -> h.get("Authorization"))
                .containsExactly("Bearer hub-1", "Bearer ghcr-1");

        // back on Docker Hub with a new password: a token the old password obtained is not used again
        hub.setRemoteUrl(HUB);
        hub.setRemotePassword("rotated");
        upstream.fetch(hub, "p3", "acme/app/manifests/1", "acme/app", FetchOptions.index(), null);
        verify(proxy).fetch(eq(hub), eq("p3"), any(), any(), headers.capture());
        assertThat(headers.getValue()).containsEntry("Authorization", "Bearer hub-2");
        verify(client, times(2)).getWithCredentials(any(), eq(hubToken), anyMap(), anyMap());
        verify(client, times(1)).getWithCredentials(any(), eq(ghcrToken), anyMap(), anyMap());
    }

    @Test
    void aChallengeFromAHostTheProbeWasRedirectedToDoesNotNameTheRealm() throws Exception {
        hub.setRemoteUsername("user");
        hub.setRemotePassword("secret");
        UpstreamClient.Response foreign = response(401, "",
                Map.of("WWW-Authenticate", "Bearer realm=\"https://elsewhere.test/token\",service=\"x\""));
        when(foreign.uri()).thenReturn(URI.create("https://elsewhere.test/v2/"));
        stubProbe(URI.create(HUB + "/v2/"), foreign);
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());

        upstream.fetch(hub, "p", "acme/app/manifests/1", "acme/app", FetchOptions.index(), null);

        assertThat(upstream.challenge(hub)).isEqualTo(DockerUpstream.Challenge.NONE);
        verify(client, never()).getWithCredentials(any(), any(), anyMap(), anyMap());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(proxy).fetch(eq(hub), eq("p"), any(), any(), headers.capture());
        assertThat(headers.getValue()).doesNotContainKey("Authorization");

        // the registry's own origin, a host the administrator listed, and any host for a proxy without credentials
        URI registry = URI.create(HUB + "/v2/");
        URI listed = URI.create("https://elsewhere.test/v2/");
        assertThat(DockerUpstream.challengeTrusted(hub, registry)).isTrue();
        assertThat(DockerUpstream.challengeTrusted(hub, listed)).isFalse();
        hub.setFormatSettings(Map.of("upstream.credentialHosts", "elsewhere.test"));
        assertThat(DockerUpstream.challengeTrusted(hub, listed)).isTrue();
        Repository anonymous = proxy("anonymous", HUB);
        assertThat(DockerUpstream.challengeTrusted(anonymous, URI.create("https://other.test/v2/"))).isTrue();
    }

    @Test
    void aRedirectedChallengeProbedWithoutCredentialsIsNotUsedOnceCredentialsAreAdded() throws Exception {
        UpstreamClient.Response foreign = response(401, "",
                Map.of("WWW-Authenticate", "Bearer realm=\"https://elsewhere.test/token\",service=\"x\""));
        when(foreign.uri()).thenReturn(URI.create("https://elsewhere.test/v2/"));
        stubProbe(URI.create(HUB + "/v2/"), foreign);
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());

        // without credentials an anonymous token from wherever the registry sent the probe is harmless
        assertThat(upstream.challenge(hub).scheme()).isEqualTo(DockerUpstream.Scheme.BEARER);

        // the cached challenge must not send credentials added afterwards to that realm
        hub.setRemoteUsername("user");
        hub.setRemotePassword("secret");
        upstream.fetch(hub, "p", "acme/app/manifests/1", "acme/app", FetchOptions.index(), null);
        assertThat(upstream.challenge(hub)).isEqualTo(DockerUpstream.Challenge.NONE);
        verify(client, times(1)).getAnonymous(any(), any(), anyMap(), anyMap());
        verify(client, never()).getWithCredentials(any(), any(), anyMap(), anyMap());
    }

    @Test
    void offlineRepositoriesAreNeverProbed() throws Exception {
        when(proxy.isOffline(hub)).thenReturn(true);
        when(proxy.fetch(any(), any(), any(), any(), anyMap())).thenReturn(Optional.empty());
        upstream.fetch(hub, "p", "x/manifests/1", "x", FetchOptions.index(), null);
        verifyNoInteractions(client);
        verify(proxy).fetch(eq(hub), eq("p"), any(), any(), anyMap());
    }

    @Test
    void shortLivedTokensAreKeptForAMinimum() throws Exception {
        stubProbe(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        stubToken(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Ax%3Apull"), response(200, "{\"token\":\"t\",\"expires_in\":5}", Map.of()));
        DockerUpstream.Challenge challenge = upstream.challenge(hub);
        assertThat(upstream.token(hub, challenge, "x")).contains("t");
        now.set(now.get().plus(Duration.ofSeconds(9)));
        assertThat(upstream.token(hub, challenge, "x")).contains("t");
        verify(client, times(1)).getWithCredentials(any(), eq(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Ax%3Apull")), anyMap(), anyMap());
        now.set(now.get().plus(Duration.ofSeconds(2)));
        assertThat(upstream.token(hub, challenge, "x")).contains("t");
        verify(client, times(2)).getWithCredentials(any(), eq(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Ax%3Apull")), anyMap(), anyMap());
    }

    @Test
    void deniedImagesAreMissesNotOutages() throws Exception {
        stubProbe(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        stubToken(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Fghost%3Apull"),
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
        verify(client, times(2)).getAnonymous(any(), eq(URI.create(HUB + "/v2/")), anyMap(), anyMap());
    }

    @Test
    void realOutagesStayOutages() throws Exception {
        stubProbe(URI.create(HUB + "/v2/"), response(200, "{}", Map.of()));
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
        when(client.getAnonymous(any(), any(), anyMap(), anyMap())).thenThrow(new IOException("connect timed out"));
        when(client.getWithCredentials(any(), any(), anyMap(), anyMap())).thenThrow(new IOException("connect timed out"));
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
        verifyNoInteractions(client);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(proxy, times(3)).fetch(eq(hub), anyString(), any(), any(), headers.capture());
        assertThat(headers.getAllValues()).allSatisfy(h -> assertThat(h).doesNotContainKey("Authorization"));
    }

    @Test
    void staleMetadataIsRevalidatedWithAToken() throws Exception {
        stubProbe(URI.create(HUB + "/v2/"), response(401, "", Map.of("WWW-Authenticate", BEARER)));
        stubToken(URI.create("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Falpine%3Apull"),
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
        verifyNoInteractions(client);
        verify(health, never()).recordSuccess(any());
        verify(proxy, times(2)).fetch(eq(hub), anyString(), any(), any(), anyMap());
    }

    @Test
    void aConcurrentlyCachedPathIsReadBackInsteadOfFailing() throws Exception {
        stubProbe(URI.create(HUB + "/v2/"), response(200, "{}", Map.of()));
        StoredContent cached = mock(StoredContent.class);
        when(proxy.fetch(any(), any(), any(), any(), anyMap()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_asset_path"))
                .thenReturn(Optional.of(cached));
        assertThat(upstream.fetch(hub, "blobs/sha256/abc", "library/alpine/blobs/sha256:abc", "library/alpine",
                FetchOptions.artifact(), "*/*")).contains(cached);
        verify(proxy, times(2)).fetch(eq(hub), eq("blobs/sha256/abc"), any(), any(), anyMap());
    }
}
