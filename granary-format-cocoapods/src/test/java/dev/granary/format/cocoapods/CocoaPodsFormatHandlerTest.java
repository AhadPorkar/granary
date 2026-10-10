package dev.granary.format.cocoapods;

import static dev.granary.format.cocoapods.CocoaPodsTestSupport.httpPodspec;
import static dev.granary.format.cocoapods.CocoaPodsTestSupport.podspec;
import static dev.granary.format.cocoapods.CocoaPodsTestSupport.utf8;
import static dev.granary.format.cocoapods.CocoaPodsTestSupport.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.GranaryException;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.storage.StoredContent;
import dev.granary.format.cocoapods.CocoaPodsTestSupport.FakeStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;

class CocoaPodsFormatHandlerTest {

    private static final String SERVER = "http://granary.test";

    @TempDir
    Path dataDir;

    private final ProxyService proxyService = mock(ProxyService.class);
    private final GroupResolver groups = mock(GroupResolver.class);
    private FakeStorage storage;
    private CocoaPodsFormatHandler handler;
    private Repository hostedRepo;
    private Repository proxyRepo;
    private Repository group;

    @BeforeEach
    void setUp() {
        storage = new FakeStorage();
        GranaryProperties properties = new GranaryProperties();
        properties.setDataDir(dataDir);
        CocoaPodsHosted hosted = new CocoaPodsHosted(storage.mock);
        CocoaPodsProxy proxy = new CocoaPodsProxy(proxyService, storage.mock, mock(UpstreamClient.class), new NegativeCache());
        handler = new CocoaPodsFormatHandler(storage.mock, hosted, proxy, new CocoaPodsResolver(hosted, proxy, groups),
                groups, properties);
        hostedRepo = CocoaPodsTestSupport.repository("cocoapods-hosted", RepositoryType.HOSTED);
        proxyRepo = CocoaPodsTestSupport.repository("cocoapods-cdn", RepositoryType.PROXY);
        group = CocoaPodsTestSupport.repository("cocoapods-group", RepositoryType.GROUP);
        when(groups.leafMembers(group)).thenReturn(List.of(hostedRepo, proxyRepo));
        when(proxyService.fetch(any(), any(String.class), any(FetchOptions.class))).thenReturn(Optional.empty());
    }

    // --- request helpers -------------------------------------------------------------------

    private static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    private static RepositoryRequest request(Repository repo, String method, String path) {
        return request(repo, method, path, null);
    }

