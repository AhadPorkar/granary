package dev.granary.format.swift;

import static dev.granary.format.swift.SwiftTestSupport.MANIFEST;
import static dev.granary.format.swift.SwiftTestSupport.MANIFEST_58;
import static dev.granary.format.swift.SwiftTestSupport.METADATA;
import static dev.granary.format.swift.SwiftTestSupport.body;
import static dev.granary.format.swift.SwiftTestSupport.bodyBytes;
import static dev.granary.format.swift.SwiftTestSupport.detail;
import static dev.granary.format.swift.SwiftTestSupport.json;
import static dev.granary.format.swift.SwiftTestSupport.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.GranaryException;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.GranaryPrincipal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/** The handler on top of an in-memory storage: hosted publishing and reading, groups of hosted members, the SPI surface. */
class SwiftFormatHandlerTest {

    @TempDir
    Path dataDir;

    private final FakeStorage storage = new FakeStorage();
    private final GroupResolver groups = mock(GroupResolver.class);
    private final Repository hosted = SwiftTestSupport.hosted("swift-hosted");
    private SwiftFormatHandler handler;

    @BeforeEach
    void setUp() {
        GranaryProperties properties = new GranaryProperties();
        properties.setDataDir(dataDir);
        handler = new SwiftFormatHandler(storage.mock, mock(ProxyService.class), mock(UpstreamClient.class), mock(UpstreamHealth.class),
                mock(NegativeCache.class), groups, SwiftTestSupport.MAPPER, properties);
    }

    private ResponseEntity<?> get(Repository repo, String path) throws IOException {
        return handler.handle(SwiftTestSupport.get(repo, path));
    }

