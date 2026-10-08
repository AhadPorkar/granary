package dev.granary.format.npm;

import static dev.granary.format.npm.NpmTestSupport.MAPPER;
import static dev.granary.format.npm.NpmTestSupport.baseUrl;
import static dev.granary.format.npm.NpmTestSupport.body;
import static dev.granary.format.npm.NpmTestSupport.bodyBytes;
import static dev.granary.format.npm.NpmTestSupport.bytes;
import static dev.granary.format.npm.NpmTestSupport.hosted;
import static dev.granary.format.npm.NpmTestSupport.http;
import static dev.granary.format.npm.NpmTestSupport.jsonBody;
import static dev.granary.format.npm.NpmTestSupport.publishDocument;
import static dev.granary.format.npm.NpmTestSupport.request;
import static dev.granary.format.npm.NpmTestSupport.str;
import static dev.granary.format.npm.NpmTestSupport.tarball;
import static dev.granary.format.npm.NpmTestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.dao.ComponentDao;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.ApiToken;
import dev.granary.core.model.Repository;
import dev.granary.core.model.User;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.ApiTokenService;
import dev.granary.core.security.AuthenticationService;
import dev.granary.core.security.UserService;
import dev.granary.core.storage.StoredContent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class NpmFormatHandlerTest {

    private static final String NPMJS = "https://registry.npmjs.org/";

    private final FakeStorage storage = new FakeStorage();
    private final ProxyService proxy = mock(ProxyService.class);
    private final UpstreamClient upstream = mock(UpstreamClient.class);
    private final GroupResolver groups = mock(GroupResolver.class);
    private final AuthenticationService authentication = mock(AuthenticationService.class);
    private final ApiTokenService tokens = mock(ApiTokenService.class);
    private final UserService users = mock(UserService.class);
    private final NpmPackuments packuments = new NpmPackuments(storage.mock, proxy, upstream, groups, MAPPER);
    private final NpmFormatHandler handler = new NpmFormatHandler(storage.mock, packuments,
            new NpmHostedWriter(storage.mock, packuments),
            new NpmSearch(packuments, mock(ComponentDao.class), upstream, proxy, groups),
            authentication, tokens, users);

    private final Repository hostedRepo = hosted("npm-hosted");
    private final Repository proxyRepo = NpmTestSupport.proxy("npm-proxy", NPMJS);
    private final Repository groupRepo = NpmTestSupport.group("npm-all", "npm-hosted", "npm-proxy");

    @Test
    void identity() {
        assertThat(handler.format()).isEqualTo("npm");
        assertThat(handler.displayName()).isEqualTo("npm");
        handler.validate(proxyRepo); // nothing format-specific to refuse
    }

    @Test
    void requiredAccessPerEndpoint() {
        assertThat(handler.requiredAccess(request(hostedRepo, "PUT", "-/user/org.couchdb.user:alice"))).isEqualTo(AccessLevel.NONE);
        assertThat(handler.requiredAccess(request(hostedRepo, "POST", "-/v1/login"))).isEqualTo(AccessLevel.NONE);
        assertThat(handler.requiredAccess(request(hostedRepo, "GET", "-/ping"))).isEqualTo(AccessLevel.NONE);
        assertThat(handler.requiredAccess(request(hostedRepo, "GET", "-/whoami"))).isEqualTo(AccessLevel.NONE);
        assertThat(handler.requiredAccess(request(hostedRepo, "GET", "-/v1/search"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "POST", "-/npm/v1/security/advisories/bulk"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "GET", "lodash"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "HEAD", "lodash/-/lodash-1.0.0.tgz"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "PUT", "lodash"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hostedRepo, "DELETE", "lodash/-rev/1-a"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hostedRepo, "PUT", "-/package/lodash/dist-tags/beta"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hostedRepo, "GET", "-/package/lodash/dist-tags"))).isEqualTo(AccessLevel.READ);
    }

    @Test
    void metadataPaths() {
        assertThat(handler.isMetadataPath("lodash")).isTrue();
        assertThat(handler.isMetadataPath("@babel/core")).isTrue();
        assertThat(handler.isMetadataPath("-/package/lodash/dist-tags")).isTrue();
        assertThat(handler.isMetadataPath("lodash/-/lodash-4.17.21.tgz")).isFalse();
    }

    @Test
    void hostedPackumentIsServedWithTarballsPointingAtTheRepository() throws Exception {
        storage.put(hostedRepo, "demo", packument("demo", "1.0.0", "http://old.example/repository/x/demo/-/demo-1.0.0.tgz"),
                "application/json", true);

        ResponseEntity<?> response = handler.handle(request(hostedRepo, "GET", "demo"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getETag()).isNotNull();
        JsonNode doc = jsonBody(response);
        assertThat(str(doc, "name")).isEqualTo("demo");
        assertThat(str(doc, "versions", "1.0.0", "dist", "tarball")).isEqualTo(baseUrl(hostedRepo) + "/demo/-/demo-1.0.0.tgz");
        assertThat(str(doc, "dist-tags", "latest")).isEqualTo("1.0.0");
    }

    @Test
    void conditionalRequestsGet304() throws Exception {
        storage.put(hostedRepo, "demo", packument("demo", "1.0.0", null), "application/json", true);
        ResponseEntity<?> first = handler.handle(request(hostedRepo, "GET", "demo"));
        RepositoryRequest again = request(hostedRepo, "GET", "demo");
        http(again).addHeader(HttpHeaders.IF_NONE_MATCH, first.getHeaders().getETag());
        ResponseEntity<?> second = handler.handle(again);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(second.getBody()).isNull();

        ResponseEntity<?> head = handler.handle(request(hostedRepo, "HEAD", "demo"));
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getETag()).isEqualTo(first.getHeaders().getETag());
    }

    @Test
    void unknownPackageIsAJsonError() throws Exception {
        ResponseEntity<?> response = handler.handle(request(hostedRepo, "GET", "nope"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(str(jsonBody(response), "error")).isEqualTo("package not found: nope");

        assertThat(handler.handle(request(hostedRepo, "GET", "-/what/is/this")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hostedRepo, "GET", "nope/-/nope-1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void versionEndpointResolvesNumbersAndTags() throws Exception {
        storage.put(hostedRepo, "demo", packument("demo", "1.0.0", null), "application/json", true);

        JsonNode byNumber = jsonBody(handler.handle(request(hostedRepo, "GET", "demo/1.0.0")));
        assertThat(str(byNumber, "version")).isEqualTo("1.0.0");
        assertThat(str(byNumber, "dist", "tarball")).isEqualTo(baseUrl(hostedRepo) + "/demo/-/demo-1.0.0.tgz");

        JsonNode byTag = jsonBody(handler.handle(request(hostedRepo, "GET", "demo/latest")));
        assertThat(str(byTag, "version")).isEqualTo("1.0.0");

        ResponseEntity<?> missing = handler.handle(request(hostedRepo, "GET", "demo/9.9.9"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(str(jsonBody(missing), "error")).isEqualTo("version not found: 9.9.9");
    }

    @Test
    void proxyPackumentIsFetchedAsMutableIndexWithJsonAccept() throws Exception {
        StoredContent cached = storage.put(proxyRepo, "lodash",
                packument("lodash", "4.17.21", NPMJS + "lodash/-/lodash-4.17.21.tgz"), "application/json", true);
        when(proxy.fetch(eq(proxyRepo), eq("lodash"), eq(FetchOptions.index()), eq(Map.of("Accept", "application/json"))))
                .thenReturn(Optional.of(cached));

        JsonNode doc = jsonBody(handler.handle(request(proxyRepo, "GET", "lodash")));
        assertThat(str(doc, "versions", "4.17.21", "dist", "tarball")).isEqualTo(baseUrl(proxyRepo) + "/lodash/-/lodash-4.17.21.tgz");

        when(proxy.fetch(eq(proxyRepo), eq("missing"), eq(FetchOptions.index()), anyMap())).thenReturn(Optional.empty());
        assertThat(handler.handle(request(proxyRepo, "GET", "missing")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void proxyTarballIsFetchedFromTheUrlThePackumentNames() throws Exception {
        storage.put(proxyRepo, "@acme/ui", packument("@acme/ui", "1.0.0", NPMJS + "download/@acme/ui/1.0.0/ui-1.0.0.tgz"),
                "application/json", true);
        StoredContent tgz = storage.put(hosted("elsewhere"), "x", tarball("ui"), "application/octet-stream", false);
        when(upstream.upstreamUri(eq(proxyRepo), any())).thenAnswer(inv -> URI.create(NPMJS + inv.getArgument(1)));
        when(proxy.fetch(eq(proxyRepo), eq("@acme/ui/-/ui-1.0.0.tgz"), eq(URI.create(NPMJS + "download/@acme/ui/1.0.0/ui-1.0.0.tgz")),
                eq(FetchOptions.artifact()), anyMap())).thenReturn(Optional.of(tgz));

        ResponseEntity<?> response = handler.handle(request(proxyRepo, "GET", "@acme/ui/-/ui-1.0.0.tgz"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyBytes(response)).isEqualTo(tarball("ui"));
        verify(storage.mock).recordDownload(tgz.asset());

        // a URL on a foreign host is not followed with our credentials: the conventional path is used instead
        storage.put(proxyRepo, "cdn", packument("cdn", "1.0.0", "https://cdn.example.com/blobs/cdn-1.0.0.tgz"), "application/json", true);
        when(proxy.fetch(eq(proxyRepo), eq("cdn/-/cdn-1.0.0.tgz"), eq(URI.create(NPMJS + "cdn/-/cdn-1.0.0.tgz")),
                eq(FetchOptions.artifact()), anyMap())).thenReturn(Optional.of(tgz));
        assertThat(handler.handle(request(proxyRepo, "GET", "cdn/-/cdn-1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void groupMergesItsMembersAndServesTheirTarballs() throws Exception {
        when(groups.leafMembers(groupRepo)).thenReturn(List.of(hostedRepo, proxyRepo));
        storage.put(hostedRepo, "demo", packument("demo", "1.0.0", baseUrl(hostedRepo) + "/demo/-/demo-1.0.0.tgz"), "application/json", true);
        storage.put(hostedRepo, "demo/-/demo-1.0.0.tgz", tarball("hosted"), "application/octet-stream", false);
        StoredContent cached = storage.put(proxyRepo, "demo", packument("demo", "2.0.0", NPMJS + "demo/-/demo-2.0.0.tgz"), "application/json", true);
        when(proxy.fetch(eq(proxyRepo), eq("demo"), eq(FetchOptions.index()), anyMap())).thenReturn(Optional.of(cached));
        StoredContent upstreamTgz = storage.put(hosted("elsewhere"), "y", tarball("proxied"), "application/octet-stream", false);
        when(upstream.upstreamUri(eq(proxyRepo), any())).thenAnswer(inv -> URI.create(NPMJS + inv.getArgument(1)));
        when(proxy.fetch(eq(proxyRepo), eq("demo/-/demo-2.0.0.tgz"), any(URI.class), eq(FetchOptions.artifact()), anyMap()))
                .thenReturn(Optional.of(upstreamTgz));

        JsonNode doc = jsonBody(handler.handle(request(groupRepo, "GET", "demo")));
        assertThat(NpmPackument.versionNames(doc)).containsExactly("1.0.0", "2.0.0");
        assertThat(str(doc, "dist-tags", "latest")).isEqualTo("2.0.0");
        assertThat(str(doc, "versions", "1.0.0", "dist", "tarball")).isEqualTo(baseUrl(groupRepo) + "/demo/-/demo-1.0.0.tgz");
        assertThat(str(doc, "versions", "2.0.0", "dist", "tarball")).isEqualTo(baseUrl(groupRepo) + "/demo/-/demo-2.0.0.tgz");

        assertThat(bodyBytes(handler.handle(request(groupRepo, "GET", "demo/-/demo-1.0.0.tgz")))).isEqualTo(tarball("hosted"));
        assertThat(bodyBytes(handler.handle(request(groupRepo, "GET", "demo/-/demo-2.0.0.tgz")))).isEqualTo(tarball("proxied"));

        JsonNode tags = jsonBody(handler.handle(request(groupRepo, "GET", "-/package/demo/dist-tags")));
        assertThat(str(tags, "latest")).isEqualTo("2.0.0");

        when(proxy.fetch(eq(proxyRepo), eq("nope"), eq(FetchOptions.index()), anyMap())).thenReturn(Optional.empty());
        assertThat(handler.handle(request(groupRepo, "GET", "nope")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void headOnATarballDoesNotCountAsADownload() throws Exception {
        StoredContent tgz = storage.put(hostedRepo, "demo/-/demo-1.0.0.tgz", tarball("hosted"), "application/octet-stream", false);
        ResponseEntity<?> response = handler.handle(request(hostedRepo, "HEAD", "demo/-/demo-1.0.0.tgz"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNull();
        verify(storage.mock, never()).recordDownload(tgz.asset());
    }

    @Test
    void publishRoundTripThroughTheHandler() throws Exception {
        byte[] tgz = tarball("demo-1.0.0");
        ResponseEntity<?> created = handler.handle(request(hostedRepo, "PUT", "demo",
                bytes(publishDocument("demo", "1.0.0", tgz, "latest")), user("alice")));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode ok = jsonBody(created);
        assertThat(ok.path("ok").asBoolean()).isTrue();
        assertThat(str(ok, "id")).isEqualTo("demo");
        assertThat(str(ok, "rev")).startsWith("1-");

        JsonNode doc = jsonBody(handler.handle(request(hostedRepo, "GET", "demo")));
        assertThat(str(doc, "versions", "1.0.0", "dist", "tarball")).isEqualTo(baseUrl(hostedRepo) + "/demo/-/demo-1.0.0.tgz");
        assertThat(bodyBytes(handler.handle(request(hostedRepo, "GET", "demo/-/demo-1.0.0.tgz")))).isEqualTo(tgz);

        ResponseEntity<?> conflict = handler.handle(request(hostedRepo, "PUT", "demo",
                bytes(publishDocument("demo", "1.0.0", tgz, "latest")), user("alice")));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(str(jsonBody(conflict), "error")).isEqualTo(NpmHostedWriter.VERSION_EXISTS);

        ResponseEntity<?> mismatch = handler.handle(request(hostedRepo, "PUT", "other",
                bytes(publishDocument("demo", "2.0.0", tgz, "latest")), user("alice")));
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(str(jsonBody(mismatch), "error")).contains("does not match the URL");
    }

    @Test
    void unpublishEndpointsThroughTheHandler() throws Exception {
        handler.handle(request(hostedRepo, "PUT", "demo", bytes(publishDocument("demo", "1.0.0", tarball("1"), "latest")), user("a")));
        handler.handle(request(hostedRepo, "PUT", "demo", bytes(publishDocument("demo", "1.1.0", tarball("2"), "latest")), user("a")));

        ObjectNode fetched = (ObjectNode) jsonBody(handler.handle(request(hostedRepo, "GET", "demo")));
        String rev = str(fetched, "_rev");
        ((ObjectNode) fetched.path("versions")).remove("1.1.0");
        ((ObjectNode) fetched.path("dist-tags")).put("latest", "1.0.0");
        ResponseEntity<?> put = handler.handle(request(hostedRepo, "PUT", "demo/-rev/" + rev, bytes(fetched), user("a")));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.paths(hostedRepo)).containsExactly("demo", "demo/-/demo-1.0.0.tgz");

        ResponseEntity<?> tarballDelete = handler.handle(request(hostedRepo, "DELETE", "demo/-/demo-1.1.0.tgz/-rev/" + rev, null, user("a")));
        assertThat(tarballDelete.getStatusCode()).isEqualTo(HttpStatus.OK); // already gone: idempotent

        ResponseEntity<?> all = handler.handle(request(hostedRepo, "DELETE", "demo/-rev/" + rev, null, user("a")));
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storage.paths(hostedRepo)).isEmpty();
        assertThat(handler.handle(request(hostedRepo, "DELETE", "demo/-rev/" + rev, null, user("a"))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void distTagEndpointsThroughTheHandler() throws Exception {
        handler.handle(request(hostedRepo, "PUT", "demo", bytes(publishDocument("demo", "1.0.0", tarball("1"), "latest")), user("a")));

        ResponseEntity<?> set = handler.handle(request(hostedRepo, "PUT", "-/package/demo/dist-tags/beta",
                "\"1.0.0\"".getBytes(StandardCharsets.UTF_8), user("a")));
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(str(jsonBody(handler.handle(request(hostedRepo, "GET", "-/package/demo/dist-tags"))), "beta")).isEqualTo("1.0.0");

        ResponseEntity<?> bare = handler.handle(request(hostedRepo, "PUT", "-/package/demo/dist-tags/next",
                "1.0.0".getBytes(StandardCharsets.UTF_8), user("a")));
        assertThat(bare.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<?> unknown = handler.handle(request(hostedRepo, "PUT", "-/package/demo/dist-tags/x",
                "\"9.9.9\"".getBytes(StandardCharsets.UTF_8), user("a")));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<?> empty = handler.handle(request(hostedRepo, "PUT", "-/package/demo/dist-tags/x",
                "\"\"".getBytes(StandardCharsets.UTF_8), user("a")));
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<?> removed = handler.handle(request(hostedRepo, "DELETE", "-/package/demo/dist-tags/beta", null, user("a")));
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jsonBody(handler.handle(request(hostedRepo, "GET", "-/package/demo/dist-tags"))).has("beta")).isFalse();
    }

    @Test
    void writesToAProxyAre405WithAJsonError() throws Exception {
        ResponseEntity<?> response = handler.handle(request(proxyRepo, "PUT", "demo",
                bytes(publishDocument("demo", "1.0.0", tarball("1"), "latest")), user("a")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(str(jsonBody(response), "error")).contains("proxy repository");
        assertThat(handler.handle(request(groupRepo, "DELETE", "demo/-rev/1-a", null, user("a"))).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hostedRepo, "PUT", "demo/-/demo-1.0.0.tgz", tarball("1"), user("a"))).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hostedRepo, "PATCH", "demo", null, user("a"))).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void pingWhoamiAndRegistryRoot() throws Exception {
        assertThat(body(handler.handle(request(hostedRepo, "GET", "-/ping")))).isEqualTo("{}");

        ResponseEntity<?> anonymous = handler.handle(request(hostedRepo, "GET", "-/whoami"));
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(str(jsonBody(anonymous), "error")).contains("logged in");

        ResponseEntity<?> me = handler.handle(request(hostedRepo, "GET", "-/whoami", null, user("alice")));
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(str(jsonBody(me), "username")).isEqualTo("alice");

        JsonNode root = jsonBody(handler.handle(request(hostedRepo, "GET", "")));
        assertThat(str(root, "db_name")).isEqualTo("npm-hosted");
        assertThat(str(root, "format")).isEqualTo("npm");
    }

    @Test
    void webLoginAndAuditAre404SoNpmFallsBackGracefully() throws Exception {
        ResponseEntity<?> web = handler.handle(request(hostedRepo, "POST", "-/v1/login", "{}".getBytes(StandardCharsets.UTF_8), null));
        assertThat(web.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(str(jsonBody(web), "error")).contains("legacy");
        ResponseEntity<?> audit = handler.handle(request(hostedRepo, "POST", "-/npm/v1/security/advisories/bulk",
                "{}".getBytes(StandardCharsets.UTF_8), null));
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void loginIssuesAnApiToken() throws Exception {
        User alice = new User();
        alice.setUsername("alice");
        when(authentication.authenticate("alice", "s3cret")).thenReturn(Optional.of(user("alice")));
        when(users.find("alice")).thenReturn(Optional.of(alice));
        when(tokens.issue(alice, NpmFormatHandler.TOKEN_NAME, null))
                .thenReturn(new ApiTokenService.IssuedToken(new ApiToken(), "gry_abcdefghijklmnopqrstuvwxyz0123456789"));

        byte[] body = "{\"_id\":\"org.couchdb.user:alice\",\"name\":\"alice\",\"password\":\"s3cret\",\"type\":\"user\",\"roles\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        ResponseEntity<?> response = handler.handle(request(hostedRepo, "PUT", "-/user/org.couchdb.user:alice", body, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = jsonBody(response);
        assertThat(json.path("ok").asBoolean()).isTrue();
        assertThat(str(json, "id")).isEqualTo("org.couchdb.user:alice");
        assertThat(str(json, "token")).isEqualTo("gry_abcdefghijklmnopqrstuvwxyz0123456789");
        assertThat(str(json, "rev")).startsWith("1-");
    }

    @Test
    void loginFailuresAreJson401Or400() throws Exception {
        when(authentication.authenticate("alice", "wrong")).thenReturn(Optional.empty());
        ResponseEntity<?> denied = handler.handle(request(hostedRepo, "PUT", "-/user/org.couchdb.user:alice",
                "{\"name\":\"alice\",\"password\":\"wrong\"}".getBytes(StandardCharsets.UTF_8), null));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(str(jsonBody(denied), "error")).isEqualTo("Invalid username or password");
        verify(tokens, never()).issue(any(), any(), any());

        ResponseEntity<?> incomplete = handler.handle(request(hostedRepo, "PUT", "-/user/org.couchdb.user:alice",
                "{\"name\":\"alice\"}".getBytes(StandardCharsets.UTF_8), null));
        assertThat(incomplete.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<?> garbage = handler.handle(request(hostedRepo, "PUT", "-/user/org.couchdb.user:alice",
                "nope".getBytes(StandardCharsets.UTF_8), null));
        assertThat(garbage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void searchParametersAreParsedAndClamped() throws Exception {
        RepositoryRequest request = request(proxyRepo, "GET", "-/v1/search");
        http(request).setParameter("text", "lodash");
        http(request).setParameter("size", "9999");
        http(request).setParameter("from", "-5");
        when(proxy.isOffline(proxyRepo)).thenReturn(true);
        ResponseEntity<?> response = handler.handle(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = jsonBody(response);
        assertThat(json.path("objects").isArray()).isTrue();
        assertThat(json.path("total").asLong()).isZero();
    }

    private static byte[] packument(String name, String version, String tarballUrl) {
        ObjectNode doc = NpmPackument.newDocument(name);
        doc.put("description", "d");
        ObjectNode v = MAPPER.createObjectNode();
        v.put("name", name);
        v.put("version", version);
        ObjectNode dist = v.putObject("dist");
        dist.put("shasum", "x");
        if (tarballUrl != null) {
            dist.put("tarball", tarballUrl);
        }
        NpmPackument.putVersion(doc, version, v, java.time.Instant.now());
        NpmPackument.distTags(doc).put("latest", version);
        doc.put("_rev", "1-abc");
        return MAPPER.writeValueAsBytes(doc);
    }
}