    private static RepositoryRequest request(Repository repo, String method, String path, byte[] body) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        if (body != null) {
            http.setContent(body);
            http.setContentType("application/json");
        }
        return new RepositoryRequest(repo, path, http, user(), baseUrl(repo));
    }

    private static GranaryPrincipal user() {
        return new GranaryPrincipal(null, "alice", Set.of("developer"), Set.of("repository:write:*"), false,
                GranaryPrincipal.AuthMethod.API_TOKEN);
    }

    private ResponseEntity<?> handle(Repository repo, String method, String path) throws IOException {
        return handler.handle(request(repo, method, path));
    }

    private ResponseEntity<?> put(Repository repo, String path, String body) throws IOException {
        return handler.handle(request(repo, "PUT", path, utf8(body)));
    }

    private static String body(ResponseEntity<?> response) {
        Object body = response.getBody();
        if (body == null) {
            return "";
        }
        if (body instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (body instanceof Resource resource) {
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
        return body.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(ResponseEntity<?> response) {
        assertThat(response.getBody()).isInstanceOf(Map.class);
        return (Map<String, Object>) response.getBody();
    }

    private static void assertError(ResponseEntity<?> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(json(response)).containsEntry("code", code);
    }

    // --- identity, templates and settings --------------------------------------------------

    @Test
    void identityTemplatesAndSettings() {
        assertThat(handler.format()).isEqualTo("cocoapods");
        assertThat(handler.displayName()).isEqualTo("CocoaPods");
        List<RepositoryTemplate> templates = handler.templates();
        assertThat(templates).extracting(RepositoryTemplate::name)
                .containsExactly("cocoapods-cdn", "cocoapods-hosted", "cocoapods-group");
        assertThat(templates).allSatisfy(t -> assertThat(t.format()).isEqualTo("cocoapods"));
        assertThat(templates.get(0).type()).isEqualTo(RepositoryType.PROXY);
        assertThat(templates.get(0).remoteUrl()).isEqualTo("https://cdn.cocoapods.org/");
        assertThat(templates.get(1).type()).isEqualTo(RepositoryType.HOSTED);
        assertThat(templates.get(2).type()).isEqualTo(RepositoryType.GROUP);
        // hosted first, so private pods win over trunk pods of the same name
        assertThat(templates.get(2).members()).containsExactly("cocoapods-hosted", "cocoapods-cdn");

        assertThat(handler.settings()).singleElement().satisfies(s -> {
            assertThat(s.key()).isEqualTo("cocoapods.rewriteHttpSources");
            assertThat(s.type()).isEqualTo(SettingDescriptor.Type.BOOLEAN);
            assertThat(s.defaultValue()).isEqualTo("true");
            assertThat(s.appliesTo()).containsExactlyInAnyOrder(RepositoryType.PROXY, RepositoryType.GROUP);
        });
    }

    @Test
    void theRewriteSettingMustBeABoolean() {
        handler.validate(proxyRepo);
        for (String ok : List.of("true", "false", "TRUE", "No", "1", "0", "yes")) {
            proxyRepo.setFormatSettings(Map.of(CocoaPodsProxy.REWRITE_SETTING, ok));
            handler.validate(proxyRepo);
        }
        proxyRepo.setFormatSettings(Map.of(CocoaPodsProxy.REWRITE_SETTING, "sometimes"));
        assertThatThrownBy(() -> handler.validate(proxyRepo))
                .isInstanceOfSatisfying(GranaryException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("repository.cocoapods.setting.invalid");
                });
    }

    @Test
    void indexFilesAreMetadataAndPodspecsAndArchivesAreNot() {
        assertThat(handler.isMetadataPath("CocoaPods-version.yml")).isTrue();
        assertThat(handler.isMetadataPath("all_pods_versions_a_7_5.txt")).isTrue();
        assertThat(handler.isMetadataPath("all_pods_versions.txt")).isTrue();
        assertThat(handler.isMetadataPath("all_pods.txt")).isTrue();
        assertThat(handler.isMetadataPath("deprecated_podspecs.txt")).isTrue();
        assertThat(handler.isMetadataPath("Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json")).isFalse();
        assertThat(handler.isMetadataPath("sources/Bin/1.0/Bin-1.0.zip")).isFalse();
        assertThat(handler.isMetadataPath("")).isFalse();
        assertThat(handler.isMetadataPath("../etc/passwd")).isFalse();
    }

    @Test
    void readsNeedReadAccessAndEverythingElseWrite() {
        assertThat(handler.requiredAccess(request(hostedRepo, "GET", "all_pods.txt"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "HEAD", "Specs/A/1.0/A.podspec.json"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "PUT", "Specs/A/1.0/A.podspec.json"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hostedRepo, "POST", "api/pods"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hostedRepo, "DELETE", "Specs/A/1.0"))).isEqualTo(AccessLevel.WRITE);
    }

    // --- hosted ----------------------------------------------------------------------------

    @Test
    void aPushedPodShowsUpInEveryIndex() throws IOException {
        ResponseEntity<?> pushed = put(hostedRepo, "Specs/AFNetworking/4.0.1/AFNetworking.podspec.json",
                podspec("AFNetworking", "4.0.1"));
        assertThat(pushed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(pushed)).containsEntry("name", "AFNetworking").containsEntry("version", "4.0.1")
                .containsEntry("podspec", baseUrl(hostedRepo) + "/Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json")
                .doesNotContainKey("source");
        // the sharded form of the path works too
        assertThat(put(hostedRepo, "Specs/a/7/5/AFNetworking/4.1.0/AFNetworking.podspec.json",
                podspec("AFNetworking", "4.1.0")).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<?> versionFile = handle(hostedRepo, "GET", "CocoaPods-version.yml");
        assertThat(versionFile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(versionFile)).contains("last: ").contains("prefix_lengths:\n- 1\n- 1\n- 1\n");

        ResponseEntity<?> shard = handle(hostedRepo, "GET", "all_pods_versions_a_7_5.txt");
        assertThat(shard.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(shard.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        assertThat(body(shard)).isEqualTo("AFNetworking/4.0.1/4.1.0\n");
        assertThat(body(handle(hostedRepo, "GET", "all_pods_versions_0_0_0.txt"))).isEmpty();
        assertThat(body(handle(hostedRepo, "GET", "all_pods.txt"))).isEqualTo("AFNetworking\n");
        assertThat(handle(hostedRepo, "GET", "deprecated_podspecs.txt").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(handle(hostedRepo, "GET", "deprecated_podspecs.txt"))).isEmpty();

        ResponseEntity<?> spec = handle(hostedRepo, "GET", "Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json");
        assertThat(spec.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(spec.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(body(spec)).contains("\"name\": \"AFNetworking\"").contains("\"version\": \"4.0.1\"");
        assertThat(storage.downloads).hasSize(1);
        // the short form is answered too
        assertThat(handle(hostedRepo, "GET", "Specs/AFNetworking/4.0.1/AFNetworking.podspec.json").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void indexesAnswerConditionalAndHeadRequests() throws IOException {
        put(hostedRepo, "Specs/Alamofire/5.9.1/Alamofire.podspec.json", podspec("Alamofire", "5.9.1"));
        ResponseEntity<?> shard = handle(hostedRepo, "GET", "all_pods_versions_d_a_2.txt");
        String etag = shard.getHeaders().getETag();
        assertThat(etag).isNotNull();
        assertThat(shard.getHeaders().getCacheControl()).isEqualTo("no-cache");

        RepositoryRequest conditional = request(hostedRepo, "GET", "all_pods_versions_d_a_2.txt");
        ((MockHttpServletRequest) conditional.http()).addHeader(HttpHeaders.IF_NONE_MATCH, etag);
        assertThat(handler.handle(conditional).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        // the client's saved ETag no longer matches once the shard changed
        put(hostedRepo, "Specs/Alamofire/5.10.0/Alamofire.podspec.json", podspec("Alamofire", "5.10.0"));
        RepositoryRequest stale = request(hostedRepo, "GET", "all_pods_versions_d_a_2.txt");
        ((MockHttpServletRequest) stale.http()).addHeader(HttpHeaders.IF_NONE_MATCH, etag);
        ResponseEntity<?> fresh = handler.handle(stale);
        assertThat(fresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(fresh)).isEqualTo("Alamofire/5.9.1/5.10.0\n");

        ResponseEntity<?> head = handle(hostedRepo, "HEAD", "Specs/Alamofire/5.9.1/Alamofire.podspec.json");
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isPositive();
        assertThat(storage.downloads).isEmpty();
    }

    @Test
    void missingPodsAndWrongShardsAreNotFound() throws IOException {
        put(hostedRepo, "Specs/AFNetworking/4.0.1/AFNetworking.podspec.json", podspec("AFNetworking", "4.0.1"));
        assertError(handle(hostedRepo, "GET", "Specs/a/7/5/AFNetworking/9.9.9/AFNetworking.podspec.json"),
                HttpStatus.NOT_FOUND, "content.notFound");
        assertError(handle(hostedRepo, "GET", "Specs/0/0/0/AFNetworking/4.0.1/AFNetworking.podspec.json"),
                HttpStatus.NOT_FOUND, "content.notFound");
        // hosted repositories shard by three characters; other shard files do not exist
        assertError(handle(hostedRepo, "GET", "all_pods_versions_a_7.txt"), HttpStatus.NOT_FOUND, "content.notFound");
        assertError(handle(hostedRepo, "GET", "all_pods_versions.txt"), HttpStatus.NOT_FOUND, "content.notFound");
        // a version directory is not a document
        assertThat(handle(hostedRepo, "GET", "Specs/a/7/5/AFNetworking/4.0.1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handle(hostedRepo, "GET", "README.md").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void pushesAreValidated() throws IOException {
        assertError(put(hostedRepo, "Specs/0/0/0/AFNetworking/4.0.1/AFNetworking.podspec.json", podspec("AFNetworking", "4.0.1")),
                HttpStatus.BAD_REQUEST, "repository.cocoapods.invalid");
        assertError(put(hostedRepo, "Specs/AFNetworking/4.0.1/AFNetworking.podspec.json", podspec("AFNetworking", "4.0.2")),
                HttpStatus.BAD_REQUEST, "repository.cocoapods.invalidPodspec");
        assertError(put(hostedRepo, "Specs/Foo/1.0/Foo.podspec.json", "Pod::Spec.new do |s| end"),
                HttpStatus.BAD_REQUEST, "repository.cocoapods.invalidPodspec");
        assertError(handler.handle(request(hostedRepo, "PUT", "Specs/Foo/1.0/Foo.podspec.json", new byte[0])),
                HttpStatus.BAD_REQUEST, "repository.cocoapods.invalidPodspec");
        assertThat(put(hostedRepo, "Specs/Foo/1.0/Foo.podspec.json", podspec("Foo", "1.0")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertError(put(hostedRepo, "Specs/Foo/1.0/Foo.podspec.json", podspec("Foo", "1.0")),
                HttpStatus.CONFLICT, "repository.cocoapods.versionExists");
        hostedRepo.setAllowRedeploy(true);
        assertThat(put(hostedRepo, "Specs/Foo/1.0/Foo.podspec.json", podspec("Foo", "1.0")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void invalidPathsAreRefused() throws IOException {
        assertError(handle(hostedRepo, "GET", "Specs/a/7/5/../../x/1.0/x.podspec.json"), HttpStatus.BAD_REQUEST, "path.invalid");
        assertError(handle(hostedRepo, "GET", "/all_pods.txt"), HttpStatus.BAD_REQUEST, "path.invalid");
        assertError(put(hostedRepo, "Specs/Foo/1.0/..", "{}"), HttpStatus.BAD_REQUEST, "path.invalid");
    }

    /** The dispatcher hands over the decoded path; the raw URI still shows what the client sent. */
    private static RepositoryRequest encoded(Repository repo, String method, String rawPath, String decodedPath, byte[] body) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + rawPath);
        if (body != null) {
            http.setContent(body);
            http.setContentType("application/json");
        }
        return new RepositoryRequest(repo, decodedPath, http, user(), baseUrl(repo));
    }

    @Test
    void encodedSlashesAndBackslashesNeverBecomeSeparators() throws IOException {
        storage.put(hostedRepo, "Specs/1/3/5/Foo/1.0/Foo.podspec.json", podspec("Foo", "1.0"));
        assertThat(handle(hostedRepo, "GET", "Specs/Foo/1.0/Foo.podspec.json").getStatusCode()).isEqualTo(HttpStatus.OK);

        // Specs/Foo%2F1.0/Foo.podspec.json is one segment "Foo/1.0", not the podspec of Foo 1.0
        for (String raw : List.of("Specs/Foo%2F1.0/Foo.podspec.json", "Specs/Foo%2f1.0/Foo.podspec.json",
                "Specs%2FFoo/1.0/Foo.podspec.json")) {
            assertError(handler.handle(encoded(hostedRepo, "GET", raw, "Specs/Foo/1.0/Foo.podspec.json", null)),
                    HttpStatus.BAD_REQUEST, "path.invalid");
        }
        assertError(handler.handle(encoded(hostedRepo, "GET", "sources/Foo/1.0/a%5Cb.zip", "sources/Foo/1.0/a\\b.zip", null)),
                HttpStatus.BAD_REQUEST, "path.invalid");
        assertError(handler.handle(encoded(hostedRepo, "PUT", "Specs/Bar%2F1.0/Bar.podspec.json", "Specs/Bar/1.0/Bar.podspec.json",
                utf8(podspec("Bar", "1.0")))), HttpStatus.BAD_REQUEST, "path.invalid");
        assertThat(storage.paths(hostedRepo)).containsExactly("Specs/1/3/5/Foo/1.0/Foo.podspec.json");
        // a percent sign in a name (pod sends %25, so "a%2Fb" arrives as a%252Fb) is not a separator
        assertError(handler.handle(encoded(hostedRepo, "GET", "Specs/a%252Fb/1.0/a%252Fb.podspec.json",
                "Specs/a%2Fb/1.0/a%2Fb.podspec.json", null)), HttpStatus.NOT_FOUND, "content.notFound");
    }

    @Test
    void indexFilesCannotBeWritten() throws IOException {
        for (String path : List.of("", "CocoaPods-version.yml", "all_pods_versions_a_7_5.txt", "all_pods.txt",
                "deprecated_podspecs.txt")) {
            assertError(put(hostedRepo, path, "x"), HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed");
            assertError(handle(hostedRepo, "DELETE", path), HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed");
        }
        assertError(handle(hostedRepo, "POST", "Specs/Foo/1.0/Foo.podspec.json"), HttpStatus.METHOD_NOT_ALLOWED,
                "method.notAllowed");
        assertError(handle(hostedRepo, "GET", "api/pods"), HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed");
        assertError(put(hostedRepo, "somewhere/else", "x"), HttpStatus.NOT_FOUND, "content.notFound");
    }

    @Test
    void proxiesAndGroupsAreReadOnlyAndAGroupNamesItsHostedMember() throws IOException {
        ResponseEntity<?> proxied = put(proxyRepo, "Specs/Foo/1.0/Foo.podspec.json", podspec("Foo", "1.0"));
        assertError(proxied, HttpStatus.METHOD_NOT_ALLOWED, "repository.readOnly");
        assertThat(json(proxied).get("message").toString()).contains("'cocoapods-cdn' is a proxy repository");

        ResponseEntity<?> grouped = put(group, "Specs/Foo/1.0/Foo.podspec.json", podspec("Foo", "1.0"));
        assertError(grouped, HttpStatus.METHOD_NOT_ALLOWED, "repository.readOnly");
        assertThat(json(grouped).get("message").toString()).contains("use its hosted member 'cocoapods-hosted'");

        Repository proxyOnly = CocoaPodsTestSupport.repository("proxy-only", RepositoryType.GROUP);
        when(groups.leafMembers(proxyOnly)).thenReturn(List.of(proxyRepo));
        assertThat(json(handle(proxyOnly, "DELETE", "Specs/Foo/1.0")).get("message").toString())
                .contains("it has no hosted member");
        assertError(handler.handle(request(group, "POST", "api/pods", utf8(podspec("Foo", "1.0")))),
                HttpStatus.METHOD_NOT_ALLOWED, "repository.readOnly");
        assertThat(storage.assets).isEmpty();
    }

    @Test
    void versionsAreDeletedWithTheirArchives() throws IOException {
        put(hostedRepo, "Specs/Gone/1.0/Gone.podspec.json", podspec("Gone", "1.0"));
        assertThat(handle(hostedRepo, "DELETE", "Specs/Gone/1.0").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertError(handle(hostedRepo, "DELETE", "Specs/Gone/1.0"), HttpStatus.NOT_FOUND, "content.notFound");

        put(hostedRepo, "Specs/Gone/2.0/Gone.podspec.json", podspec("Gone", "2.0"));
        assertError(handle(hostedRepo, "DELETE", "Specs/0/0/0/Gone/2.0/Gone.podspec.json"), HttpStatus.BAD_REQUEST,
                "repository.cocoapods.invalid");
        assertThat(handle(hostedRepo, "DELETE", "Specs/0/b/4/Gone/2.0/Gone.podspec.json").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertError(handle(hostedRepo, "DELETE", "Specs/Gone/2.0/Gone.podspec.json"), HttpStatus.NOT_FOUND, "content.notFound");
        assertThat(body(handle(hostedRepo, "GET", "all_pods.txt"))).isEmpty();
    }

    @Test
    void sourceArchivesAreUploadedServedAndDeleted() throws IOException {
        byte[] archive = zip("Bin.xcframework/Info.plist", "<plist/>");
        RepositoryRequest upload = request(hostedRepo, "PUT", "sources/Bin/1.0/Bin-1.0.zip", archive);
        ResponseEntity<?> stored = handler.handle(upload);
        assertThat(stored.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String url = baseUrl(hostedRepo) + "/sources/Bin/1.0/Bin-1.0.zip";
        assertThat(json(stored)).containsEntry("source", url);
        assertThat(Files.list(dataDir.resolve("tmp/cocoapods"))).isEmpty();

        put(hostedRepo, "Specs/Bin/1.0/Bin.podspec.json", httpPodspec("Bin", "1.0", url));
        ResponseEntity<?> download = handle(hostedRepo, "GET", "sources/Bin/1.0/Bin-1.0.zip");
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment; filename=\"Bin-1.0.zip\"");
        assertThat(storage.downloads).hasSize(1);
        assertThat(handle(hostedRepo, "HEAD", "sources/Bin/1.0/Bin-1.0.zip").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storage.downloads).hasSize(1);

        assertError(handler.handle(request(hostedRepo, "PUT", "sources/Bin/1.0/Bin-1.0.zip", utf8("<html>"))),
                HttpStatus.BAD_REQUEST, "repository.cocoapods.invalid");
        assertThat(handle(hostedRepo, "DELETE", "sources/Bin/1.0/Bin-1.0.zip").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertError(handle(hostedRepo, "DELETE", "sources/Bin/1.0/Bin-1.0.zip"), HttpStatus.NOT_FOUND, "content.notFound");
        assertError(handle(hostedRepo, "GET", "sources/Bin/1.0/Bin-1.0.zip"), HttpStatus.NOT_FOUND, "content.notFound");
    }

    @Test
    void aMultipartPushStoresThePodspecAndItsArchive() throws IOException {
        MockMultipartHttpServletRequest http = new MockMultipartHttpServletRequest();
        http.setMethod("POST");
        http.addFile(new MockMultipartFile("podspec", "Zipped.podspec.json", "application/json",
                utf8(httpPodspec("Zipped", "1.2.0", "https://example.com/Zipped-1.2.0.zip"))));
        http.addFile(new MockMultipartFile("source", "Zipped-1.2.0.zip", "application/zip", zip("a", "b")));
        ResponseEntity<?> response = handler.handle(new RepositoryRequest(hostedRepo, "api/pods", http, user(), baseUrl(hostedRepo)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String source = baseUrl(hostedRepo) + "/sources/Zipped/1.2.0/Zipped-1.2.0.zip";
        assertThat(json(response)).containsEntry("source", source);
        String spec = body(handle(hostedRepo, "GET", "Specs/Zipped/1.2.0/Zipped.podspec.json"));
        assertThat(spec).contains("\"http\": \"" + source + "\"");
        assertThat(handle(hostedRepo, "GET", "sources/Zipped/1.2.0/Zipped-1.2.0.zip").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Files.list(dataDir.resolve("tmp/cocoapods"))).isEmpty();
    }

    @Test
    void theLandingPageExplainsHowToUseTheRepository() throws IOException {
        Repository odd = CocoaPodsTestSupport.repository("pods<b>", RepositoryType.HOSTED);
        ResponseEntity<?> page = handle(odd, "GET", "");
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(body(page)).contains("pods&lt;b&gt;").doesNotContain("pods<b>")
                .contains("pod repo add-cdn granary").contains("Specs/MyPod/1.0.0/MyPod.podspec.json");
        assertThat(body(handle(proxyRepo, "GET", ""))).doesNotContain("curl -X PUT");
    }

    // --- proxy and group -------------------------------------------------------------------

    private StoredContent upstream(String path, String body) {
        StoredContent content = storage.put(proxyRepo, path, body);
        when(proxyService.fetch(proxyRepo, path, FetchOptions.index())).thenReturn(Optional.of(content));
        return content;
    }

    @Test
    void proxiesServeTheUpstreamIndexFilesAsTheyAre() throws IOException {
        assertError(handle(proxyRepo, "GET", "CocoaPods-version.yml"), HttpStatus.NOT_FOUND, "content.notFound");
        upstream("CocoaPods-version.yml", "---\nmin: 1.0.0\nlast: 1.16.2\nprefix_lengths:\n- 1\n- 1\n- 1\n");
        upstream("all_pods_versions_a_7_5.txt", "AFNetworking/4.0.0/4.0.1\n");
        upstream("all_pods.txt", "AFNetworking\nAlamofire\n");
        upstream("deprecated_podspecs.txt", "Specs/0/0/4/AFCategory/0.1.0/AFCategory.podspec.json\n");

        assertThat(body(handle(proxyRepo, "GET", "CocoaPods-version.yml"))).contains("last: 1.16.2");
        assertThat(body(handle(proxyRepo, "GET", "all_pods_versions_a_7_5.txt"))).isEqualTo("AFNetworking/4.0.0/4.0.1\n");
        assertThat(body(handle(proxyRepo, "GET", "all_pods.txt"))).isEqualTo("AFNetworking\nAlamofire\n");
        assertThat(body(handle(proxyRepo, "GET", "deprecated_podspecs.txt"))).contains("AFCategory");
        assertError(handle(proxyRepo, "GET", "all_pods_versions_f_f_f.txt"), HttpStatus.NOT_FOUND, "content.notFound");
    }

    @Test
    void proxiedPodspecsPointHttpSourcesAtTheRepository() throws IOException {
        String layoutPath = ShardLayout.STANDARD.podspecPath("Zipped", "1.2.0");
        assertThat(layoutPath).isEqualTo("Specs/f/e/5/Zipped/1.2.0/Zipped.podspec.json");
        StoredContent content = storage.put(proxyRepo, layoutPath, httpPodspec("Zipped", "1.2.0", "https://example.com/Zipped-1.2.0.zip"));
        when(proxyService.fetch(proxyRepo, layoutPath, FetchOptions.artifact())).thenReturn(Optional.of(content));

        ResponseEntity<?> spec = handle(proxyRepo, "GET", layoutPath);
        assertThat(spec.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(spec)).contains(baseUrl(proxyRepo) + "/sources/Zipped/1.2.0/Zipped-1.2.0.zip");
        // through the group the URL names the group
        assertThat(body(handle(group, "GET", layoutPath))).contains(baseUrl(group) + "/sources/Zipped/1.2.0/Zipped-1.2.0.zip");
        // without the setting the podspec is served as the upstream wrote it
        proxyRepo.setFormatSettings(Map.of(CocoaPodsProxy.REWRITE_SETTING, "false"));
        assertThat(body(handle(proxyRepo, "GET", layoutPath))).contains("https://example.com/Zipped-1.2.0.zip");
    }

    @Test
    void groupsMergeTheirMembersIndexes() throws IOException {
        put(hostedRepo, "Specs/AFNetworking/4.0.2-internal/AFNetworking.podspec.json", podspec("AFNetworking", "4.0.2-internal"));
        upstream("CocoaPods-version.yml", "---\nmin: 1.0.0\nlast: 1.16.2\nprefix_lengths:\n- 1\n- 1\n- 1\n");
        upstream("all_pods_versions_a_7_5.txt", "AFNetworking/4.0.0/4.0.1\n");
        upstream("all_pods.txt", "Alamofire\n");
        upstream("deprecated_podspecs.txt", "Specs/0/0/4/AFCategory/0.1.0/AFCategory.podspec.json\n");

        assertThat(body(handle(group, "GET", "CocoaPods-version.yml"))).contains("prefix_lengths:\n- 1\n- 1\n- 1\n");
        assertThat(body(handle(group, "GET", "all_pods_versions_a_7_5.txt"))).isEqualTo("AFNetworking/4.0.0/4.0.1/4.0.2-internal\n");
        assertThat(body(handle(group, "GET", "all_pods.txt"))).isEqualTo("AFNetworking\nAlamofire\n");
        assertThat(body(handle(group, "GET", "deprecated_podspecs.txt")))
                .isEqualTo("Specs/0/0/4/AFCategory/0.1.0/AFCategory.podspec.json\n");
        assertThat(body(handle(group, "GET", "Specs/a/7/5/AFNetworking/4.0.2-internal/AFNetworking.podspec.json")))
                .contains("4.0.2-internal");
    }

    @Test
    void aGroupWithAnUnreachableMemberAndNothingElseAnswersNotFound() throws IOException {
        // the dispatcher turns this 404 into a 503 when the proxy member is offline
        assertError(handle(group, "GET", "all_pods_versions_a_7_5.txt"), HttpStatus.NOT_FOUND, "content.notFound");
        assertError(handle(group, "GET", "all_pods.txt"), HttpStatus.NOT_FOUND, "content.notFound");
    }
}
