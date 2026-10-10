package dev.granary.format.composer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** URL handling around the upstream: Composer's URL canonicalisation, credential scoping and conditional requests. */
class ComposerUpstreamTest {

    private static final URI ROOT = URI.create("https://repo.packagist.org/packages.json");

    @Test
    void canonicalizesRootUrlsLikeComposer() {
        assertThat(ComposerProxy.canonicalize(ROOT, "/p2/%package%.json")).contains("https://repo.packagist.org/p2/%package%.json");
        assertThat(ComposerProxy.canonicalize(ROOT, "https://packagist.org/search.json?q=%query%"))
                .contains("https://packagist.org/search.json?q=%query%");
        assertThat(ComposerProxy.canonicalize(ROOT, "//cdn.example/p2/%package%.json")).contains("https://cdn.example/p2/%package%.json");
        assertThat(ComposerProxy.canonicalize(URI.create("https://satis.example/php/packages.json"), "p2/%package%.json"))
                .contains("https://satis.example/php/p2/%package%.json");
        assertThat(ComposerProxy.canonicalize(URI.create("http://localhost:8081/repository/php/packages.json"), "/x"))
                .contains("http://localhost:8081/x");
        assertThat(ComposerProxy.canonicalize(ROOT, null)).isEmpty();
        assertThat(ComposerProxy.canonicalize(ROOT, " ")).isEmpty();
    }

    @Test
    void findsTheUpstreamRootDocument() {
        assertThat(ComposerProxy.rootUri(remote("https://repo.packagist.org/"))).isEqualTo(URI.create("https://repo.packagist.org/packages.json"));
        assertThat(ComposerProxy.rootUri(remote("https://satis.example/php"))).isEqualTo(URI.create("https://satis.example/php/packages.json"));
        assertThat(ComposerProxy.rootUri(remote("https://satis.example/php/custom.json"))).isEqualTo(URI.create("https://satis.example/php/custom.json"));
    }

    @Test
    void credentialsStayWithTheUpstreamOrigin() {
        Repository repo = remote("https://repo.example.com/php/");
        repo.setRemoteUsername("user");
        repo.setRemotePassword("secret");
        repo.setRemoteToken("token");
        repo.setFormatSettings(Map.of("composer.distRewrite", "true"));

        assertThat(UpstreamScope.credentialsFor(repo, URI.create("https://repo.example.com/p2/a/b.json"))).isSameAs(repo);
        assertThat(UpstreamScope.credentialsFor(repo, URI.create("https://REPO.example.com:443/x"))).isSameAs(repo);

        Repository foreign = UpstreamScope.credentialsFor(repo, URI.create("https://api.github.com/repos/a/b/zipball/c"));
        assertThat(foreign.getId()).isEqualTo(repo.getId());
        assertThat(foreign.getRemoteUsername()).isNull();
        assertThat(foreign.getRemotePassword()).isNull();
        assertThat(foreign.getRemoteToken()).isNull();
        assertThat(foreign.getFormatSettings()).containsEntry("composer.distRewrite", "true");

        Repository scoped = UpstreamScope.hostScope(repo, URI.create("https://api.github.com/repos/a/b/zipball/c"));
        assertThat(scoped.getId()).isNotEqualTo(repo.getId());
        assertThat(scoped.getId()).isEqualTo(UpstreamScope.hostScope(repo, URI.create("https://api.github.com/other")).getId());
        assertThat(scoped.getId()).isNotEqualTo(UpstreamScope.hostScope(repo, URI.create("https://gitlab.com/x")).getId());
        assertThat(scoped.getRemotePassword()).isNull();
        assertThat(scoped.getName()).contains("https://api.github.com");
        assertThat(UpstreamScope.hostScope(repo, URI.create("https://repo.example.com/dist/x.zip"))).isSameAs(repo);
    }

