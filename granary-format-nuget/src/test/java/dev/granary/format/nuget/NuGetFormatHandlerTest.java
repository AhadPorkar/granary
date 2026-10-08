package dev.granary.format.nuget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class NuGetFormatHandlerTest {

    private static final String BASE = "http://granary.test/repository/nuget";
    private static final String UPSTREAM = "https://nuget.example.org";

    private final ObjectMapper json = JsonMapper.builder().build();
    private final StorageService storage = mock(StorageService.class);
    private final ProxyService proxy = mock(ProxyService.class);
    private final UpstreamClient client = mock(UpstreamClient.class);
    private final GroupResolver groups = mock(GroupResolver.class);
    private final InMemoryBlobStore blobs = new InMemoryBlobStore();

    private NuGetFormatHandler handler;
    private Repository hosted;
    private Repository proxied;
    private Repository group;

    @BeforeEach
    void setUp() throws IOException {
        handler = new NuGetFormatHandler(storage, proxy, client, groups, json);
        hosted = repository("nuget-hosted", RepositoryType.HOSTED);
        proxied = repository("nuget-proxy", RepositoryType.PROXY);
        proxied.setRemoteUrl(UPSTREAM + "/v3/index.json");
        group = repository("nuget-all", RepositoryType.GROUP);
        group.setMembers(List.of("nuget-hosted", "nuget-proxy"));
        when(groups.leafMembers(group)).thenReturn(List.of(hosted, proxied));
    }

    private static Repository repository(String name, RepositoryType type) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setFormat(NuGetFormatHandler.FORMAT);
        r.setType(type);
        return r;
    }

    private static Asset asset(String path, String sha, long size) {
        Asset a = new Asset();
        a.setPath(path);
        a.setBlobSha256(sha);
        a.setSize(size);
        return a;
    }

    private StoredContent content(String path, byte[] bytes) {
        return new StoredContent(asset(path, blobs.put(bytes), bytes.length), blobs);
    }

    private RepositoryRequest request(Repository repo, String method, String path) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        return new RepositoryRequest(repo, path, http, GranaryPrincipal.anonymous(Set.of()), BASE);
    }

    private RepositoryRequest request(Repository repo, String method, String path, byte[] body) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        http.setContent(body);
        http.setContentType("application/octet-stream");
        return new RepositoryRequest(repo, path, http, GranaryPrincipal.anonymous(Set.of()), BASE);
    }

    private JsonNode body(ResponseEntity<?> response) {
        return json.readTree((byte[]) response.getBody());
    }

    @Test
    void identifiesItself() {
        assertThat(handler.format()).isEqualTo("nuget");
        assertThat(handler.displayName()).isEqualTo("NuGet");
        assertThat(handler.requiredAccess(request(hosted, "GET", "index.json"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "HEAD", "v3/query"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "PUT", "v2/package"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hosted, "DELETE", "v2/package/a/1.0"))).isEqualTo(AccessLevel.WRITE);
    }

    @Test
    void indexDocumentsAreMetadataPathsAndPackagesAreNot() {
        assertThat(handler.isMetadataPath("index.json")).isTrue();
        assertThat(handler.isMetadataPath("v3/flatcontainer/a/index.json")).isTrue();
        assertThat(handler.isMetadataPath("a/index.json")).isTrue();
        assertThat(handler.isMetadataPath("v3/registration/a/index.json")).isTrue();
        assertThat(handler.isMetadataPath("v3/registration/a/page-1.json")).isTrue();
        assertThat(handler.isMetadataPath("a/1.0.0/a.1.0.0.nupkg")).isFalse();
        assertThat(handler.isMetadataPath("v3/flatcontainer/a/1.0.0/a.nuspec")).isFalse();
        assertThat(handler.isMetadataPath("a/1.0.0/a.1.0.0.snupkg")).isFalse();
        // the empty path is the service index, which is regenerated on every request
        assertThat(handler.isMetadataPath("")).isTrue();
        assertThat(handler.isMetadataPath(null)).isTrue();
    }

    @Test
    void serviceIndexIsGeneratedForEveryRepositoryType() throws IOException {
        for (Repository repo : List.of(hosted, proxied, group)) {
            ResponseEntity<?> response = handler.handle(request(repo, "GET", "index.json"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode index = body(response);
            assertThat(index.path("version").stringValue()).isEqualTo("3.0.0");
            assertThat(json.writeValueAsString(index)).contains(BASE + "/v3/flatcontainer/").contains("PackagePublish/2.0.0");
        }
        assertThat(handler.handle(request(hosted, "GET", "")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void flatIndexListsHostedVersionsSortedAndLowerCased() throws IOException {
        when(storage.list(hosted, "acme.lib/")).thenReturn(List.of(
                asset("acme.lib/2.0.0/acme.lib.2.0.0.nupkg", "a", 1),
                asset("acme.lib/2.0.0/acme.lib.nuspec", "b", 1),
                asset("acme.lib/1.0.0-beta/acme.lib.1.0.0-beta.nupkg", "c", 1),
                asset("acme.lib/1.0.0-beta/acme.lib.1.0.0-beta.snupkg", "d", 1),
                asset("acme.lib/index.json", "e", 1),
                asset("acme.libx/1.0.0/acme.libx.1.0.0.nupkg", "f", 1)));

        ResponseEntity<?> response = handler.handle(request(hosted, "GET", "v3/flatcontainer/Acme.Lib/index.json"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response).path("versions")).extracting(JsonNode::stringValue).containsExactly("1.0.0-beta", "2.0.0");
    }

    @Test
    void unknownPackageAndUnknownPathsAre404() throws IOException {
        when(storage.list(any(), anyString())).thenReturn(List.of());
        assertThat(handler.handle(request(hosted, "GET", "v3/flatcontainer/nope/index.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "v3/registration/nope/index.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "v3/registration/nope/1.0.0.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "v3/flatcontainer/nope/1.0.0/nope.1.0.0.nupkg")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "v3/flatcontainer/nope/not-a-version/nope.nuspec")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "something/else")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void wrongMethodsAre405WithJsonBodies() throws IOException {
        ResponseEntity<?> patch = handler.handle(request(hosted, "PATCH", "index.json"));
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(patch.getBody()).isInstanceOf(Map.class);
        assertThat(handler.handle(request(hosted, "GET", "v2/package")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "PUT", "v3/query")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "DELETE", "v2/package")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void pushAndDeleteAreRefusedOnProxiesAndGroups() throws IOException {
        byte[] nupkg = TestNupkgs.nuspec("Acme.Lib", "1.0.0").nupkg();
        for (Repository repo : List.of(proxied, group)) {
            ResponseEntity<?> push = handler.handle(request(repo, "PUT", "v2/package", nupkg));
            assertThat(push.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(((Map<?, ?>) push.getBody()).get("code")).isEqualTo("repository.readOnly");
            ResponseEntity<?> delete = handler.handle(request(repo, "DELETE", "v2/package/Acme.Lib/1.0.0"));
            assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }
        verify(storage, never()).store(any(), anyString(), any(), any(), any(), eq(false), any());
    }

    @Test
    void rawBodyPushStoresNupkgAndManifestUnderNormalisedCoordinates() throws IOException {
        byte[] nupkg = TestNupkgs.nuspec("Acme.Lib", "1.0.01").description("d").nupkg();
        when(storage.exists(hosted, "acme.lib/1.0.1/acme.lib.1.0.1.nupkg")).thenReturn(false);

        ResponseEntity<?> response = handler.handle(request(hosted, "PUT", "v2/package", nupkg));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<StorageService.ComponentCoordinates> coordinates = ArgumentCaptor.forClass(StorageService.ComponentCoordinates.class);
        verify(storage, org.mockito.Mockito.times(2)).store(eq(hosted), paths.capture(), any(InputStream.class), any(),
                eq(Asset.Origin.UPLOADED), eq(false), coordinates.capture());
        assertThat(paths.getAllValues()).containsExactly("acme.lib/1.0.1/acme.lib.1.0.1.nupkg", "acme.lib/1.0.1/acme.lib.nuspec");
        assertThat(coordinates.getAllValues()).allSatisfy(c -> {
            assertThat(c.namespace()).isEmpty();
            assertThat(c.name()).isEqualTo("Acme.Lib");
            assertThat(c.version()).isEqualTo("1.0.1");
        });
    }

    @Test
    void multipartPushReadsTheFilePartWhateverItsName() throws IOException {
        byte[] nupkg = TestNupkgs.nuspec("Acme.Lib", "2.0.0").nupkg();
        MockMultipartHttpServletRequest http = new MockMultipartHttpServletRequest();
        http.setMethod("PUT");
        http.setRequestURI("/repository/nuget-hosted/v2/package/");
        http.addFile(new MockMultipartFile("package", "Acme.Lib.2.0.0.nupkg", "application/octet-stream", nupkg));
        RepositoryRequest request = new RepositoryRequest(hosted, "v2/package/", http, GranaryPrincipal.anonymous(Set.of()), BASE);

        ResponseEntity<?> response = handler.handle(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(storage).store(eq(hosted), eq("acme.lib/2.0.0/acme.lib.2.0.0.nupkg"), any(InputStream.class), any(),
                eq(Asset.Origin.UPLOADED), eq(false), any());
    }

    @Test
    void pushOfAnExistingVersionIs409UnlessRedeployIsAllowed() throws IOException {
        byte[] nupkg = TestNupkgs.nuspec("Acme.Lib", "1.0.0").nupkg();
        when(storage.exists(hosted, "acme.lib/1.0.0/acme.lib.1.0.0.nupkg")).thenReturn(true);

        ResponseEntity<?> conflict = handler.handle(request(hosted, "PUT", "v2/package", nupkg));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((Map<?, ?>) conflict.getBody()).get("code")).isEqualTo("repository.nuget.versionExists");
        verify(storage, never()).store(any(), anyString(), any(), any(), any(), eq(false), any());

        hosted.setAllowRedeploy(true);
        assertThat(handler.handle(request(hosted, "PUT", "v2/package", nupkg)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void pushOfSomethingThatIsNotAPackageIs400() throws IOException {
        ResponseEntity<?> garbage = handler.handle(request(hosted, "PUT", "v2/package", "nope".getBytes(StandardCharsets.UTF_8)));
        assertThat(garbage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) garbage.getBody()).get("code")).isEqualTo("repository.nuget.invalidPackage");

        byte[] badVersion = TestNupkgs.nuspec("Acme.Lib", "not.a.version").nupkg();
        assertThat(handler.handle(request(hosted, "PUT", "v2/package", badVersion)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        byte[] badId = TestNupkgs.nuspec("Acme Lib!", "1.0.0").nupkg();
        assertThat(handler.handle(request(hosted, "PUT", "v2/package", badId)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(storage, never()).store(any(), anyString(), any(), any(), any(), eq(false), any());
    }

    @Test
    void symbolPackagesAreStoredNextToThePackageAndNeverConflict() throws IOException {
        byte[] snupkg = TestNupkgs.nuspec("Acme.Lib", "1.0.0").packageType("SymbolsPackage").nupkg();
        when(storage.exists(any(), anyString())).thenReturn(true);

        assertThat(handler.handle(request(hosted, "PUT", "v2/package", snupkg)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(handler.handle(request(hosted, "PUT", "v2/symbolpackage", snupkg)).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        verify(storage, org.mockito.Mockito.times(2)).store(eq(hosted), eq("acme.lib/1.0.0/acme.lib.1.0.0.snupkg"),
                any(InputStream.class), any(), eq(Asset.Origin.UPLOADED), eq(false), any());
        verify(storage, never()).store(eq(hosted), eq("acme.lib/1.0.0/acme.lib.1.0.0.nupkg"), any(), any(), any(), eq(false), any());
    }

    @Test
    void registrationIndexAndLeafAreBuiltFromStoredManifests() throws IOException {
        byte[] nuspec = TestNupkgs.nuspec("Acme.Lib", "1.0.0").description("Acme library")
                .dependency("net8.0", "Newtonsoft.Json", "13.0.1").xml().getBytes(StandardCharsets.UTF_8);
        Asset nupkg = asset("acme.lib/1.0.0/acme.lib.1.0.0.nupkg", "p", 10);
        nupkg.setDownloadCount(4);
        when(storage.list(hosted, "acme.lib/")).thenReturn(List.of(nupkg, asset("acme.lib/1.0.0/acme.lib.nuspec", "n", 1)));
        when(storage.find(hosted, "acme.lib/1.0.0/acme.lib.nuspec")).thenReturn(Optional.of(content("acme.lib/1.0.0/acme.lib.nuspec", nuspec)));

        ResponseEntity<?> index = handler.handle(request(hosted, "GET", "v3/registration/acme.lib/index.json"));
        assertThat(index.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode doc = body(index);
        assertThat(doc.path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/acme.lib/index.json");
        JsonNode entry = doc.path("items").get(0).path("items").get(0).path("catalogEntry");
        assertThat(entry.path("id").stringValue()).isEqualTo("Acme.Lib");
        assertThat(entry.path("description").stringValue()).isEqualTo("Acme library");
        assertThat(entry.path("dependencyGroups").get(0).path("dependencies").get(0).path("range").stringValue()).isEqualTo("[13.0.1, )");
        assertThat(entry.path("packageContent").stringValue()).isEqualTo(BASE + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.nupkg");

        ResponseEntity<?> leaf = handler.handle(request(hosted, "GET", "v3/registration/Acme.Lib/1.0.json"));
        assertThat(leaf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(leaf).path("@id").stringValue()).isEqualTo(BASE + "/v3/registration/acme.lib/1.0.0.json");
    }

    @Test
    void unreadableManifestsDegradeToAMinimalCatalogEntry() throws IOException {
        when(storage.list(hosted, "acme.lib/")).thenReturn(List.of(asset("acme.lib/1.0.0/acme.lib.1.0.0.nupkg", "p", 10)));
        when(storage.find(hosted, "acme.lib/1.0.0/acme.lib.nuspec"))
                .thenReturn(Optional.of(content("acme.lib/1.0.0/acme.lib.nuspec", "<broken".getBytes(StandardCharsets.UTF_8))));

        JsonNode doc = body(handler.handle(request(hosted, "GET", "v3/registration/acme.lib/index.json")));
        JsonNode entry = doc.path("items").get(0).path("items").get(0).path("catalogEntry");
        assertThat(entry.path("id").stringValue()).isEqualTo("acme.lib");
        assertThat(entry.path("version").stringValue()).isEqualTo("1.0.0");
    }

    @Test
    void packageDownloadsStreamTheStoredFileAndCountTheDownload() throws IOException {
        StoredContent stored = content("acme.lib/1.0.0/acme.lib.1.0.0.nupkg", new byte[] {1, 2, 3});
        when(storage.find(hosted, "acme.lib/1.0.0/acme.lib.1.0.0.nupkg")).thenReturn(Optional.of(stored));

        ResponseEntity<?> response = handler.handle(request(hosted, "GET", "v3/flatcontainer/Acme.Lib/1.0/acme.lib.1.0.nupkg"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"" + stored.asset().getBlobSha256() + "\"");
        verify(storage).recordDownload(stored.asset());

        ResponseEntity<?> head = handler.handle(request(hosted, "HEAD", "v3/flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.nupkg"));
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(storage, org.mockito.Mockito.times(1)).recordDownload(stored.asset());
    }

    @Test
    void deleteRemovesEveryAssetOfTheVersion() throws IOException {
        when(storage.list(hosted, "acme.lib/1.0.0/")).thenReturn(List.of(
                asset("acme.lib/1.0.0/acme.lib.1.0.0.nupkg", "p", 1),
                asset("acme.lib/1.0.0/acme.lib.nuspec", "n", 1),
                asset("acme.lib/1.0.0-beta/acme.lib.nuspec", "x", 1)));
        when(storage.delete(eq(hosted), anyString())).thenReturn(true);

        ResponseEntity<?> response = handler.handle(request(hosted, "DELETE", "v2/package/Acme.Lib/1.0"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(storage).delete(hosted, "acme.lib/1.0.0/acme.lib.1.0.0.nupkg");
        verify(storage).delete(hosted, "acme.lib/1.0.0/acme.lib.nuspec");
        verify(storage, never()).delete(hosted, "acme.lib/1.0.0-beta/acme.lib.nuspec");

        when(storage.list(hosted, "acme.lib/9.0.0/")).thenReturn(List.of());
        assertThat(handler.handle(request(hosted, "DELETE", "v2/package/acme.lib/9.0.0")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "DELETE", "v2/package/acme.lib/bogus")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void proxyResolvesTheFlatContainerThroughTheUpstreamServiceIndex() throws IOException {
        String serviceIndex = """
                {"version":"3.0.0","resources":[
                  {"@id":"%s/flat","@type":"PackageBaseAddress/3.0.0"},
                  {"@id":"%s/reg/","@type":"RegistrationsBaseUrl/3.6.0"}]}""".formatted(UPSTREAM, UPSTREAM);
        when(proxy.fetch(eq(proxied), eq("index.json"), eq(URI.create(UPSTREAM + "/v3/index.json")), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(content("index.json", serviceIndex.getBytes(StandardCharsets.UTF_8))));
        when(proxy.fetch(eq(proxied), eq("acme.lib/index.json"), eq(URI.create(UPSTREAM + "/flat/acme.lib/index.json")), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(content("acme.lib/index.json", "{\"versions\":[\"2.0.0\",\"1.0.0\",\"1.0.0-beta\"]}".getBytes(StandardCharsets.UTF_8))));
        StoredContent nupkg = content("acme.lib/2.0.0/acme.lib.2.0.0.nupkg", new byte[] {9});
        when(proxy.fetch(eq(proxied), eq("acme.lib/2.0.0/acme.lib.2.0.0.nupkg"),
                eq(URI.create(UPSTREAM + "/flat/acme.lib/2.0.0/acme.lib.2.0.0.nupkg")), eq(FetchOptions.artifact()), any()))
                .thenReturn(Optional.of(nupkg));

        JsonNode versions = body(handler.handle(request(proxied, "GET", "v3/flatcontainer/Acme.Lib/index.json")));
        assertThat(versions.path("versions")).extracting(JsonNode::stringValue).containsExactly("1.0.0-beta", "1.0.0", "2.0.0");

        ResponseEntity<?> download = handler.handle(request(proxied, "GET", "v3/flatcontainer/acme.lib/2.0.0/acme.lib.2.0.0.nupkg"));
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(storage).recordDownload(nupkg.asset());
    }

    @Test
    void proxyInlinesNonInlinedRegistrationPagesAndRewritesThem() throws IOException {
        String serviceIndex = """
                {"resources":[{"@id":"https://api.nuget.org/v3/registration5-gz-semver2/","@type":"RegistrationsBaseUrl/3.6.0"}]}""";
        when(proxy.fetch(eq(proxied), eq("index.json"), any(URI.class), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(content("index.json", serviceIndex.getBytes(StandardCharsets.UTF_8))));
        when(proxy.fetch(eq(proxied), eq("v3/registration/newtonsoft.json/index.json"),
                eq(URI.create("https://api.nuget.org/v3/registration5-gz-semver2/newtonsoft.json/index.json")), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(content("reg-index", RegistrationBuilderTest.upstreamIndex(false).getBytes(StandardCharsets.UTF_8))));
        when(proxy.fetch(eq(proxied), eq("v3/registration/newtonsoft.json/page-1.json"),
                eq(URI.create("https://api.nuget.org/v3/registration5-gz-semver2/newtonsoft.json/page/12.0.3/13.0.1.json")), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(content("reg-page", gzip(RegistrationBuilderTest.upstreamPage()))));

        ResponseEntity<?> response = handler.handle(request(proxied, "GET", "v3/registration/Newtonsoft.Json/index.json"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String text = new String((byte[]) response.getBody(), StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("api.nuget.org");
        JsonNode doc = json.readTree(text);
        assertThat(RegistrationBuilder.leaves(doc)).hasSize(2);
        assertThat(RegistrationBuilder.leaves(doc).get(1).path("packageContent").stringValue())
                .isEqualTo(BASE + "/v3/flatcontainer/newtonsoft.json/13.0.1/newtonsoft.json.13.0.1.nupkg");
    }

    @Test
    void proxySearchIsEmptyWhenOffline() throws IOException {
        when(proxy.isOffline(proxied)).thenReturn(true);
        JsonNode result = body(handler.handle(request(proxied, "GET", "v3/query")));
        assertThat(result.path("totalHits").asInt()).isZero();
        assertThat(result.path("data").size()).isZero();
    }

    @Test
    void groupUnionsTheVersionsOfItsMembersAndServesFilesFromTheFirstThatHasThem() throws IOException {
        when(storage.list(hosted, "acme.lib/")).thenReturn(List.of(asset("acme.lib/1.0.0/acme.lib.1.0.0.nupkg", "h", 1)));
        String serviceIndex = "{\"resources\":[{\"@id\":\"" + UPSTREAM + "/flat/\",\"@type\":\"PackageBaseAddress/3.0.0\"}]}";
        when(proxy.fetch(eq(proxied), eq("index.json"), any(URI.class), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(content("index.json", serviceIndex.getBytes(StandardCharsets.UTF_8))));
        when(proxy.fetch(eq(proxied), eq("acme.lib/index.json"), any(URI.class), eq(FetchOptions.index()), any()))
                .thenReturn(Optional.of(content("acme.lib/index.json", "{\"versions\":[\"1.0.0\",\"2.0.0\"]}".getBytes(StandardCharsets.UTF_8))));
        StoredContent hostedNupkg = content("acme.lib/1.0.0/acme.lib.1.0.0.nupkg", new byte[] {1});
        when(storage.find(hosted, "acme.lib/1.0.0/acme.lib.1.0.0.nupkg")).thenReturn(Optional.of(hostedNupkg));
        StoredContent proxiedNupkg = content("acme.lib/2.0.0/acme.lib.2.0.0.nupkg", new byte[] {2});
        when(proxy.fetch(eq(proxied), eq("acme.lib/2.0.0/acme.lib.2.0.0.nupkg"), any(URI.class), eq(FetchOptions.artifact()), any()))
                .thenReturn(Optional.of(proxiedNupkg));

        JsonNode versions = body(handler.handle(request(group, "GET", "v3/flatcontainer/acme.lib/index.json")));
        assertThat(versions.path("versions")).extracting(JsonNode::stringValue).containsExactly("1.0.0", "2.0.0");

        handler.handle(request(group, "GET", "v3/flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.nupkg"));
        verify(storage).recordDownload(hostedNupkg.asset());
        handler.handle(request(group, "GET", "v3/flatcontainer/acme.lib/2.0.0/acme.lib.2.0.0.nupkg"));
        verify(storage).recordDownload(proxiedNupkg.asset());
        verify(proxy, never()).fetch(eq(proxied), eq("acme.lib/1.0.0/acme.lib.1.0.0.nupkg"), any(URI.class), any(), any());
    }

    private static byte[] gzip(String text) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