    private ResponseEntity<?> get(Repository repo, String path, String query) throws IOException {
        RepositoryRequest request = SwiftTestSupport.get(repo, path);
        ((MockHttpServletRequest) request.http()).setQueryString(query);
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            ((MockHttpServletRequest) request.http()).addParameter(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return handler.handle(request);
    }

    private ResponseEntity<?> publish(Repository repo, String path, byte[] archive, String metadata) throws IOException {
        return handler.handle(SwiftTestSupport.publish(repo, path, archive, metadata));
    }

    // --- SPI surface -------------------------------------------------------------------------------------

    @Test
    void identityTemplatesAndSettings() {
        assertThat(handler.format()).isEqualTo("swift");
        assertThat(handler.displayName()).isEqualTo("Swift package registry");
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder(RepositoryType.HOSTED, RepositoryType.PROXY, RepositoryType.GROUP);
        assertThat(handler.templates()).extracting(RepositoryTemplate::name).containsExactly("swift-hosted", "swift-group");
        assertThat(handler.templates().get(1).members()).containsExactly("swift-hosted");
        assertThat(handler.templates()).allSatisfy(t -> assertThat(t.format()).isEqualTo("swift"));
        assertThat(handler.settings()).extracting(s -> s.key()).containsExactly("swift.requireSignature", "swift.maxArchiveSizeMb");
    }

    @Test
    void validateChecksTheSettings() {
        Repository r = SwiftTestSupport.hosted("r");
        r.setFormatSettings(Map.of("swift.requireSignature", "TRUE", "swift.maxArchiveSizeMb", "512"));
        assertThatCode(() -> handler.validate(r)).doesNotThrowAnyException();
        r.setFormatSettings(Map.of("swift.requireSignature", "sometimes"));
        assertThatThrownBy(() -> handler.validate(r)).isInstanceOf(GranaryException.class).hasMessageContaining("true or false");
        for (String bad : List.of("0", "-5", "1.5", "abc", "99999999")) {
            r.setFormatSettings(Map.of("swift.maxArchiveSizeMb", bad));
            assertThatThrownBy(() -> handler.validate(r)).as(bad).isInstanceOf(GranaryException.class);
        }
    }

    @Test
    void metadataPaths() {
        assertThat(handler.isMetadataPath("acme/demo/releases.json")).isTrue();
        assertThat(handler.isMetadataPath("acme/demo/1.0.0/metadata.json")).isTrue();
        assertThat(handler.isMetadataPath("-/identifiers/0123.json")).isTrue();
        assertThat(handler.isMetadataPath("acme/demo/1.0.0.zip")).isFalse();
        assertThat(handler.isMetadataPath("acme/demo/1.0.0/Package.swift")).isFalse();
    }

    @Test
    void requiredAccessLetsAnonymousRefusalsThroughWithoutTheServerChallenge() {
        // anonymous read allowed: READ as usual
        assertThat(handler.requiredAccess(SwiftTestSupport.get(hosted, "acme/demo"))).isEqualTo(AccessLevel.READ);
        // anonymous read not allowed: NONE, handle() answers 401 without a challenge
        RepositoryRequest anonymousGet = SwiftTestSupport.withPrincipal(SwiftTestSupport.get(hosted, "acme/demo"),
                SwiftTestSupport.anonymousWithoutRead());
        assertThat(handler.requiredAccess(anonymousGet)).isEqualTo(AccessLevel.NONE);
        RepositoryRequest anonymousPut = SwiftTestSupport.request(hosted, "PUT", "acme/demo/1.0.0");
        assertThat(handler.requiredAccess(anonymousPut)).isEqualTo(AccessLevel.NONE);

        GranaryPrincipal reader = new GranaryPrincipal(UUID.randomUUID(), "reader", Set.of("reader"), Set.of("repository:read:*"),
                false, GranaryPrincipal.AuthMethod.API_TOKEN);
        RepositoryRequest readerPut = SwiftTestSupport.withPrincipal(anonymousPut, reader);
        assertThat(handler.requiredAccess(readerPut)).isEqualTo(AccessLevel.WRITE); // the server answers 403
        assertThat(handler.requiredAccess(SwiftTestSupport.withPrincipal(SwiftTestSupport.request(hosted, "POST", ""), reader)))
                .isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(SwiftTestSupport.withPrincipal(SwiftTestSupport.request(hosted, "POST", "login"), reader)))
                .isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(SwiftTestSupport.withPrincipal(SwiftTestSupport.request(hosted, "DELETE", "acme/demo"), reader)))
                .isEqualTo(AccessLevel.WRITE);
    }

    @Test
    void anonymousCallersWithoutThePrivilegeGetAPlain401() throws IOException {
        ResponseEntity<?> response = handler.handle(SwiftTestSupport.withPrincipal(SwiftTestSupport.get(hosted, "acme/demo"),
                SwiftTestSupport.anonymousWithoutRead()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().containsHeader("WWW-Authenticate")).isFalse();
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(detail(response)).isEqualTo("authentication required for read access to repository 'swift-hosted'; "
                + "log in with swift package-registry login");
    }

    @Test
    void connectSnippetsForHttpsAndHttp() {
        Repository group = SwiftTestSupport.group("swift-group", "swift-hosted");
        ConnectContext https = new ConnectContext(hosted, "https://pkg.example.com/repository/swift-hosted", "https://pkg.example.com",
                "jane", ConnectContext.TOKEN_PLACEHOLDER, true);
        Map<String, String> code = handler.connectSnippets(https).stream().collect(Collectors.toMap(ConnectSnippet::id, ConnectSnippet::code));
        assertThat(code.get("swift-registry-set")).contains("swift package-registry set https://pkg.example.com/repository/swift-hosted --global\n");
        assertThat(code.get("swift-registry-login")).doesNotContain("HTTPS only")
                .contains("swift package-registry login https://pkg.example.com/repository/swift-hosted --token <api-token> --no-confirm");
        assertThat(code.get("swift-registry-login-basic")).contains("--username jane --password <api-token>");
        assertThat(code.get("registries-json")).contains("\"pkg.example.com\"");
        assertThat(code.get("netrc")).contains("machine pkg.example.com\n");
        assertThat(code.get("swift-registry-publish")).contains("--url https://pkg.example.com/repository/swift-hosted")
                .doesNotContain("read-only");

        ConnectContext http = new ConnectContext(group, "http://localhost:8113/repository/swift-group", "http://localhost:8113",
                ConnectContext.USERNAME_PLACEHOLDER, ConnectContext.TOKEN_PLACEHOLDER, true);
        Map<String, String> groupCode = handler.connectSnippets(http).stream().collect(Collectors.toMap(ConnectSnippet::id, ConnectSnippet::code));
        assertThat(groupCode.get("swift-registry-set")).contains("--global --allow-insecure-http");
        assertThat(groupCode.get("swift-registry-set-scope")).contains("--scope acme --allow-insecure-http");
        assertThat(groupCode.get("swift-registry-login")).contains("HTTPS only");
        // SwiftPM keys registries.json authentication by host and port, netrc by host alone
        assertThat(groupCode.get("registries-json")).contains("\"localhost:8113\"");
        assertThat(groupCode.get("netrc")).contains("machine localhost\n");
        assertThat(groupCode.get("swift-registry-publish")).contains("read-only").contains("<hosted repository URL>");

        // Granary below a context path: credentials are keyed by host (and port), never by the path
        ConnectContext subPath = new ConnectContext(hosted, "https://Pkg.Example.com:8443/granary/repository/swift-hosted",
                "https://Pkg.Example.com:8443/granary", "jane", ConnectContext.TOKEN_PLACEHOLDER, true);
        Map<String, String> subPathCode = handler.connectSnippets(subPath).stream()
                .collect(Collectors.toMap(ConnectSnippet::id, ConnectSnippet::code));
        assertThat(subPathCode.get("registries-json")).contains("\"pkg.example.com:8443\": {").doesNotContain("granary\": {");
        assertThat(subPathCode.get("netrc")).startsWith("machine pkg.example.com\n");
        assertThat(subPathCode.get("swift-registry-set")).contains("https://Pkg.Example.com:8443/granary/repository/swift-hosted");
    }

    @Test
    void credentialKeysFromServerUrls() {
        assertThat(SwiftSnippets.authenticationKey("https://pkg.example.com")).isEqualTo("pkg.example.com");
        assertThat(SwiftSnippets.authenticationKey("http://localhost:8113")).isEqualTo("localhost:8113");
        assertThat(SwiftSnippets.authenticationKey("https://pkg.example.com/granary")).isEqualTo("pkg.example.com");
        assertThat(SwiftSnippets.authenticationKey("http://[::1]:8113/x")).isEqualTo("[::1]:8113");
        assertThat(SwiftSnippets.netrcMachine("http://localhost:8113/granary")).isEqualTo("localhost");
        assertThat(SwiftSnippets.netrcMachine("https://PKG.example.com")).isEqualTo("pkg.example.com");
        // not a parsable URL: the old textual fallback
        assertThat(SwiftSnippets.authenticationKey("https://bad host:1/x")).isEqualTo("bad host:1");
        assertThat(SwiftSnippets.netrcMachine("https://bad host:1/x")).isEqualTo("bad host");
    }

    // --- hosted ------------------------------------------------------------------------------------------

    @Test
    void publishStoresTheReleaseAndItsManifests() throws IOException {
        byte[] zip = SwiftTestSupport.sourceArchive("demo", "x", true);
        ResponseEntity<?> created = publish(hosted, "acme/demo/1.0.0", zip, METADATA);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).hasToString("https://granary.test/repository/swift-hosted/acme/demo/1.0.0");
        assertThat(storage.paths(hosted)).contains("acme/demo/1.0.0.zip", "acme/demo/1.0.0/metadata.json",
                "acme/demo/1.0.0/Package.swift", "acme/demo/1.0.0/Package@swift-5.8.swift");
        assertThat(storage.coordinates.values()).allSatisfy(c -> {
            assertThat(c.namespace()).isEqualTo("acme");
            assertThat(c.name()).isEqualTo("demo");
            assertThat(c.version()).isEqualTo("1.0.0");
        });
        assertThat(storage.assets.values().stream().filter(a -> a.getPath().endsWith("metadata.json")).findFirst().orElseThrow().isMetadata())
                .isTrue();

        JsonNode release = json(get(hosted, "acme/demo/1.0.0"));
        assertThat(release.path("resources").get(0).path("checksum").stringValue()).isEqualTo(sha256(zip));
        assertThat(bodyBytes(get(hosted, "acme/demo/1.0.0.zip"))).isEqualTo(zip);
        assertThat(body(get(hosted, "acme/demo/1.0.0/Package.swift"))).isEqualTo(MANIFEST);
        assertThat(body(get(hosted, "acme/demo/1.0.0/Package.swift", "swift-version=5.8"))).isEqualTo(MANIFEST_58);
        ResponseEntity<?> redirect = get(hosted, "acme/demo/1.0.0/Package.swift", "swift-version=4.2");
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(json(get(hosted, "identifiers", "url=git@github.com:acme/demo.git")).path("identifiers").get(0).stringValue())
                .isEqualTo("acme.demo");
    }

    @Test
    void failedPublishesLeaveNothingBehind() throws IOException {
        assertThat(publish(hosted, "acme/demo/1.0.0", "garbage".getBytes(), null).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(publish(hosted, "acme/demo/1.0.0", SwiftTestSupport.sourceArchive("x"), "{\"author\":5}").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(storage.paths(hosted)).isEmpty();
        try (var spooled = java.nio.file.Files.list(dataDir.resolve("tmp").resolve("swift"))) {
            assertThat(spooled).isEmpty();
        }
    }

    @Test
    void replacingAReleaseKeepsTheIdentifiersIndexInStep() throws IOException {
        hosted.setAllowRedeploy(true);
        publish(hosted, "acme/demo/1.0.0", SwiftTestSupport.sourceArchive("a"), "{\"repositoryURLs\":[\"https://github.com/acme/old\"]}");
        publish(hosted, "acme/demo/1.0.0", SwiftTestSupport.sourceArchive("b"), "{\"repositoryURLs\":[\"https://github.com/acme/new\"]}");
        assertThat(get(hosted, "identifiers", "url=https://github.com/acme/old").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(hosted, "identifiers", "url=https://github.com/acme/new").getStatusCode()).isEqualTo(HttpStatus.OK);

        // a URL still listed by another release survives the deletion of one of them
        publish(hosted, "acme/demo/1.1.0", SwiftTestSupport.sourceArchive("c"), "{\"repositoryURLs\":[\"https://github.com/acme/new\"]}");
        assertThat(handler.handle(SwiftTestSupport.withPrincipal(SwiftTestSupport.request(hosted, "DELETE", "acme/demo/1.0.0"),
                SwiftTestSupport.user("u"))).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get(hosted, "identifiers", "url=https://github.com/acme/new").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void encodedSeparatorsInTheRequestPathAreRefused() throws IOException {
        byte[] zip = SwiftTestSupport.sourceArchive("encoded");
        assertThat(publish(hosted, "acme/demo/1.0.0", zip, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // the dispatcher hands the handler the decoded path; only the raw URI still shows what the client sent
        for (String raw : List.of("acme%2Fdemo/1.0.0", "acme/demo%2f1.0.0.zip", "acme/demo/1.0.0%2FPackage.swift", "acme%5Cdemo/1.0.0")) {
            for (String method : List.of("GET", "HEAD", "DELETE")) {
                RepositoryRequest request = SwiftTestSupport.withPrincipal(SwiftTestSupport.request(hosted, method,
                        raw.replaceAll("(?i)%2f", "/").replaceAll("(?i)%5c", "\\\\")), SwiftTestSupport.user("u"));
                ((MockHttpServletRequest) request.http()).setRequestURI("/repository/swift-hosted/" + raw);
                ResponseEntity<?> response = handler.handle(request);
                assertThat(response.getStatusCode()).as(method + " " + raw).isEqualTo(HttpStatus.BAD_REQUEST);
                if (!"HEAD".equals(method)) {
                    assertThat(detail(response)).contains("encoded slashes");
                }
            }
        }
        RepositoryRequest put = SwiftTestSupport.publish(hosted, "acme/demo/1.1.0", zip, null);
        ((MockHttpServletRequest) put.http()).setRequestURI("/repository/swift-hosted/acme%2Fdemo/1.1.0");
        assertThat(handler.handle(put).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(storage.paths(hosted)).doesNotContain("acme/demo/1.1.0.zip").contains("acme/demo/1.0.0.zip");
        // an encoded slash in the query (identifiers?url=) is not part of the path
        RepositoryRequest lookup = SwiftTestSupport.get(hosted, "identifiers");
        ((MockHttpServletRequest) lookup.http()).setQueryString("url=https%3A%2F%2Fgithub.com%2Facme%2Fdemo");
        ((MockHttpServletRequest) lookup.http()).addParameter("url", "https://github.com/acme/demo");
        assertThat(handler.handle(lookup).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(hosted, "acme/demo/1.0.0").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- groups ------------------------------------------------------------------------------------------

    @Test
    void groupsMergeHostedMembersAndServeFromTheOwner() throws IOException {
        Repository first = SwiftTestSupport.hosted("first");
        Repository second = SwiftTestSupport.hosted("second");
        Repository group = SwiftTestSupport.group("all", "first", "second");
        when(groups.leafMembers(group)).thenReturn(List.of(first, second));
        byte[] firstZip = SwiftTestSupport.sourceArchive("first");
        byte[] secondZip = SwiftTestSupport.sourceArchive("second");
        publish(first, "acme/demo/1.0.0", firstZip, null);
        publish(second, "acme/demo/1.0.0", secondZip, null);
        publish(second, "acme/demo/2.0.0", SwiftTestSupport.sourceArchive("second 2"), null);

        JsonNode list = json(get(group, "acme/demo"));
        assertThat(list.path("releases").propertyNames()).containsExactly("2.0.0", "1.0.0");
        assertThat(list.path("releases").path("1.0.0").path("url").stringValue())
                .isEqualTo("https://granary.test/repository/all/acme/demo/1.0.0");
        assertThat(bodyBytes(get(group, "acme/demo/1.0.0.zip"))).isEqualTo(firstZip);
        assertThat(json(get(group, "acme/demo/1.0.0")).path("resources").get(0).path("checksum").stringValue()).isEqualTo(sha256(firstZip));
        assertThat(json(get(group, "acme/demo/2.0.0")).path("version").stringValue()).isEqualTo("2.0.0");

        ResponseEntity<?> refused = publish(group, "acme/demo/3.0.0", firstZip, null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(detail(refused)).isEqualTo("repository 'all' is a group and read-only; publish to its hosted member 'first'");

        Repository empty = SwiftTestSupport.group("empty");
        when(groups.leafMembers(empty)).thenReturn(List.of());
        assertThat(detail(publish(empty, "acme/demo/3.0.0", firstZip, null))).endsWith("it has no hosted member to publish to");
        assertThat(get(empty, "acme/demo").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void onlyUrlsTheCredentialsMayGoToAreFollowed() {
        Repository proxy = SwiftTestSupport.proxy("p", "https://registry.example.com/swift/");
        assertThat(SwiftUpstream.trustedUrl(proxy, "https://registry.example.com/swift/a/b/1.0.0/Package.swift?swift-version=6")).isPresent();
        assertThat(SwiftUpstream.trustedUrl(proxy, "https://REGISTRY.example.com/x")).isPresent();
        assertThat(SwiftUpstream.trustedUrl(proxy, "http://registry.example.com/x")).isEmpty();
        assertThat(SwiftUpstream.trustedUrl(proxy, "https://registry.example.com:8443/x")).isEmpty();
        assertThat(SwiftUpstream.trustedUrl(proxy, "https://evil.example.com/x")).isEmpty();
        assertThat(SwiftUpstream.trustedUrl(proxy, "/relative")).isEmpty();
        assertThat(SwiftUpstream.trustedUrl(proxy, "not a url")).isEmpty();
        assertThat(SwiftUpstream.trustedUrl(proxy, null)).isEmpty();
        assertThat(SwiftUpstream.trustedUrl(proxy, "https://registry.example.com:443/x")).isPresent();
        assertThat(SwiftUpstream.trustedUrl(proxy, "https://registry.example.com@evil.example.com/x")).isEmpty();
        proxy.setFormatSettings(java.util.Map.of("upstream.credentialHosts", "files.example.com"));
        assertThat(SwiftUpstream.trustedUrl(proxy, "https://files.example.com/a/b/1.0.0/Package.swift")).isPresent();
        assertThat(SwiftUpstream.trustedUrl(proxy, "https://evil.example.com/x")).isEmpty();
    }
}