    @Test
    void originsIgnoreDefaultPortsAndCase() {
        assertThat(UpstreamScope.origin("HTTPS://Example.COM:443/a")).isEqualTo("https://example.com");
        assertThat(UpstreamScope.origin("http://example.com:8080/a")).isEqualTo("http://example.com:8080");
        assertThat(UpstreamScope.origin("not a url")).isNull();
        assertThat(UpstreamScope.origin("/relative")).isNull();
    }

    @Test
    void ifModifiedSinceMatchesOnlyTheExactSecond() {
        Instant modified = Instant.parse("2026-05-06T07:08:09.500Z");
        assertThat(ComposerResponses.sameSecond("Wed, 06 May 2026 07:08:09 GMT", modified)).isTrue();
        assertThat(ComposerResponses.sameSecond("Wed, 06 May 2026 07:08:10 GMT", modified)).isFalse();
        assertThat(ComposerResponses.sameSecond("Wed, 06 May 2026 07:08:08 GMT", modified)).isFalse();
        assertThat(ComposerResponses.sameSecond("yesterday", modified)).isFalse();
        assertThat(ComposerResponses.sameSecond(null, modified)).isFalse();
    }

    @Test
    void aLastModifiedInTheCurrentSecondIsNeitherSentNorMatched() {
        Repository repo = ComposerTestSupport.repository("composer-hosted", RepositoryType.HOSTED);
        Instant changed = Instant.parse("2026-05-06T07:08:09.100Z");
        Instant sameSecond = Instant.parse("2026-05-06T07:08:09.900Z");
        Instant nextSecond = Instant.parse("2026-05-06T07:08:10.000Z");
        byte[] body = "{\"packages\":{}}".getBytes(StandardCharsets.UTF_8);
        String header = "Wed, 06 May 2026 07:08:09 GMT";

        // the document may still change within this second, and that change would carry the same Last-Modified
        ResponseEntity<?> fresh = ComposerResponses.json(get(repo, null), body, changed, sameSecond);
        assertThat(fresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fresh.getHeaders().containsHeader(HttpHeaders.LAST_MODIFIED)).isFalse();
        assertThat(fresh.getHeaders().getETag()).isNotNull();
        assertThat(ComposerResponses.json(get(repo, header), body, changed, sameSecond).getStatusCode()).isEqualTo(HttpStatus.OK);

        // once the second is over, the time is a strong validator
        ResponseEntity<?> later = ComposerResponses.json(get(repo, null), body, changed, nextSecond);
        assertThat(later.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED)).isEqualTo(header);
        ResponseEntity<?> notModified = ComposerResponses.json(get(repo, header), body, changed, nextSecond);
        assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModified.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED)).isEqualTo(header);
        assertThat(ComposerResponses.json(get(repo, "Wed, 06 May 2026 07:08:08 GMT"), body, changed, nextSecond).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void globsMatchLikePackagistListFilters() {
        assertThat(ComposerMetadata.glob("acme/*").matcher("acme/lib").matches()).isTrue();
        assertThat(ComposerMetadata.glob("acme/*").matcher("other/acme").matches()).isFalse();
        assertThat(ComposerMetadata.glob("*log*").matcher("psr/log").matches()).isTrue();
        assertThat(ComposerMetadata.glob("Psr/Log").matcher("psr/log").matches()).isTrue();
        assertThat(ComposerMetadata.glob("a.b").matcher("axb").matches()).isFalse();
    }

    private static RepositoryRequest get(Repository repo, String ifModifiedSince) {
        MockHttpServletRequest http = new MockHttpServletRequest("GET", "/repository/" + repo.getName() + "/p2/acme/lib.json");
        if (ifModifiedSince != null) {
            http.addHeader(HttpHeaders.IF_MODIFIED_SINCE, ifModifiedSince);
        }
        return new RepositoryRequest(repo, "p2/acme/lib.json", http, GranaryPrincipal.anonymous(Set.of()),
                "http://localhost/repository/" + repo.getName());
    }

    private static Repository remote(String url) {
        Repository repo = ComposerTestSupport.repository("proxy", RepositoryType.PROXY);
        repo.setRemoteUrl(url);
        return repo;
    }
}
