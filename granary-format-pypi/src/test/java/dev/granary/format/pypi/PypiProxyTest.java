package dev.granary.format.pypi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PypiProxyTest {

    private static final String SHA = "8abb2f1d86890a2dfb989f9a77cfd3f28b3a5f0e6e1d4e0f3b2a1c0d9e8f7a6b";
    private static final String BASE = "http://granary.test/repository/pypi-group";

    private final ObjectMapper json = JsonMapper.builder().build();
    private static final String FILES = PypiProxy.DEFAULT_FILES_URL;

    private final ProxyService proxyService = mock(ProxyService.class);
    private final StorageService storage = mock(StorageService.class);
    private final PypiTestSupport.MemoryBlobStore blobs = new PypiTestSupport.MemoryBlobStore();
    private PypiProxy proxy;
    private Repository repo;

    @BeforeEach
    void setUp() {
        proxy = new PypiProxy(proxyService, storage, new SimpleIndex(json), json);
        repo = PypiTestSupport.repository("pypi-proxy", RepositoryType.PROXY);
        repo.setRemoteUrl("https://pypi.org/");
    }

    private StoredContent content(String path, String body, String contentType) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Asset asset = new Asset();
        asset.setPath(path);
        asset.setBlobSha256(blobs.put(bytes));
        asset.setSize(bytes.length);
        asset.setContentType(contentType);
        return new StoredContent(asset, blobs);
    }

    @Test
    void localPathsKeepThePypiLayoutAndMapOtherHostsUnderAMarker() {
        assertThat(PypiProxy.localPath("https://files.pythonhosted.org/packages/d9/5a/e7c3/six-1.16.0-py2.py3-none-any.whl#sha256=x", null, "six", FILES))
                .isEqualTo("packages/d9/5a/e7c3/six-1.16.0-py2.py3-none-any.whl");
        assertThat(PypiProxy.localPath("https://FILES.pythonhosted.org:443/packages/source/s/six/six-1.16.0.tar.gz", null, "six", FILES))
                .isEqualTo("packages/source/s/six/six-1.16.0.tar.gz");
        // a mirror keeps its layout only when it is the configured files host
        assertThat(PypiProxy.localPath("https://mirror.example/pypi/packages/ab/cd/six-1.0.tar.gz?token=1", null, "six", "https://mirror.example/pypi"))
                .isEqualTo("packages/ab/cd/six-1.0.tar.gz");
        assertThat(PypiProxy.localPath("https://mirror.example/pypi/packages/ab/cd/six-1.0.tar.gz?token=1", null, "six", FILES))
                .isEqualTo("packages/-/six/six-1.0.tar.gz");
        // another Granary, Nexus or devpi: their /packages/ is not the files host
        assertThat(PypiProxy.localPath("http://other:8081/repository/pypi-hosted/packages/acme/1.0/acme-1.0.tar.gz", null, "acme", FILES))
                .isEqualTo("packages/-/acme/acme-1.0.tar.gz");
        assertThat(PypiProxy.localPath("http://files.pythonhosted.org/packages/a/b/six-1.0.tar.gz", null, "six", FILES))
                .isEqualTo("packages/-/six/six-1.0.tar.gz");
        assertThat(PypiProxy.localPath("https://devpi.example/root/pypi/+f/abc/six-1.16.0.tar.gz", null, "Six", FILES))
                .isEqualTo("packages/-/six/six-1.16.0.tar.gz");
        assertThat(PypiProxy.localPath("https://files.pythonhosted.org/packages/six-1.16.0.tar.gz", null, "six", FILES))
                .isEqualTo("packages/-/six/six-1.16.0.tar.gz");
        assertThat(PypiProxy.localPath("https://files.pythonhosted.org/packages/a%20b/six-1.16.0.tar.gz", null, "six", FILES))
                .isEqualTo("packages/-/six/six-1.16.0.tar.gz");
        assertThat(PypiProxy.localPath("https://files.pythonhosted.org/packages/../x/six-1.16.0.tar.gz", null, "six", FILES))
                .isEqualTo("packages/-/six/six-1.16.0.tar.gz");
        // the marker path carries the file name the index states, not whatever the URL ends with
        assertThat(PypiProxy.localPath("https://dl.example/download?id=42", "six-1.0.tar.gz", "six", FILES))
                .isEqualTo("packages/-/six/six-1.0.tar.gz");
        assertThat(PypiProxy.localPath("not a url at all#frag", null, "six", FILES)).isEqualTo("packages/-/six/not a url at all");
        assertThat(PypiProxy.localPath("", null, "six", FILES)).isEqualTo("packages/-/six/");
    }

    @Test
    void theRemoteUrlMayBeTheIndexRootOrTheSimpleIndexItself() {
        assertThat(PypiProxy.simpleRoot(repo)).isEqualTo("https://pypi.org/simple/");
        assertThat(PypiProxy.apiRoot(repo)).isEqualTo("https://pypi.org/");
        for (String remote : new String[] {"https://pypi.org/simple", "https://pypi.org/simple/", " https://pypi.org/simple/ "}) {
            repo.setRemoteUrl(remote);
            assertThat(PypiProxy.simpleRoot(repo)).isEqualTo("https://pypi.org/simple/");
            assertThat(PypiProxy.apiRoot(repo)).isEqualTo("https://pypi.org/");
        }
        repo.setRemoteUrl("https://nexus.example/repository/pypi-all/simple/");
        assertThat(PypiProxy.simpleRoot(repo)).isEqualTo("https://nexus.example/repository/pypi-all/simple/");
        assertThat(PypiProxy.apiRoot(repo)).isEqualTo("https://nexus.example/repository/pypi-all/");
        repo.setRemoteUrl("https://devpi.example/root/pypi/+simple/");
        assertThat(PypiProxy.simpleRoot(repo)).isEqualTo("https://devpi.example/root/pypi/+simple/");
        repo.setRemoteUrl("https://mirror.example/pypi");
        assertThat(PypiProxy.simpleRoot(repo)).isEqualTo("https://mirror.example/pypi/simple/");
        assertThat(PypiProxy.apiRoot(repo)).isEqualTo("https://mirror.example/pypi/");

        repo.setRemoteUrl("https://nexus.example/repository/pypi-all/simple");
        when(proxyService.fetch(eq(repo), eq("simple/six/index.json"), eq(URI.create("https://nexus.example/repository/pypi-all/simple/six/")),
                eq(FetchOptions.index()), any())).thenReturn(Optional.empty());
        assertThat(proxy.project(repo, "six", PypiAccept.JSON)).isEmpty();
        verify(proxyService).fetch(eq(repo), eq("simple/six/index.json"), eq(URI.create("https://nexus.example/repository/pypi-all/simple/six/")),
                eq(FetchOptions.index()), any());
        when(proxyService.fetch(eq(repo), eq("pypi/six/json"), any(URI.class), any(), any())).thenReturn(Optional.empty());
        proxy.jsonApi(repo, "pypi/six/json");
        verify(proxyService).fetch(eq(repo), eq("pypi/six/json"), eq(URI.create("https://nexus.example/repository/pypi-all/pypi/six/json")),
                eq(FetchOptions.index()), any());
    }

    @Test
    void cachedDistributionsAreLinkedToTheirComponent() {
        StoredContent wheel = content("packages/a/b/c/Six-1.16.0-py2.py3-none-any.whl", "bytes", "application/octet-stream");
        StoredContent sidecar = content("packages/a/b/c/Six-1.16.0-py2.py3-none-any.whl.metadata", "Name: six", "application/octet-stream");
        StoredContent other = content("packages/a/b/c/README", "text", "text/plain");
        when(proxyService.fetch(eq(repo), any(), any(URI.class), eq(FetchOptions.artifact()), any())).thenAnswer(invocation -> {
            String path = invocation.getArgument(1);
            return Optional.of(path.endsWith(".metadata") ? sidecar : path.endsWith("README") ? other : wheel);
        });
        Component component = mock(Component.class);
        when(component.getId()).thenReturn(UUID.randomUUID());
        when(storage.ensureComponent(eq(repo), any())).thenReturn(component);
        when(storage.save(any(Asset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(proxy.file(repo, "packages/a/b/c/Six-1.16.0-py2.py3-none-any.whl"))
                .hasValueSatisfying(c -> assertThat(c.asset().getComponentId()).isEqualTo(component.getId()));
        assertThat(proxy.file(repo, "packages/a/b/c/Six-1.16.0-py2.py3-none-any.whl.metadata"))
                .hasValueSatisfying(c -> assertThat(c.asset().getComponentId()).isEqualTo(component.getId()));
        ArgumentCaptor<StorageService.ComponentCoordinates> coordinates = ArgumentCaptor.forClass(StorageService.ComponentCoordinates.class);
        verify(storage, org.mockito.Mockito.times(2)).ensureComponent(eq(repo), coordinates.capture());
        assertThat(coordinates.getAllValues()).containsOnly(new StorageService.ComponentCoordinates("", "six", "1.16.0"));

        // linked once, never again; files that are not distributions are left alone
        proxy.file(repo, "packages/a/b/c/Six-1.16.0-py2.py3-none-any.whl");
        assertThat(proxy.file(repo, "packages/a/b/c/README")).hasValueSatisfying(c -> assertThat(c.asset().getComponentId()).isNull());
        verify(storage, org.mockito.Mockito.times(2)).ensureComponent(eq(repo), any());
    }

    @Test
    void aFailedComponentLinkNeverFailsTheDownload() {
        StoredContent wheel = content("packages/a/b/six-1.16.0.tar.gz", "bytes", "application/octet-stream");
        when(proxyService.fetch(eq(repo), any(), any(URI.class), eq(FetchOptions.artifact()), any())).thenReturn(Optional.of(wheel));
        when(storage.ensureComponent(eq(repo), any())).thenThrow(new IllegalStateException("unique constraint"));
        assertThat(proxy.file(repo, "packages/a/b/six-1.16.0.tar.gz")).hasValueSatisfying(c -> {
            assertThat(c.asset().getComponentId()).isNull();
            assertThat(c.size()).isEqualTo(5);
        });
    }

    @Test
    void rewritesEveryLinkToTheRepository() {
        PypiProject page = PypiProject.of("Six", List.of(
                PypiFile.of("six-1.16.0.tar.gz", "https://files.pythonhosted.org/packages/a/b/c/six-1.16.0.tar.gz", SHA),
                PypiFile.of("six-1.0.tar.gz", "https://other.example/dl/six-1.0.tar.gz", "ff")));
        PypiProject rewritten = PypiProxy.rewrite(page, BASE, FILES);
        assertThat(rewritten.files().get(0).url()).isEqualTo(BASE + "/packages/a/b/c/six-1.16.0.tar.gz");
        assertThat(rewritten.files().get(0).sha256()).contains(SHA);
        assertThat(rewritten.files().get(1).url()).isEqualTo(BASE + "/packages/-/six/six-1.0.tar.gz");
        assertThat(rewritten.name()).isEqualTo("Six");
    }

    @Test
    void upstreamFileUrisUseTheFilesHostSetting() {
        assertThat(proxy.upstreamFileUri(repo, "packages/a/b/c/six-1.16.0.tar.gz"))
                .contains(URI.create("https://files.pythonhosted.org/packages/a/b/c/six-1.16.0.tar.gz"));
        assertThat(proxy.upstreamFileUri(repo, "packages/a/b/c/six-1.16.0.tar.gz.metadata"))
                .contains(URI.create("https://files.pythonhosted.org/packages/a/b/c/six-1.16.0.tar.gz.metadata"));
        repo.setFormatSettings(Map.of(PypiProxy.FILES_URL_SETTING, "https://mirror.example/files"));
        assertThat(proxy.upstreamFileUri(repo, "packages/a/b/c/torch-2.0+cpu-cp311-cp311-linux_x86_64.whl"))
                .contains(URI.create("https://mirror.example/files/packages/a/b/c/torch-2.0+cpu-cp311-cp311-linux_x86_64.whl"));
        assertThat(PypiProxy.filesUrl(repo)).isEqualTo("https://mirror.example/files/");
    }

    @Test
    void foreignFilesAreLookedUpInTheCachedProjectPage() {
        String html = "<a href=\"https://devpi.example/+f/abc/six-1.0.tar.gz#sha256=" + SHA + "\">six-1.0.tar.gz</a>";
        when(proxyService.fetch(eq(repo), eq("simple/six/index.json"), any(URI.class), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.empty());
        when(proxyService.fetch(eq(repo), eq("simple/six/index.html"), any(URI.class), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(content("simple/six/index.html", html, "text/html")));
        assertThat(proxy.upstreamFileUri(repo, "packages/-/six/six-1.0.tar.gz")).contains(URI.create("https://devpi.example/+f/abc/six-1.0.tar.gz"));
        assertThat(proxy.upstreamFileUri(repo, "packages/-/six/six-1.0.tar.gz.metadata")).contains(URI.create("https://devpi.example/+f/abc/six-1.0.tar.gz.metadata"));
        assertThat(proxy.upstreamFileUri(repo, "packages/-/six/unknown-1.0.tar.gz")).isEmpty();
        assertThat(proxy.upstreamFileUri(repo, "packages/-/six")).isEmpty();
        assertThat(proxy.upstreamFileUri(repo, "packages/-/six/a/b")).isEmpty();
    }

    @Test
    void fileFetchesGoThroughTheArtifactCache() {
        StoredContent cached = content("packages/a/b/c/six-1.16.0.tar.gz", "bytes", "application/octet-stream");
        when(proxyService.fetch(eq(repo), eq("packages/a/b/c/six-1.16.0.tar.gz"),
                eq(URI.create("https://files.pythonhosted.org/packages/a/b/c/six-1.16.0.tar.gz")), eq(FetchOptions.artifact()), eq(Map.of())))
                .thenReturn(Optional.of(cached));
        assertThat(proxy.file(repo, "packages/a/b/c/six-1.16.0.tar.gz")).contains(cached);
        when(proxyService.fetch(eq(repo), eq("simple/x/index.json"), any(URI.class), any(), any())).thenReturn(Optional.empty());
        when(proxyService.fetch(eq(repo), eq("simple/x/index.html"), any(URI.class), any(), any())).thenReturn(Optional.empty());
        assertThat(proxy.file(repo, "packages/-/x/x-1.0.tar.gz")).isEmpty();
        verify(proxyService, never()).fetch(eq(repo), eq("packages/-/x/x-1.0.tar.gz"), any(URI.class), any(), any());
    }

    @Test
    void projectPagesAreFetchedPerRepresentationWithTheMatchingAccept() {
        String jsonPage = "{\"name\":\"six\",\"files\":[{\"filename\":\"six-1.16.0.tar.gz\",\"url\":\"https://files.pythonhosted.org/packages/a/six-1.16.0.tar.gz\",\"hashes\":{\"sha256\":\"" + SHA + "\"}}]}";
        when(proxyService.fetch(eq(repo), eq("simple/six/index.json"), eq(URI.create("https://pypi.org/simple/six/")), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(content("simple/six/index.json", jsonPage, "application/vnd.pypi.simple.v1+json")));
        PypiProject page = proxy.project(repo, "six", PypiAccept.JSON).orElseThrow();
        assertThat(page.files()).hasSize(1);
        assertThat(page.files().get(0).url()).isEqualTo("https://files.pythonhosted.org/packages/a/six-1.16.0.tar.gz");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(proxyService).fetch(eq(repo), eq("simple/six/index.json"), any(URI.class), eq(FetchOptions.index()), headers.capture());
        assertThat(headers.getValue()).containsEntry("Accept", PypiAccept.UPSTREAM_ACCEPT_JSON);

        String htmlPage = "<a href=\"../../packages/b/six-1.0.tar.gz\">six-1.0.tar.gz</a>";
        when(proxyService.fetch(eq(repo), eq("simple/six/index.html"), eq(URI.create("https://pypi.org/simple/six/")), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(content("simple/six/index.html", htmlPage, "text/html")));
        PypiProject html = proxy.project(repo, "six", PypiAccept.HTML).orElseThrow();
        assertThat(html.files().get(0).url()).isEqualTo("https://pypi.org/packages/b/six-1.0.tar.gz");
        verify(proxyService).fetch(eq(repo), eq("simple/six/index.html"), any(URI.class), eq(FetchOptions.index()), headers.capture());
        assertThat(headers.getValue()).containsEntry("Accept", PypiAccept.UPSTREAM_ACCEPT_HTML);
    }

    @Test
    void anHtmlAnswerToAJsonRequestIsStillUnderstood() {
        String htmlPage = "<a href=\"https://files.pythonhosted.org/packages/b/six-1.0.tar.gz\">six-1.0.tar.gz</a>";
        when(proxyService.fetch(eq(repo), eq("simple/six/index.json"), any(URI.class), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(content("simple/six/index.json", htmlPage, "text/html")));
        assertThat(proxy.project(repo, "six", PypiAccept.JSON)).hasValueSatisfying(p ->
                assertThat(p.files()).extracting(PypiFile::filename).containsExactly("six-1.0.tar.gz"));
    }

    @Test
    void missingAndBrokenPagesAreEmpty() {
        when(proxyService.fetch(eq(repo), any(), any(URI.class), any(), any())).thenReturn(Optional.empty());
        assertThat(proxy.project(repo, "nope", PypiAccept.JSON)).isEmpty();
        assertThat(proxy.projectNames(repo, PypiAccept.HTML)).isEmpty();
        assertThat(proxy.jsonApi(repo, "pypi/nope/json")).isEmpty();
        when(proxyService.fetch(eq(repo), eq("pypi/bad/json"), any(URI.class), any(), any()))
                .thenReturn(Optional.of(content("pypi/bad/json", "<html>", "text/html")));
        assertThat(proxy.jsonApi(repo, "pypi/bad/json")).isEmpty();
        when(proxyService.fetch(eq(repo), eq("pypi/array/json"), any(URI.class), any(), any()))
                .thenReturn(Optional.of(content("pypi/array/json", "[1]", "application/json")));
        assertThat(proxy.jsonApi(repo, "pypi/array/json")).isEmpty();
    }

    @Test
    void projectListAndJsonApiAreCachedUnderTheirOwnPaths() {
        when(proxyService.fetch(eq(repo), eq("simple/index.html"), eq(URI.create("https://pypi.org/simple/")), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(content("simple/index.html", "<a href=\"six/\">six</a><a href=\"/simple/django/\">Django</a>", "text/html")));
        assertThat(proxy.projectNames(repo, PypiAccept.HTML)).contains(List.of("six", "Django"));
        when(proxyService.fetch(eq(repo), eq("simple/index.json"), eq(URI.create("https://pypi.org/simple/")), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(content("simple/index.json", "{\"projects\":[{\"name\":\"six\"}]}", "application/vnd.pypi.simple.v1+json")));
        assertThat(proxy.projectNames(repo, PypiAccept.JSON)).contains(List.of("six"));

        when(proxyService.fetch(eq(repo), eq("pypi/six/json"), eq(URI.create("https://pypi.org/pypi/six/json")), eq(FetchOptions.index()), eq(Map.of("Accept", "application/json"))))
                .thenReturn(Optional.of(content("pypi/six/json", "{\"info\":{\"version\":\"1.16.0\"}}", "application/json")));
        assertThat(proxy.jsonApi(repo, "pypi/six/json")).hasValueSatisfying(doc ->
                assertThat(doc.path("info").path("version").asString()).isEqualTo("1.16.0"));
    }
}
