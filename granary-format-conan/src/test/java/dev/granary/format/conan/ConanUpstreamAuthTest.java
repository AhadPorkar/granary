package dev.granary.format.conan;

import static dev.granary.format.conan.ConanTestSupport.hosted;
import static dev.granary.format.conan.ConanTestSupport.proxy;
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

import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UrlPaths;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConanUpstreamAuthTest {

    private static final String REMOTE = "https://artifactory.example.com/artifactory/api/conan/conan-local";
    private static final URI AUTH_URI = URI.create(REMOTE + "/v2/users/authenticate");

    private final UpstreamClient client = mock(UpstreamClient.class);
    private final MutableClock clock = new MutableClock();
    private final ConanUpstreamAuth auth = new ConanUpstreamAuth(client, clock);
    private Repository repo;

    @BeforeEach
    void setUp() {
        when(client.upstreamUri(any(), anyString())).thenAnswer(inv ->
                URI.create(UrlPaths.join(((Repository) inv.getArgument(0)).getRemoteUrl(), inv.getArgument(1))));
        repo = proxy("conan-private", REMOTE);
        repo.setRemoteUsername("ci");
        repo.setRemotePassword("s3cret");
        repo.setDescription("private upstream");
        repo.setAllowRedeploy(true);
        repo.setMetadataMaxAgeMinutes(7);
        repo.setNegativeCacheMinutes(3);
        repo.setConnectTimeoutSeconds(4);
        repo.setReadTimeoutSeconds(9);
        repo.setFormatSettings(Map.of(ConanUpstreamAuth.SETTING, "token"));
    }

    @Test
    void onlyProxiesWithUsernameAndPasswordUseTheTokenFlow() throws Exception {
        assertThat(ConanUpstreamAuth.needsToken(repo)).isTrue();
        assertThat(ConanUpstreamAuth.needsToken(hosted("h"))).isFalse();
        assertThat(ConanUpstreamAuth.needsToken(proxy("anonymous", ConanFormatHandler.CONANCENTER))).isFalse();

        Repository noId = proxy("x", REMOTE);
        noId.setId(null);
        noId.setRemoteUsername("ci");
        noId.setRemotePassword("pw");
        assertThat(ConanUpstreamAuth.needsToken(noId)).isFalse();

        Repository withToken = proxy("t", REMOTE);
        withToken.setRemoteUsername("ci");
        withToken.setRemotePassword("pw");
        withToken.setRemoteToken("static-token");
        assertThat(ConanUpstreamAuth.needsToken(withToken)).isFalse();

        Repository noPassword = proxy("p", REMOTE);
        noPassword.setRemoteUsername("ci");
        assertThat(ConanUpstreamAuth.needsToken(noPassword)).isFalse();

        Repository blankUser = proxy("b", REMOTE);
        blankUser.setRemoteUsername(" ");
        blankUser.setRemotePassword("pw");
        assertThat(ConanUpstreamAuth.needsToken(blankUser)).isFalse();

        Repository basic = proxy("basic", REMOTE);
        basic.setRemoteUsername("ci");
        basic.setRemotePassword("pw");
        basic.setFormatSettings(Map.of(ConanUpstreamAuth.SETTING, "BASIC"));
        assertThat(ConanUpstreamAuth.needsToken(basic)).isFalse();
        assertThat(auth.authorised(basic)).isSameAs(basic);
        verify(client, never()).getAbsolute(any(), any(), anyMap(), anyMap());
    }

    @Test
    void exchangesOnceAndHandsOutACopyCarryingTheToken() throws Exception {
        answer(200, "eyJhbGciOi.token.sig\n");

        Repository view = auth.authorised(repo);
        assertThat(view).isNotSameAs(repo);
        assertThat(view.getRemoteToken()).isEqualTo("eyJhbGciOi.token.sig");
        assertThat(view.getRemoteUsername()).isNull();
        assertThat(view.getRemotePassword()).isNull();
        // everything else the proxy service reads is carried over
        assertThat(view.getId()).isEqualTo(repo.getId());
        assertThat(view.getName()).isEqualTo(repo.getName());
        assertThat(view.getFormat()).isEqualTo(repo.getFormat());
        assertThat(view.getType()).isEqualTo(RepositoryType.PROXY);
        assertThat(view.getRemoteUrl()).isEqualTo(REMOTE);
        assertThat(view.getDescription()).isEqualTo("private upstream");
        assertThat(view.isAllowRedeploy()).isTrue();
        assertThat(view.getMetadataMaxAgeMinutes()).isEqualTo(7);
        assertThat(view.getNegativeCacheMinutes()).isEqualTo(3);
        assertThat(view.getConnectTimeoutSeconds()).isEqualTo(4);
        assertThat(view.getReadTimeoutSeconds()).isEqualTo(9);
        assertThat(view.getFormatSettings()).containsEntry(ConanUpstreamAuth.SETTING, "token");
        assertThat(view.isOnline()).isEqualTo(repo.isOnline());
        assertThat(view.getBlobStore()).isEqualTo(repo.getBlobStore());

        // the second request reuses the cached token
        assertThat(auth.authorised(repo).getRemoteToken()).isEqualTo("eyJhbGciOi.token.sig");
        verify(client, times(1)).getAbsolute(eq(repo), eq(AUTH_URI), eq(Map.of()), eq(Map.of("Accept", "text/plain")));
    }

    @Test
    void tokenIsRenewedAfterItsLifetime() throws Exception {
        answer(200, "first");
        assertThat(auth.authorised(repo).getRemoteToken()).isEqualTo("first");
        answer(200, "second");
        clock.advance(ConanUpstreamAuth.TOKEN_TTL.minusMinutes(1));
        assertThat(auth.authorised(repo).getRemoteToken()).isEqualTo("first");
        clock.advance(Duration.ofMinutes(2));
        assertThat(auth.authorised(repo).getRemoteToken()).isEqualTo("second");
    }

    @Test
    void changedCredentialsAreExchangedAgain() throws Exception {
        answer(200, "first");
        auth.authorised(repo);
        repo.setRemotePassword("rotated");
        answer(200, "second");
        assertThat(auth.authorised(repo).getRemoteToken()).isEqualTo("second");
    }

    @Test
    void forgetDropsTheToken() throws Exception {
        answer(200, "first");
        auth.authorised(repo);
        auth.forget(repo);
        answer(200, "second");
        assertThat(auth.authorised(repo).getRemoteToken()).isEqualTo("second");
        Repository noId = proxy("x", REMOTE);
        noId.setId(null);
        auth.forget(noId); // nothing to forget, nothing thrown
    }

    @Test
    void aRejectedTokenIsExchangedAgainButANewerOneIsKept() throws Exception {
        answer(200, "first");
        assertThat(auth.authorised(repo).getRemoteToken()).isEqualTo("first");
        clock.advance(Duration.ofMinutes(1));
        Instant rejectedAt = clock.instant();
        clock.advance(Duration.ofSeconds(1));
        answer(200, "second");
        auth.rejected(repo, rejectedAt);
        assertThat(auth.authorised(repo).getRemoteToken()).isEqualTo("second");
        // the same 401 reported again must not throw away the token obtained after it
        answer(200, "third");
        clock.advance(Duration.ofMinutes(1));
        auth.rejected(repo, rejectedAt);
        assertThat(auth.authorised(repo).getRemoteToken()).isEqualTo("second");
        auth.rejected(repo, null);
        Repository noId = proxy("x", REMOTE);
        noId.setId(null);
        auth.rejected(noId, rejectedAt);
        assertThat(auth.authorised(repo).getRemoteToken()).isEqualTo("second");
    }

    @Test
    void failedExchangeFallsBackToBasicAndIsRetriedLater() throws Exception {
        answer(401, "nope");
        assertThat(auth.authorised(repo)).isSameAs(repo);
        assertThat(auth.authorised(repo)).isSameAs(repo);
        verify(client, times(1)).getAbsolute(any(), any(), anyMap(), anyMap());

        answer(200, "late-token");
        clock.advance(ConanUpstreamAuth.FAILURE_TTL.plusSeconds(1));
        assertThat(auth.authorised(repo).getRemoteToken()).isEqualTo("late-token");
    }

    @Test
    void unusableTokensAreRefused() throws Exception {
        answer(200, "   ");
        assertThat(auth.authorised(repo)).isSameAs(repo);
        clock.advance(ConanUpstreamAuth.FAILURE_TTL.plusSeconds(1));
        answer(200, "<html> login page </html>");
        assertThat(auth.authorised(repo)).isSameAs(repo);
        clock.advance(ConanUpstreamAuth.FAILURE_TTL.plusSeconds(1));
        answer(200, "x".repeat(5000));
        assertThat(auth.authorised(repo)).isSameAs(repo);
    }

    @Test
    void networkFailuresFallBackToBasic() throws Exception {
        when(client.getAbsolute(any(), any(), anyMap(), anyMap())).thenThrow(new IOException("connection refused"));
        assertThat(auth.authorised(repo)).isSameAs(repo);
    }

    @Test
    void interruptionFallsBackToBasicAndKeepsTheFlag() throws Exception {
        when(client.getAbsolute(any(), any(), anyMap(), anyMap())).thenThrow(new InterruptedException());
        try {
            assertThat(auth.authorised(repo)).isSameAs(repo);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void publicConstructorUsesTheSystemClock() {
        assertThat(new ConanUpstreamAuth(client).authorised(hosted("h")).getName()).isEqualTo("h");
    }

    private void answer(int status, String body) throws Exception {
        UpstreamClient.Response response = mock(UpstreamClient.Response.class);
        when(response.status()).thenReturn(status);
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(client.getAbsolute(any(), any(), anyMap(), anyMap())).thenReturn(response);
    }

    /** A clock the test moves forward by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-03-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

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
            return now;
        }
    }
}
