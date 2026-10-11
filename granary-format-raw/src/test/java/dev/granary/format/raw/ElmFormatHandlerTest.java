package dev.granary.format.raw;

import static dev.granary.format.raw.RawTestSupport.baseUrl;
import static dev.granary.format.raw.RawTestSupport.body;
import static dev.granary.format.raw.RawTestSupport.context;
import static dev.granary.format.raw.RawTestSupport.header;
import static dev.granary.format.raw.RawTestSupport.hosted;
import static dev.granary.format.raw.RawTestSupport.json;
import static dev.granary.format.raw.RawTestSupport.properties;
import static dev.granary.format.raw.RawTestSupport.proxy;
import static dev.granary.format.raw.RawTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.storage.StorageService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class ElmFormatHandlerTest {

    @TempDir
    Path dir;

    final FakeStorage storage = new FakeStorage();
    final ProxyService proxy = mock(ProxyService.class);
    final GroupResolver groups = mock(GroupResolver.class);
    final Repository hosted = hosted("elm-hosted", "elm");
    final Repository upstream = proxy("elm-proxy", "elm", "https://package.elm-lang.org/");
    ElmFormatHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ElmFormatHandler(storage.mock, proxy, groups, properties(dir));
    }

    @Test
    void registryPostsAreReads() {
        assertThat(handler.requiredAccess(request(hosted, "POST", "all-packages"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "POST", "all-packages/since/12"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "POST", "search.json"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "GET", "packages/elm/core/1.0.5/elm.json"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "POST", "packages/elm/core/1.0.5/elm.json"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hosted, "PUT", "all-packages"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hosted, "DELETE", "packages/elm/core/1.0.5/elm.json"))).isEqualTo(AccessLevel.WRITE);
    }

    @Test
    void hostedReleasesBuildTheRegistryDocumentsAndEndpoint() throws Exception {
        byte[] zip = "zip-bytes".getBytes(StandardCharsets.UTF_8);
        assertThat(handler.handle(request(hosted, "PUT", "packages/elm/core/1.0.5/elm.json", "{}")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(handler.handle(request(hosted, "PUT", "packages/elm/core/1.0.5/core.zip", zip, "application/zip")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.coordinates(hosted, "packages/elm/core/1.0.5/elm.json")).isEqualTo(new StorageService.ComponentCoordinates("elm", "core", "1.0.5"));
        assertThat(storage.asset(hosted, "packages/elm/core/1.0.5/elm.json").isMetadata()).isFalse();

        ResponseEntity<?> endpoint = handler.handle(request(hosted, "GET", "packages/elm/core/1.0.5/endpoint.json"));
        assertThat(endpoint.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = json(endpoint);
        assertThat(json.path("url").asString()).isEqualTo(baseUrl(hosted) + "/packages/elm/core/1.0.5/core.zip");
        assertThat(json.path("hash").asString()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip)));
        assertThat(storage.asset(hosted, "packages/elm/core/1.0.5/endpoint.json").getOrigin()).isEqualTo(Asset.Origin.GENERATED);
        assertThat(header(endpoint, "Cache-Control")).contains("no-cache");

        assertThat(body(handler.handle(request(hosted, "GET", "all-packages")))).isEqualTo("{\"elm/core\":[\"1.0.5\"]}");
        assertThat(body(handler.handle(request(hosted, "POST", "all-packages")))).isEqualTo("{\"elm/core\":[\"1.0.5\"]}");
        assertThat(body(handler.handle(request(hosted, "POST", "all-packages/since/0")))).isEqualTo("[\"elm/core@1.0.5\"]");

        handler.handle(request(hosted, "PUT", "packages/elm/core/1.0.6/elm.json", "{}"));
        handler.handle(request(hosted, "PUT", "packages/elm/html/1.0.0/elm.json", "{}"));
        assertThat(body(handler.handle(request(hosted, "GET", "all-packages")))).isEqualTo("{\"elm/core\":[\"1.0.5\",\"1.0.6\"],\"elm/html\":[\"1.0.0\"]}");
        assertThat(body(handler.handle(request(hosted, "GET", "all-packages/since/0")))).isEqualTo("[\"elm/html@1.0.0\",\"elm/core@1.0.6\",\"elm/core@1.0.5\"]");
        assertThat(body(handler.handle(request(hosted, "GET", "all-packages/since/2")))).isEqualTo("[\"elm/html@1.0.0\"]");
        assertThat(body(handler.handle(request(hosted, "GET", "all-packages/since/9")))).isEqualTo("[]");
        assertThat(handler.handle(request(hosted, "PUT", "all-packages", "{}")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "GET", "packages/elm/html/1.0.0/endpoint.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void proxyRewritesEndpointsAndRoutesZipsToGithub() throws Exception {
        when(proxy.fetch(eq(upstream), eq("packages/elm/core/1.0.5/endpoint.json"), any(FetchOptions.class)))
                .thenReturn(Optional.of(storage.put(upstream, "packages/elm/core/1.0.5/endpoint.json",
                        "{\"url\":\"https://github.com/elm/core/zipball/1.0.5/\",\"hash\":\"abc123\"}".getBytes(StandardCharsets.UTF_8), "application/json", true)));
        ResponseEntity<?> endpoint = handler.handle(request(upstream, "GET", "packages/elm/core/1.0.5/endpoint.json"));
        assertThat(endpoint.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(endpoint).path("url").asString()).isEqualTo(baseUrl(upstream) + "/github/elm/core/zip/1.0.5");
        assertThat(json(endpoint).path("hash").asString()).isEqualTo("abc123");
        assertThat(header(endpoint, "Cache-Control")).contains("no-cache");

        ResponseEntity<?> zip = handler.handle(request(upstream, "GET", "github/elm/core/zip/1.0.5"));
        assertThat(zip.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ArgumentCaptor<FetchOptions> options = ArgumentCaptor.forClass(FetchOptions.class);
        verify(proxy).fetch(eq(upstream), eq("github/elm/core/zip/1.0.5"), eq(URI.create("https://github.com/elm/core/zipball/1.0.5")),
                options.capture(), anyMap());
        assertThat(options.getValue().metadata()).isFalse();
        // the cached zipball belongs to the package release, like an upload of it
        assertThat(options.getValue().component()).isEqualTo(new StorageService.ComponentCoordinates("elm", "core", "1.0.5"));
        assertThat(handler.isMetadataPath("github/elm/core/zip/1.0.5")).isFalse();
        assertThat(handler.isMetadataPath("packages/elm/core/1.0.5/endpoint.json")).isTrue();
        assertThat(handler.isMetadataPath("packages/elm/core/releases.json")).isTrue();
        assertThat(handler.isMetadataPath("packages/elm/core/1.0.5/docs.json")).isFalse();

        when(proxy.fetch(eq(upstream), eq("all-packages/since/3"), any(FetchOptions.class)))
                .thenReturn(Optional.of(storage.put(upstream, "all-packages/since/3", "[\"elm/core@1.0.5\"]".getBytes(StandardCharsets.UTF_8), "application/json", true)));
        assertThat(body(handler.handle(request(upstream, "POST", "all-packages/since/3")))).isEqualTo("[\"elm/core@1.0.5\"]");
    }

    @Test
    void groupsMergeTheRegistryOfHostedAndProxyMembers() throws Exception {
        handler.handle(request(hosted, "PUT", "packages/acme/widget/2.0.0/elm.json", "{}"));
        when(proxy.fetch(eq(upstream), eq("all-packages"), any(FetchOptions.class)))
                .thenReturn(Optional.of(storage.put(upstream, "all-packages", "{\"elm/core\":[\"1.0.5\"],\"acme/widget\":[\"1.0.0\"]}".getBytes(StandardCharsets.UTF_8), "application/json", true)));
        when(proxy.fetch(eq(upstream), eq("all-packages/since/0"), any(FetchOptions.class)))
                .thenReturn(Optional.of(storage.put(upstream, "all-packages/since/0", "[\"elm/core@1.0.5\",\"acme/widget@1.0.0\"]".getBytes(StandardCharsets.UTF_8), "application/json", true)));
        Repository group = RawTestSupport.group("elm-all", "elm", "elm-hosted", "elm-proxy");
        when(groups.leafMembers(group)).thenReturn(List.of(hosted, upstream));
        assertThat(body(handler.handle(request(group, "GET", "all-packages")))).isEqualTo("{\"acme/widget\":[\"1.0.0\",\"2.0.0\"],\"elm/core\":[\"1.0.5\"]}");
        assertThat(body(handler.handle(request(group, "GET", "all-packages/since/0"))))
                .isEqualTo("[\"elm/core@1.0.5\",\"acme/widget@1.0.0\",\"acme/widget@2.0.0\"]");
        assertThat(handler.handle(request(group, "PUT", "packages/acme/widget/3.0.0/elm.json", "{}")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void aGroupHandsOutEveryNewReleaseOnceWhicheverMemberGainsIt() throws Exception {
        // the compiler counts the releases it knows (3 after all-packages) and asks for since/<count>
        handler.handle(request(hosted, "PUT", "packages/acme/widget/2.0.0/elm.json", "{}"));
        stubUpstreamSince("[\"elm/core@1.0.5\",\"acme/widget@1.0.0\"]");
        Repository group = RawTestSupport.group("elm-all", "elm", "elm-hosted", "elm-proxy");
        when(groups.leafMembers(group)).thenReturn(List.of(hosted, upstream));
        JsonNode all = json(handler.handle(request(group, "POST", "all-packages")));
        int known = 0;
        for (JsonNode versions : all) {
            known += versions.size();
        }
        assertThat(known).isEqualTo(3);
        assertThat(body(handler.handle(request(group, "POST", "all-packages/since/" + known)))).isEqualTo("[]");

        // a new upstream release: it is the one newer release, although the hosted member comes first
        stubUpstreamSince("[\"elm/core@1.0.6\",\"elm/core@1.0.5\",\"acme/widget@1.0.0\"]");
        assertThat(body(handler.handle(request(group, "POST", "all-packages/since/3")))).isEqualTo("[\"elm/core@1.0.6\"]");
        // then a new hosted release
        handler.handle(request(hosted, "PUT", "packages/acme/widget/3.0.0/elm.json", "{}"));
        assertThat(body(handler.handle(request(group, "POST", "all-packages/since/4")))).isEqualTo("[\"acme/widget@3.0.0\"]");
        assertThat(body(handler.handle(request(group, "POST", "all-packages/since/3"))))
                .isEqualTo("[\"acme/widget@3.0.0\",\"elm/core@1.0.6\"]");
        assertThat(body(handler.handle(request(group, "POST", "all-packages/since/5")))).isEqualTo("[]");
        assertThat(json(handler.handle(request(group, "POST", "all-packages"))).path("acme/widget").size()).isEqualTo(3);
    }

    private void stubUpstreamSince(String newestFirst) {
        when(proxy.fetch(eq(upstream), eq("all-packages/since/0"), any(FetchOptions.class)))
                .thenReturn(Optional.of(storage.put(upstream, "all-packages/since/0", newestFirst.getBytes(StandardCharsets.UTF_8),
                        "application/json", true)));
    }

    @Test
    void templatesAndSnippets() {
        assertThat(handler.templates()).extracting(RepositoryTemplate::name).containsExactly("elm-proxy");
        assertThat(handler.templates().get(0).remoteUrl()).isEqualTo("https://package.elm-lang.org/");
        var snippets = handler.connectSnippets(context(upstream, true));
        assertThat(snippets).extracting(ConnectSnippet::id).containsExactly("note", "nginx", "hosts", "curl", "publish");
        assertThat(snippets.get(0).code()).contains("package.elm-lang.org");
        assertThat(snippets.get(1).code()).contains("server_name package.elm-lang.org").contains("proxy_pass http://granary.test/repository/elm-proxy/")
                // Host must stay Granary's, or endpoint.json would send the zip downloads to package.elm-lang.org over http
                .doesNotContain("proxy_set_header Host");
        assertThat(snippets.get(3).code()).contains("http://granary.test/repository/elm-proxy/all-packages");
    }
}
