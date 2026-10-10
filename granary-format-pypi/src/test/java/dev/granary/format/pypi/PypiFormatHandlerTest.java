package dev.granary.format.pypi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.GranaryException;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.net.URI;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PypiFormatHandlerTest {

    private static final String SERVER = "http://granary.test";
    private static final String METADATA = PypiTestSupport.metadata("acme-demo", "1.0.0", ">=3.8", "Demo");
    private static final byte[] WHEEL = PypiTestSupport.wheel("acme-demo", "1.0.0", METADATA);
    private static final String WHEEL_NAME = "acme_demo-1.0.0-py3-none-any.whl";
    private static final String WHEEL_PATH = "packages/acme-demo/1.0.0/" + WHEEL_NAME;
    private static final String PIP_ACCEPT = "application/vnd.pypi.simple.v1+json, application/vnd.pypi.simple.v1+html; q=0.1, text/html; q=0.01";

    @TempDir
    Path dataDir;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final PypiTestSupport.FakeStorage storage = new PypiTestSupport.FakeStorage();
    private final ProxyService proxyService = mock(ProxyService.class);
    private final GroupResolver groups = mock(GroupResolver.class);
    private PypiFormatHandler handler;
    private Repository hosted;
    private Repository proxied;
    private Repository group;

    @BeforeEach
    void setUp() {
        GranaryProperties properties = new GranaryProperties();
        properties.setDataDir(dataDir);
        SimpleIndex index = new SimpleIndex(json);
        PypiProxy proxy = new PypiProxy(proxyService, storage.mock, index, json);
        handler = new PypiFormatHandler(storage.mock, new PypiHosted(storage.mock), proxy, groups, index, json, properties);
        hosted = PypiTestSupport.repository("pypi-hosted", RepositoryType.HOSTED);
        proxied = PypiTestSupport.repository("pypi-proxy", RepositoryType.PROXY);
        proxied.setRemoteUrl("https://pypi.org/");
        group = PypiTestSupport.repository("pypi-group", RepositoryType.GROUP);
        group.setMembers(List.of("pypi-hosted", "pypi-proxy"));
        when(groups.leafMembers(group)).thenReturn(List.of(hosted, proxied));
        when(proxyService.fetch(any(), any(), any(URI.class), any(), any())).thenReturn(Optional.empty());
    }

    private static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    private RepositoryRequest request(Repository repo, String method, String path, String accept) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        if (accept != null) {
            http.addHeader("Accept", accept);
        }
        return new RepositoryRequest(repo, path, http, GranaryPrincipal.anonymous(Set.of()), baseUrl(repo));
    }

    private RepositoryRequest request(Repository repo, String method, String path) {
        return request(repo, method, path, null);
    }

    private ResponseEntity<?> upload(Repository repo, String path, String name, String version, String filetype, String filename, byte[] bytes)
            throws IOException {
        MockMultipartHttpServletRequest http = PypiUploadTest.twineRequest(name, version, filetype, filename, bytes);
        http.setRequestURI("/repository/" + repo.getName() + "/" + path);
        return handler.handle(new RepositoryRequest(repo, path, http, GranaryPrincipal.anonymous(Set.of()), baseUrl(repo)));
    }

    private static String body(ResponseEntity<?> response) throws IOException {
        Object body = response.getBody();
        if (body instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (body instanceof Resource resource) {
            return new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
        }
        return String.valueOf(body);
    }

    private static byte[] bytes(ResponseEntity<?> response) throws IOException {
        Object body = response.getBody();
        return body instanceof Resource resource ? resource.getContentAsByteArray() : (byte[]) body;
    }

    private StoredContent upstream(String path, String text, String contentType) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Asset asset = new Asset();
        asset.setPath(path);
        asset.setBlobSha256(storage.blobs.put(bytes));
        asset.setSize(bytes.length);
        asset.setContentType(contentType);
        return new StoredContent(asset, storage.blobs);
    }

    // --- identity ----------------------------------------------------------------------------------

    @Test
    void identifiesItselfAndItsTemplates() {
        assertThat(handler.format()).isEqualTo("pypi");
        assertThat(handler.displayName()).isEqualTo("PyPI");
        assertThat(handler.templates()).extracting(RepositoryTemplate::name).containsExactly("pypi-proxy", "pypi-hosted", "pypi-group");
        assertThat(handler.templates().get(0).remoteUrl()).isEqualTo("https://pypi.org/");
        assertThat(handler.templates().get(2).members()).containsExactly("pypi-hosted", "pypi-proxy");
        assertThat(handler.templates()).allSatisfy(t -> assertThat(t.format()).isEqualTo("pypi"));
        assertThat(handler.settings()).extracting(s -> s.key()).containsExactly("pypi.filesUrl");
        assertThat(handler.settings().get(0).defaultValue()).isEqualTo("https://files.pythonhosted.org/");
        assertThat(handler.settings().get(0).appliesTo()).containsExactly(RepositoryType.PROXY);
        assertThat(handler.connectSnippets(new ConnectContext(hosted, baseUrl(hosted), SERVER, "alice", "<api-token>", true)))
                .extracting(s -> s.id()).contains("pip-conf", "twine-pypirc");
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder(RepositoryType.values());
    }

    @Test
    void accessLevels() {
        assertThat(handler.requiredAccess(request(hosted, "GET", "simple/six/"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "HEAD", WHEEL_PATH))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "POST", ""))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hosted, "POST", "legacy/"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hosted, "DELETE", WHEEL_PATH))).isEqualTo(AccessLevel.WRITE);
    }

    @Test
    void metadataPaths() {
        assertThat(handler.isMetadataPath("")).isTrue();
        assertThat(handler.isMetadataPath("simple/")).isTrue();
        assertThat(handler.isMetadataPath("simple/six/")).isTrue();
        assertThat(handler.isMetadataPath("simple/six/index.json")).isTrue();
        assertThat(handler.isMetadataPath("pypi/six/json")).isTrue();
        assertThat(handler.isMetadataPath(WHEEL_PATH)).isFalse();
        assertThat(handler.isMetadataPath(WHEEL_PATH + ".metadata")).isFalse();
        assertThat(handler.isMetadataPath("packages/a/b/c/six-1.0.tar.gz")).isFalse();
    }

    @Test
    void validatesTheFilesUrlSetting() {
        handler.validate(proxied);
        proxied.setFormatSettings(Map.of("pypi.filesUrl", "https://mirror.example/files/"));
        handler.validate(proxied);
        for (String bad : List.of("ftp://x", "not a url", "/relative", "https://")) {
            proxied.setFormatSettings(Map.of("pypi.filesUrl", bad));
            assertThatThrownBy(() -> handler.validate(proxied)).as(bad).isInstanceOf(GranaryException.class)
                    .hasMessageContaining("pypi.filesUrl");
        }
    }

    // --- hosted ------------------------------------------------------------------------------------

    @Test
    void uploadThenBrowseAndDownload() throws IOException {
        ResponseEntity<?> response = upload(hosted, "", "acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response)).contains(WHEEL_NAME);
        assertThat(storage.paths(hosted)).containsExactly(WHEEL_PATH, WHEEL_PATH + ".metadata");
        assertThat(storage.bytes(hosted, WHEEL_PATH)).isEqualTo(WHEEL);
        assertThat(new String(storage.bytes(hosted, WHEEL_PATH + ".metadata"), StandardCharsets.UTF_8)).isEqualTo(METADATA);
        assertThat(storage.coordinates.values()).allSatisfy(c -> {
            assertThat(c.name()).isEqualTo("acme-demo");
            assertThat(c.version()).isEqualTo("1.0.0");
        });
        assertThat(Files.list(dataDir.resolve("tmp").resolve("pypi")).toList()).as("spool file removed").isEmpty();

        ResponseEntity<?> html = handler.handle(request(hosted, "GET", "simple/acme-demo/"));
        assertThat(html.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(html.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(html.getHeaders().getETag()).isNotNull();
        String page = body(html);
        assertThat(page).contains("<a href=\"" + baseUrl(hosted) + "/" + WHEEL_PATH + "#sha256=" + PypiTestSupport.sha256(WHEEL) + "\"");
        assertThat(page).contains("data-requires-python=\"&gt;=3.8\"");
        assertThat(page).contains("data-core-metadata=\"sha256=" + PypiTestSupport.sha256(METADATA.getBytes(StandardCharsets.UTF_8)) + "\"");
        assertThat(page).contains(">" + WHEEL_NAME + "</a>");

        ResponseEntity<?> jsonPage = handler.handle(request(hosted, "GET", "simple/acme-demo/", PIP_ACCEPT));
        assertThat(jsonPage.getHeaders().getContentType().toString()).isEqualTo("application/vnd.pypi.simple.v1+json");
        JsonNode doc = json.readTree(bytes(jsonPage));
        assertThat(doc.path("name").asString()).isEqualTo("acme-demo");
        assertThat(doc.path("files").get(0).path("url").asString()).isEqualTo(baseUrl(hosted) + "/" + WHEEL_PATH);
        assertThat(doc.path("files").get(0).path("size").longValue()).isEqualTo(WHEEL.length);
        assertThat(doc.path("files").get(0).path("upload-time").asString()).isNotBlank();
        assertThat(doc.path("versions")).extracting(JsonNode::asString).containsExactly("1.0.0");

        ResponseEntity<?> list = handler.handle(request(hosted, "GET", "simple/"));
        assertThat(body(list)).contains("<a href=\"acme-demo/\">acme-demo</a>");

        ResponseEntity<?> file = handler.handle(request(hosted, "GET", WHEEL_PATH));
        assertThat(file.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bytes(file)).isEqualTo(WHEEL);
        assertThat(file.getHeaders().getFirst("Content-Disposition")).isEqualTo("attachment; filename=\"" + WHEEL_NAME + "\"");
        assertThat(file.getHeaders().getETag()).isEqualTo("\"" + PypiTestSupport.sha256(WHEEL) + "\"");
        assertThat(storage.downloads).hasSize(1);

        ResponseEntity<?> head = handler.handle(request(hosted, "HEAD", WHEEL_PATH));
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(storage.downloads).hasSize(1);

        ResponseEntity<?> sidecar = handler.handle(request(hosted, "GET", WHEEL_PATH + ".metadata"));
        assertThat(sidecar.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(sidecar)).isEqualTo(METADATA);

        ResponseEntity<?> api = handler.handle(request(hosted, "GET", "pypi/acme-demo/json"));
        assertThat(api.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(bytes(api)).path("info").path("version").asString()).isEqualTo("1.0.0");
        ResponseEntity<?> release = handler.handle(request(hosted, "GET", "pypi/acme-demo/1.0.0/json"));
        assertThat(json.readTree(bytes(release)).path("urls")).hasSize(1);
        assertThat(handler.handle(request(hosted, "GET", "pypi/acme-demo/9.9/json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void uploadsToTheLegacyUrlAndSdistsWithoutFormMetadataWork() throws IOException {
        byte[] sdist = PypiTestSupport.sdist("acme_demo", "1.0.0", METADATA);
        ResponseEntity<?> response = upload(hosted, "legacy/", "acme-demo", "1.0.0", "sdist", "acme_demo-1.0.0.tar.gz", sdist);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(storage.bytes(hosted, "packages/acme-demo/1.0.0/acme_demo-1.0.0.tar.gz.metadata"), StandardCharsets.UTF_8))
                .isEqualTo(METADATA);

        // an sdist whose archive has no PKG-INFO gets its sidecar from the form fields
        byte[] bare = PypiTestSupport.targz(Map.of("acme_demo-2.0.0/setup.py", new byte[0]));
        assertThat(upload(hosted, "", "acme-demo", "2.0.0", "sdist", "acme_demo-2.0.0.tar.gz", bare).getStatusCode()).isEqualTo(HttpStatus.OK);
        String sidecar = new String(storage.bytes(hosted, "packages/acme-demo/2.0.0/acme_demo-2.0.0.tar.gz.metadata"), StandardCharsets.UTF_8);
        assertThat(sidecar).contains("Name: acme-demo\nVersion: 2.0.0\n").contains("Requires-Python: >=3.8\n")
                .contains("Classifier: Programming Language :: Python :: 3\nClassifier: License :: OSI Approved :: MIT License\n");

        // so does one that is not even an archive
        byte[] junk = "not a tarball".getBytes(StandardCharsets.UTF_8);
        assertThat(upload(hosted, "", "acme-demo", "3.0.0", "sdist", "acme_demo-3.0.0.tar.gz", junk).getStatusCode()).isEqualTo(HttpStatus.OK);
        // but a wheel that is not a zip is refused
        ResponseEntity<?> badWheel = upload(hosted, "", "acme-demo", "4.0.0", "bdist_wheel", "acme_demo-4.0.0-py3-none-any.whl", junk);
        assertThat(badWheel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(badWheel)).contains("not a valid wheel");
        assertThat(storage.paths(hosted)).noneMatch(p -> p.contains("4.0.0"));

        // and so is an archive whose METADATA belongs to another project or release than the form and file name claim
        ResponseEntity<?> wrongVersion = upload(hosted, "", "acme-demo", "5.0.0", "bdist_wheel", "acme_demo-5.0.0-py3-none-any.whl",
                PypiTestSupport.wheel("acme-demo", "5.0.0", METADATA));
        assertThat(wrongVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(wrongVersion)).contains("is for version 1.0.0, not 5.0.0");
        ResponseEntity<?> wrongName = upload(hosted, "", "other", "1.0.0", "bdist_wheel", "other-1.0.0-py3-none-any.whl",
                PypiTestSupport.wheel("other", "1.0.0", METADATA));
        assertThat(wrongName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(wrongName)).contains("names project 'acme-demo', not 'other'");
        assertThat(storage.paths(hosted)).noneMatch(p -> p.contains("5.0.0") || p.contains("other"));
    }

    @Test
    void duplicateFilesAreRefusedUnlessRedeployIsAllowed() throws IOException {
        upload(hosted, "", "acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
        byte[] other = PypiTestSupport.wheel("acme-demo", "1.0.0", METADATA + "Keywords: changed\n");
        ResponseEntity<?> again = upload(hosted, "", "acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, other);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(again.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(body(again)).startsWith("File already exists").contains(baseUrl(hosted) + "/simple/acme-demo/");
        assertThat(storage.bytes(hosted, WHEEL_PATH)).isEqualTo(WHEEL);

        hosted.setAllowRedeploy(true);
        assertThat(upload(hosted, "", "acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, other).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storage.bytes(hosted, WHEEL_PATH)).isEqualTo(other);
    }

    @Test
    void aFileNameIsUniqueAcrossVersionSpellingsAndEqualVersionsShareTheirRelease() throws IOException {
        upload(hosted, "", "acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
        // the sdist says 1.0, which is the same release as 1.0.0 under PEP 440
        byte[] sdist = PypiTestSupport.sdist("acme_demo", "1.0", PypiTestSupport.metadata("acme-demo", "1.0", ">=3.8", "Demo"));
        assertThat(upload(hosted, "", "acme-demo", "1.0", "sdist", "acme_demo-1.0.tar.gz", sdist).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storage.paths(hosted)).contains("packages/acme-demo/1.0.0/acme_demo-1.0.tar.gz")
                .noneMatch(path -> path.startsWith("packages/acme-demo/1.0/"));
        assertThat(storage.coordinates.get(hosted.getId() + "|packages/acme-demo/1.0.0/acme_demo-1.0.tar.gz"))
                .isEqualTo(new dev.granary.core.storage.StorageService.ComponentCoordinates("", "acme-demo", "1.0.0"));

        // the same file name under another spelling of the version is still the same file
        ResponseEntity<?> again = upload(hosted, "", "acme-demo", "1.0.0", "sdist", "acme_demo-1.0.tar.gz", sdist);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(again)).startsWith("File already exists (acme_demo-1.0.tar.gz)");
        hosted.setAllowRedeploy(true);
        assertThat(upload(hosted, "", "acme-demo", "1.0.0", "sdist", "acme_demo-1.0.tar.gz", sdist).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storage.paths(hosted)).filteredOn(path -> path.endsWith("/acme_demo-1.0.tar.gz")).hasSize(1);

        // one page entry per file, and a delete with either spelling removes the whole release
        assertThat(body(handler.handle(request(hosted, "GET", "simple/acme-demo/", PIP_ACCEPT))))
                .containsOnlyOnce("\"acme_demo-1.0.tar.gz\"").containsOnlyOnce("\"" + WHEEL_NAME + "\"");
        assertThat(handler.handle(request(hosted, "DELETE", "packages/acme-demo/1.0")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storage.paths(hosted)).isEmpty();
    }

    @Test
    void uploadErrorsArePlainTextForTwine() throws IOException {
        MockMultipartHttpServletRequest http = PypiUploadTest.twineRequest("acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
        http.removeParameter("sha256_digest");
        http.addParameter("sha256_digest", "bad");
        ResponseEntity<?> response = handler.handle(new RepositoryRequest(hosted, "", http, GranaryPrincipal.anonymous(Set.of()), baseUrl(hosted)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(body(response)).contains("sha256_digest");
        assertThat(storage.paths(hosted)).isEmpty();

        ResponseEntity<?> get = handler.handle(request(hosted, "GET", "legacy/"));
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body(get)).contains("POST");
    }

    @Test
    void uploadsToProxiesAndGroupsAreRefusedAndNameTheHostedMember() throws IOException {
        ResponseEntity<?> toProxy = upload(proxied, "", "acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
        assertThat(toProxy.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body(toProxy)).contains("'pypi-proxy' is a proxy repository");
        ResponseEntity<?> toGroup = upload(group, "legacy/", "acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
        assertThat(toGroup.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body(toGroup)).contains("group repository").contains("hosted member 'pypi-hosted'");
        ResponseEntity<?> delete = handler.handle(request(group, "DELETE", WHEEL_PATH));
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body(delete)).contains("repository.readOnly");
    }

    @Test
    void deletesFilesAndVersions() throws IOException {
        upload(hosted, "", "acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
        upload(hosted, "", "acme-demo", "1.0.0", "sdist", "acme_demo-1.0.0.tar.gz", PypiTestSupport.sdist("acme_demo", "1.0.0", METADATA));
        upload(hosted, "", "acme-demo", "1.1.0", "sdist", "acme_demo-1.1.0.tar.gz", PypiTestSupport.sdist("acme_demo", "1.1.0", PypiTestSupport.metadata("acme-demo", "1.1.0", ">=3.8", "Demo")));
        assertThat(handler.handle(request(hosted, "DELETE", WHEEL_PATH)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storage.paths(hosted)).doesNotContain(WHEEL_PATH, WHEEL_PATH + ".metadata")
                .contains("packages/acme-demo/1.0.0/acme_demo-1.0.0.tar.gz");
        assertThat(handler.handle(request(hosted, "DELETE", WHEEL_PATH)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "DELETE", "packages/Acme.Demo/1.0.0")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storage.paths(hosted)).containsExactly("packages/acme-demo/1.1.0/acme_demo-1.1.0.tar.gz",
                "packages/acme-demo/1.1.0/acme_demo-1.1.0.tar.gz.metadata");
        assertThat(handler.handle(request(hosted, "DELETE", "packages/acme-demo/1.0.0")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "packages/acme-demo/1.1.0")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(handler.handle(request(hosted, "GET", "simple/acme-demo/")))).doesNotContain("1.0.0").contains("1.1.0");
    }

    @Test
    void redirectsNonCanonicalSimpleUrls() throws IOException {
        for (String path : List.of("simple/Acme.Demo", "simple/acme_demo/", "simple/ACME-DEMO", "simple/acme-demo/index.html")) {
            ResponseEntity<?> response = handler.handle(request(hosted, "GET", path));
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
            assertThat(response.getHeaders().getFirst("Location")).as(path).isEqualTo(baseUrl(hosted) + "/simple/acme-demo/");
        }
        ResponseEntity<?> index = handler.handle(request(hosted, "GET", "simple"));
        assertThat(index.getStatusCode()).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
        assertThat(index.getHeaders().getFirst("Location")).isEqualTo(baseUrl(hosted) + "/simple/");
    }

    @Test
    void missingThingsAre404AndOddPathsAre400() throws IOException {
        assertThat(handler.handle(request(hosted, "GET", "simple/nothing/")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "packages/a/b/c-1.0.tar.gz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "pypi/nothing/json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "robots.txt")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "simple/../x/")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handle(request(hosted, "GET", "packages/a/b/.hidden")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handle(request(hosted, "PUT", "simple/six/")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "PATCH", "")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "POST", WHEEL_PATH)).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "GET", "simple/")).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<?> landing = handler.handle(request(hosted, "GET", ""));
        assertThat(landing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(landing)).contains("simple/").contains("twine upload");
    }

    @Test
    void conditionalRequestsOnIndexPages() throws IOException {
        upload(hosted, "", "acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
        ResponseEntity<?> first = handler.handle(request(hosted, "GET", "simple/acme-demo/"));
        String etag = first.getHeaders().getETag();
        MockHttpServletRequest http = new MockHttpServletRequest("GET", "/repository/pypi-hosted/simple/acme-demo/");
        http.addHeader("If-None-Match", etag);
        ResponseEntity<?> conditional = handler.handle(new RepositoryRequest(hosted, "simple/acme-demo/", http, GranaryPrincipal.anonymous(Set.of()), baseUrl(hosted)));
        assertThat(conditional.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        ResponseEntity<?> head = handler.handle(request(hosted, "HEAD", "simple/acme-demo/"));
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getETag()).isEqualTo(etag);
    }

    // --- proxy and group ---------------------------------------------------------------------------

    private void stubUpstreamProject() {
        String page = "{\"meta\":{\"api-version\":\"1.1\"},\"name\":\"acme-demo\",\"files\":["
                + "{\"filename\":\"acme_demo-0.9.0.tar.gz\",\"url\":\"https://files.pythonhosted.org/packages/a/b/c/acme_demo-0.9.0.tar.gz\",\"hashes\":{\"sha256\":\"09\"},\"yanked\":\"old\"},"
                + "{\"filename\":\"" + WHEEL_NAME + "\",\"url\":\"https://files.pythonhosted.org/packages/d/e/f/" + WHEEL_NAME + "\",\"hashes\":{\"sha256\":\"ff\"}}],"
                + "\"versions\":[\"0.9.0\",\"1.0.0\"]}";
        when(proxyService.fetch(eq(proxied), eq("simple/acme-demo/index.json"), eq(URI.create("https://pypi.org/simple/acme-demo/")), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(upstream("simple/acme-demo/index.json", page, "application/vnd.pypi.simple.v1+json")));
        String html = "<a href=\"https://files.pythonhosted.org/packages/a/b/c/acme_demo-0.9.0.tar.gz#sha256=09\" data-yanked=\"old\">acme_demo-0.9.0.tar.gz</a>";
        when(proxyService.fetch(eq(proxied), eq("simple/acme-demo/index.html"), eq(URI.create("https://pypi.org/simple/acme-demo/")), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(upstream("simple/acme-demo/index.html", html, "text/html")));
    }

    @Test
    void proxyRewritesLinksAndFetchesFilesFromTheFilesHost() throws IOException {
        stubUpstreamProject();
        ResponseEntity<?> page = handler.handle(request(proxied, "GET", "simple/acme-demo/", PIP_ACCEPT));
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode doc = json.readTree(bytes(page));
        assertThat(doc.path("files").get(0).path("url").asString()).isEqualTo(baseUrl(proxied) + "/packages/a/b/c/acme_demo-0.9.0.tar.gz");
        assertThat(doc.path("files").get(0).path("yanked").asString()).isEqualTo("old");
        assertThat(doc.path("files").get(1).path("hashes").path("sha256").asString()).isEqualTo("ff");
        assertThat(body(page)).doesNotContain("files.pythonhosted.org");

        StoredContent cached = upstream("packages/d/e/f/" + WHEEL_NAME, "wheel-bytes", "application/octet-stream");
        when(proxyService.fetch(eq(proxied), eq("packages/d/e/f/" + WHEEL_NAME),
                eq(URI.create("https://files.pythonhosted.org/packages/d/e/f/" + WHEEL_NAME)), eq(FetchOptions.artifact()), eq(Map.of())))
                .thenReturn(Optional.of(cached));
        ResponseEntity<?> file = handler.handle(request(proxied, "GET", "packages/d/e/f/" + WHEEL_NAME));
        assertThat(file.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(file)).isEqualTo("wheel-bytes");
        assertThat(handler.handle(request(proxied, "GET", "packages/d/e/f/missing-1.0.tar.gz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(proxied, "GET", "simple/unknown/")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(proxied, "GET", "simple/")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void groupMergesHostedAndProxiedPages() throws IOException {
        stubUpstreamProject();
        upload(hosted, "", "acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
        upload(hosted, "", "acme-demo", "1.1.0", "sdist", "acme_demo-1.1.0.tar.gz", PypiTestSupport.sdist("acme_demo", "1.1.0", PypiTestSupport.metadata("acme-demo", "1.1.0", ">=3.8", "Demo")));

        ResponseEntity<?> page = handler.handle(request(group, "GET", "simple/acme-demo/", PIP_ACCEPT));
        JsonNode doc = json.readTree(bytes(page));
        assertThat(doc.path("files")).extracting(f -> f.path("filename").asString())
                .containsExactly(WHEEL_NAME, "acme_demo-1.1.0.tar.gz", "acme_demo-0.9.0.tar.gz");
        // the hosted copy of the shared file name wins, and every link points at the group
        assertThat(doc.path("files").get(0).path("hashes").path("sha256").asString()).isEqualTo(PypiTestSupport.sha256(WHEEL));
        assertThat(doc.path("files").get(0).path("url").asString()).isEqualTo(baseUrl(group) + "/" + WHEEL_PATH);
        assertThat(doc.path("files").get(2).path("url").asString()).isEqualTo(baseUrl(group) + "/packages/a/b/c/acme_demo-0.9.0.tar.gz");
        assertThat(doc.path("files").get(2).path("yanked").asString()).isEqualTo("old");
        assertThat(doc.path("versions")).extracting(JsonNode::asString).containsExactly("0.9.0", "1.0.0", "1.1.0");

        String html = body(handler.handle(request(group, "GET", "simple/acme-demo/")));
        assertThat(html).contains(baseUrl(group) + "/" + WHEEL_PATH + "#sha256=").contains("data-yanked=\"old\"");

        ResponseEntity<?> hostedFile = handler.handle(request(group, "GET", WHEEL_PATH));
        assertThat(bytes(hostedFile)).isEqualTo(WHEEL);

        // the project list of the group is the union; the proxy contributes nothing when its root is unavailable
        ResponseEntity<?> list = handler.handle(request(group, "GET", "simple/"));
        assertThat(body(list)).contains("acme-demo/");
        when(proxyService.fetch(eq(proxied), eq("simple/index.html"), any(URI.class), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(upstream("simple/index.html", "<a href=\"six/\">six</a><a href=\"acme-demo/\">Acme-Demo</a>", "text/html")));
        assertThat(body(handler.handle(request(group, "GET", "simple/")))).contains("acme-demo/").contains("six/").doesNotContain("Acme-Demo");

        ResponseEntity<?> api = handler.handle(request(group, "GET", "pypi/acme-demo/json"));
        assertThat(json.readTree(bytes(api)).path("info").path("version").asString()).isEqualTo("1.1.0");
    }

    @Test
    void groupWithoutAnyMemberContentIs404() throws IOException {
        assertThat(handler.handle(request(group, "GET", "simple/acme-demo/")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(group, "GET", WHEEL_PATH)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(group, "GET", "pypi/acme-demo/json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(group, "GET", "pypi/acme-demo/1.0.0/json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // a group of only hosted members still lists them
        ResponseEntity<?> list = handler.handle(request(group, "GET", "simple/"));
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void proxyJsonApiIsRewritten() throws IOException {
        String doc = "{\"info\":{\"name\":\"six\",\"version\":\"1.17.0\"},\"releases\":{\"1.17.0\":[{\"filename\":\"six-1.17.0.tar.gz\",\"url\":\"https://files.pythonhosted.org/packages/x/y/six-1.17.0.tar.gz\"}]},"
                + "\"urls\":[{\"filename\":\"six-1.17.0.tar.gz\",\"url\":\"https://files.pythonhosted.org/packages/x/y/six-1.17.0.tar.gz\"}]}";
        when(proxyService.fetch(eq(proxied), eq("pypi/six/json"), eq(URI.create("https://pypi.org/pypi/six/json")), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(upstream("pypi/six/json", doc, "application/json")));
        when(proxyService.fetch(eq(proxied), eq("pypi/six/1.17.0/json"), eq(URI.create("https://pypi.org/pypi/six/1.17.0/json")), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(upstream("pypi/six/1.17.0/json", doc, "application/json")));
        for (Repository repo : List.of(proxied, group)) {
            ResponseEntity<?> response = handler.handle(request(repo, "GET", "pypi/six/json"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json.readTree(bytes(response)).path("urls").get(0).path("url").asString())
                    .isEqualTo(baseUrl(repo) + "/packages/x/y/six-1.17.0.tar.gz");
            ResponseEntity<?> release = handler.handle(request(repo, "GET", "pypi/six/1.17.0/json"));
            assertThat(json.readTree(bytes(release)).path("urls").get(0).path("url").asString())
                    .isEqualTo(baseUrl(repo) + "/packages/x/y/six-1.17.0.tar.gz");
        }
    }
}
