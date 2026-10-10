package dev.granary.format.go;

import static dev.granary.format.go.GoTestSupport.MAPPER;
import static dev.granary.format.go.GoTestSupport.hosted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.format.ContentResolver;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoModulesTest {

    private static final String MODULE = "example.com/acme/hello";

    private final FakeStorage storage = new FakeStorage();
    private final ProxyService proxy = mock(ProxyService.class);
    private final GroupResolver groups = mock(GroupResolver.class);
    private final ContentResolver resolver = new ContentResolver(storage.mock, proxy, groups);
    private final GoModules modules = new GoModules(storage.mock, proxy, groups, resolver, MAPPER);

    private final Repository hostedRepo = hosted("go-hosted");
    private final Repository proxyRepo = GoTestSupport.proxy("go-proxy", "https://proxy.golang.org/");
    private final Repository groupRepo = GoTestSupport.group("go-group", "go-hosted", "go-proxy");

    @BeforeEach
    void wireGroup() {
        when(groups.leafMembers(groupRepo)).thenReturn(List.of(hostedRepo, proxyRepo));
        when(groups.leafMembers(hostedRepo)).thenReturn(List.of(hostedRepo));
        when(groups.leafMembers(proxyRepo)).thenReturn(List.of(proxyRepo));
    }

    @Test
    void hostedListIsDerivedFromStoredZipsWithoutPseudoVersions() throws IOException {
        store(hostedRepo, "v0.2.0");
        store(hostedRepo, "v0.10.0");
        store(hostedRepo, "v0.0.0-20200101000000-abcdef123456");
        store(hostedRepo, "v1.0.0-!r!c1");
        storage.put(hostedRepo, MODULE + "/@v/v9.9.9.info", "{}".getBytes(StandardCharsets.UTF_8), "application/json", false); // no zip: not a version
        storage.put(hostedRepo, MODULE + "/sub/@v/v5.0.0.zip", new byte[] {1}, "application/zip", false); // another module
        storage.put(hostedRepo, MODULE + "/@v/odd!!.zip", new byte[] {1}, "application/zip", false); // undecodable, ignored

        assertThat(modules.list(hostedRepo, MODULE)).contains(List.of("v0.2.0", "v0.10.0", "v1.0.0-RC1"));
        assertThat(modules.storedVersions(hostedRepo, MODULE))
                .containsExactlyInAnyOrder("v0.2.0", "v0.10.0", "v0.0.0-20200101000000-abcdef123456", "v1.0.0-RC1");
        assertThat(modules.list(hostedRepo, "example.com/nothing")).isEmpty();
        assertThat(modules.list(hostedRepo, MODULE + "/sub")).contains(List.of("v5.0.0"));
    }

    @Test
    void hostedLatestPrefersReleasesAndReadsTheStoredInfo() throws IOException {
        assertThat(modules.latest(hostedRepo, MODULE)).isEmpty();
        store(hostedRepo, "v0.0.0-20200101000000-abcdef123456");
        assertThat(new String(modules.latest(hostedRepo, MODULE).orElseThrow(), StandardCharsets.UTF_8))
                .isEqualTo("{\"Version\":\"v0.0.0-20200101000000-abcdef123456\"}");
        store(hostedRepo, "v1.0.0-beta.1");
        assertThat(new String(modules.latest(hostedRepo, MODULE).orElseThrow(), StandardCharsets.UTF_8)).contains("v1.0.0-beta.1");
        store(hostedRepo, "v0.9.0");
        storage.put(hostedRepo, MODULE + "/@v/v0.9.0.info", "{\"Version\":\"v0.9.0\",\"Time\":\"2020-01-01T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8),
                "application/json", false);
        assertThat(new String(modules.latest(hostedRepo, MODULE).orElseThrow(), StandardCharsets.UTF_8))
                .isEqualTo("{\"Version\":\"v0.9.0\",\"Time\":\"2020-01-01T00:00:00Z\"}");
    }

    @Test
    void proxyListAndLatestComeFromUpstreamAsMetadata() throws IOException {
        StoredContent list = storage.put(proxyRepo, MODULE + "/@v/list", "v1.0.0\nv0.9.0 extra\n\nv0.0.0-20200101000000-abcdef123456\njunk\n".getBytes(StandardCharsets.UTF_8),
                "text/plain", true);
        when(proxy.fetch(proxyRepo, MODULE + "/@v/list", FetchOptions.index())).thenReturn(Optional.of(list));
        assertThat(modules.list(proxyRepo, MODULE)).contains(List.of("v0.9.0", "v1.0.0"));
        assertThat(modules.proxiedList(proxyRepo, MODULE)).contains(list);

        when(proxy.fetch(proxyRepo, "example.com/nothing/@v/list", FetchOptions.index())).thenReturn(Optional.empty());
        assertThat(modules.list(proxyRepo, "example.com/nothing")).isEmpty();

        StoredContent latest = storage.put(proxyRepo, MODULE + "/@latest", "{\"Version\":\"v1.0.0\"}".getBytes(StandardCharsets.UTF_8), "application/json", true);
        when(proxy.fetch(proxyRepo, MODULE + "/@latest", FetchOptions.index())).thenReturn(Optional.of(latest));
        assertThat(new String(modules.latest(proxyRepo, MODULE).orElseThrow(), StandardCharsets.UTF_8)).isEqualTo("{\"Version\":\"v1.0.0\"}");
    }

    @Test
    void groupListIsTheSortedUnionOfItsMembers() throws IOException {
        store(hostedRepo, "v0.5.0");
        store(hostedRepo, "v2.0.0-beta.1");
        StoredContent list = storage.put(proxyRepo, MODULE + "/@v/list", "v1.0.0\nv0.5.0\nv0.10.0\n".getBytes(StandardCharsets.UTF_8), "text/plain", true);
        when(proxy.fetch(proxyRepo, MODULE + "/@v/list", FetchOptions.index())).thenReturn(Optional.of(list));

        assertThat(modules.list(groupRepo, MODULE)).contains(List.of("v0.5.0", "v0.10.0", "v1.0.0", "v2.0.0-beta.1"));

        when(proxy.fetch(proxyRepo, "example.com/private/@v/list", FetchOptions.index())).thenReturn(Optional.empty());
        store(hostedRepo, "example.com/private", "v0.1.0");
        assertThat(modules.list(groupRepo, "example.com/private")).contains(List.of("v0.1.0"));
        when(proxy.fetch(proxyRepo, "example.com/nothing/@v/list", FetchOptions.index())).thenReturn(Optional.empty());
        assertThat(modules.list(groupRepo, "example.com/nothing")).isEmpty();
    }

    @Test
    void groupLatestIsTheHighestReleaseAcrossMembersFromTheMemberThatHasIt() throws IOException {
        store(hostedRepo, "v3.0.0");
        storage.put(hostedRepo, MODULE + "/@v/v3.0.0.info", "{\"Version\":\"v3.0.0\",\"Time\":\"2021-01-01T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8),
                "application/json", false);
        StoredContent list = storage.put(proxyRepo, MODULE + "/@v/list", "v1.0.0\nv2.0.0\n".getBytes(StandardCharsets.UTF_8), "text/plain", true);
        when(proxy.fetch(proxyRepo, MODULE + "/@v/list", FetchOptions.index())).thenReturn(Optional.of(list));

        assertThat(new String(modules.latest(groupRepo, MODULE).orElseThrow(), StandardCharsets.UTF_8))
                .isEqualTo("{\"Version\":\"v3.0.0\",\"Time\":\"2021-01-01T00:00:00Z\"}");
        verify(proxy, never()).fetch(eq(proxyRepo), eq(MODULE + "/@v/v3.0.0.info"), any());

        // the proxy's version wins when it is higher; its .info is fetched from the proxy
        StoredContent list2 = storage.put(proxyRepo, MODULE + "/@v/list", "v1.0.0\nv4.0.0\n".getBytes(StandardCharsets.UTF_8), "text/plain", true);
        when(proxy.fetch(proxyRepo, MODULE + "/@v/list", FetchOptions.index())).thenReturn(Optional.of(list2));
        StoredContent info = storage.put(proxyRepo, MODULE + "/@v/v4.0.0.info", "{\"Version\":\"v4.0.0\"}".getBytes(StandardCharsets.UTF_8), "application/json", false);
        when(proxy.fetch(proxyRepo, MODULE + "/@v/v4.0.0.info", FetchOptions.artifact())).thenReturn(Optional.of(info));
        assertThat(new String(modules.latest(groupRepo, MODULE).orElseThrow(), StandardCharsets.UTF_8)).isEqualTo("{\"Version\":\"v4.0.0\"}");
    }

    @Test
    void groupLatestFallsBackToTheProxyWhenNoMemberListsAVersion() throws IOException {
        when(proxy.fetch(proxyRepo, MODULE + "/@v/list", FetchOptions.index())).thenReturn(Optional.empty());
        StoredContent latest = storage.put(proxyRepo, MODULE + "/@latest",
                "{\"Version\":\"v0.0.0-20200101000000-abcdef123456\"}".getBytes(StandardCharsets.UTF_8), "application/json", true);
        when(proxy.fetch(proxyRepo, MODULE + "/@latest", FetchOptions.index())).thenReturn(Optional.of(latest));
        assertThat(new String(modules.latest(groupRepo, MODULE).orElseThrow(), StandardCharsets.UTF_8)).contains("20200101000000");

        when(proxy.fetch(proxyRepo, MODULE + "/@latest", FetchOptions.index())).thenReturn(Optional.empty());
        assertThat(modules.latest(groupRepo, MODULE)).isEmpty();
    }

    @Test
    void groupLatestSynthesisesAnInfoWhenTheOwnerLostIt() throws IOException {
        storage.put(hostedRepo, MODULE + "/@v/v1.0.0.zip", new byte[] {1}, "application/zip", false);
        when(proxy.fetch(proxyRepo, MODULE + "/@v/list", FetchOptions.index())).thenReturn(Optional.empty());
        when(proxy.fetch(proxyRepo, MODULE + "/@v/v1.0.0.info", FetchOptions.artifact())).thenReturn(Optional.empty());
        assertThat(new String(modules.latest(groupRepo, MODULE).orElseThrow(), StandardCharsets.UTF_8)).isEqualTo("{\"Version\":\"v1.0.0\"}");
    }

    @Test
    void filesComeFromStorageTheProxyOrTheFirstMember() throws IOException {
        store(hostedRepo, "v1.0.0");
        GoPath zip = GoPath.parse(MODULE + "/@v/v1.0.0.zip");
        assertThat(modules.file(hostedRepo, zip)).isPresent();
        assertThat(modules.file(hostedRepo, GoPath.parse(MODULE + "/@v/v2.0.0.zip"))).isEmpty();

        StoredContent cached = storage.put(proxyRepo, MODULE + "/@v/v2.0.0.zip", new byte[] {2}, "application/zip", false);
        when(proxy.fetch(proxyRepo, MODULE + "/@v/v2.0.0.zip", FetchOptions.artifact())).thenReturn(Optional.of(cached));
        when(proxy.fetch(proxyRepo, MODULE + "/@v/v1.0.0.zip", FetchOptions.artifact())).thenReturn(Optional.empty());
        assertThat(modules.file(groupRepo, zip).orElseThrow().readAllBytes()).isEqualTo(storage.bytes(hostedRepo, MODULE + "/@v/v1.0.0.zip"));
        assertThat(modules.file(groupRepo, GoPath.parse(MODULE + "/@v/v2.0.0.zip")).orElseThrow().readAllBytes()).isEqualTo(new byte[] {2});
        verify(proxy, never()).fetch(eq(proxyRepo), eq(MODULE + "/@v/v1.0.0.zip"), any());

        // branch lookups are metadata on proxies
        when(proxy.fetch(proxyRepo, MODULE + "/@v/master.info", FetchOptions.index())).thenReturn(Optional.empty());
        assertThat(modules.file(proxyRepo, GoPath.parse(MODULE + "/@v/master.info"))).isEmpty();
        verify(proxy).fetch(proxyRepo, MODULE + "/@v/master.info", FetchOptions.index());
    }

    @Test
    void sumdbIsPassedThroughProxiesOnly() {
        assertThat(modules.sumdbSupported(hostedRepo, "sum.golang.org")).isFalse();
        assertThat(modules.sumdbSupported(proxyRepo, "sum.golang.org")).isTrue();
        assertThat(modules.sumdbSupported(groupRepo, "sum.golang.org")).isTrue();
        assertThat(modules.sumdbSupported(proxyRepo, "sum.example.com")).isFalse();
        assertThat(modules.sumdbSupported(groupRepo, "sum.example.com")).isFalse();
        Repository hostedOnly = GoTestSupport.group("go-internal", "go-hosted");
        when(groups.leafMembers(hostedOnly)).thenReturn(List.of(hostedRepo));
        assertThat(modules.sumdbSupported(hostedOnly, "sum.golang.org")).isFalse();

        GoPath lookup = GoPath.parse("sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2");
        StoredContent entry = storage.put(proxyRepo, lookup.raw(), "rsc.io/quote v1.5.2 h1:x\n".getBytes(StandardCharsets.UTF_8), "text/plain", false);
        when(proxy.fetch(eq(proxyRepo), eq(lookup.raw()), eq(URI.create("https://sum.golang.org/lookup/rsc.io/quote@v1.5.2")),
                eq(FetchOptions.artifact()), anyMap())).thenReturn(Optional.of(entry));
        assertThat(modules.sumdb(hostedRepo, lookup)).isEmpty();
        assertThat(modules.sumdb(proxyRepo, lookup)).contains(entry);
        assertThat(modules.sumdb(groupRepo, lookup)).contains(entry);
        assertThat(modules.sumdb(hostedOnly, lookup)).isEmpty();

        GoPath latest = GoPath.parse("sumdb/sum.golang.org/latest");
        when(proxy.fetch(eq(proxyRepo), eq(latest.raw()), eq(URI.create("https://sum.golang.org/latest")), eq(FetchOptions.index()), anyMap()))
                .thenReturn(Optional.empty());
        assertThat(modules.sumdb(proxyRepo, latest)).isEmpty();
        verify(proxy).fetch(eq(proxyRepo), eq(latest.raw()), eq(URI.create("https://sum.golang.org/latest")), eq(FetchOptions.index()), anyMap());
    }

    @Test
    void onlyTheConfiguredDatabaseIsPassedThrough() {
        GoPath other = GoPath.parse("sumdb/sum.example.com/lookup/rsc.io/quote@v1.5.2");
        assertThat(modules.sumdb(proxyRepo, other)).isEmpty();
        assertThat(modules.sumdb(groupRepo, other)).isEmpty();
        verify(proxy, never()).fetch(any(), any(), any(URI.class), any(), anyMap());

        proxyRepo.setFormatSettings(Map.of(GoModules.SUMDB_NAME, "sum.example.com"));
        assertThat(GoModules.sumdbName(proxyRepo)).isEqualTo("sum.example.com");
        assertThat(modules.sumdbSupported(groupRepo, "sum.example.com")).isTrue();
        assertThat(modules.sumdbSupported(groupRepo, "sum.golang.org")).isFalse();
        assertThat(GoModules.sumdbUri(proxyRepo, other)).isEqualTo(URI.create("https://sum.example.com/lookup/rsc.io/quote@v1.5.2"));
        when(proxy.fetch(eq(proxyRepo), eq(other.raw()), any(URI.class), eq(FetchOptions.artifact()), anyMap())).thenReturn(Optional.empty());
        assertThat(modules.sumdb(groupRepo, other)).isEmpty();
        verify(proxy).fetch(eq(proxyRepo), eq(other.raw()), eq(URI.create("https://sum.example.com/lookup/rsc.io/quote@v1.5.2")),
                eq(FetchOptions.artifact()), anyMap());
    }

    @Test
    void sumdbUrlSettingOverridesTheHost() {
        GoPath lookup = GoPath.parse("sumdb/sum.golang.org/lookup/github.com/!burnt!sushi/toml@v1.3.2+incompatible");
        assertThat(GoModules.sumdbUri(proxyRepo, lookup))
                .isEqualTo(URI.create("https://sum.golang.org/lookup/github.com/!burnt!sushi/toml@v1.3.2+incompatible"));
        proxyRepo.setFormatSettings(Map.of(GoModules.SUMDB_URL, "https://sum.golang.google.cn/"));
        assertThat(GoModules.sumdbUri(proxyRepo, lookup))
                .isEqualTo(URI.create("https://sum.golang.google.cn/lookup/github.com/!burnt!sushi/toml@v1.3.2+incompatible"));
        proxyRepo.setFormatSettings(Map.of(GoModules.SUMDB_URL, "http://mirror.internal:8080/sumdb"));
        assertThat(GoModules.sumdbUri(proxyRepo, GoPath.parse("sumdb/sum.golang.org/tile/8/0/x001/234")))
                .isEqualTo(URI.create("http://mirror.internal:8080/sumdb/tile/8/0/x001/234"));
    }

    @Test
    void upstreamCredentialsOnlyGoToAChecksumDatabaseOnTheSameOrigin() {
        GoPath lookup = GoPath.parse("sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2");
        Repository privateProxy = GoTestSupport.proxy("go-private", "https://goproxy.corp.example/");
        privateProxy.setRemoteToken("secret-token");
        privateProxy.setMetadataMaxAgeMinutes(7);
        privateProxy.setNegativeCacheMinutes(3);
        privateProxy.setOfflineMode(true);

        Repository view = GoModules.withoutForeignCredentials(privateProxy, GoModules.sumdbUri(privateProxy, lookup));
        assertThat(view).isNotSameAs(privateProxy);
        assertThat(view.getRemoteToken()).isNull();
        assertThat(view.getRemoteUsername()).isNull();
        assertThat(view.getRemotePassword()).isNull();
        // the cache, offline switch and timeouts stay those of the proxy
        assertThat(view.getId()).isEqualTo(privateProxy.getId());
        assertThat(view.getName()).isEqualTo("go-private");
        assertThat(view.getBlobStore()).isEqualTo(privateProxy.getBlobStore());
        assertThat(view.getMetadataMaxAgeMinutes()).isEqualTo(7);
        assertThat(view.getNegativeCacheMinutes()).isEqualTo(3);
        assertThat(view.isOfflineMode()).isTrue();
        assertThat(privateProxy.getRemoteToken()).isEqualTo("secret-token");

        Repository basic = GoTestSupport.proxy("go-basic", "https://goproxy.corp.example/");
        basic.setRemoteUsername("ci");
        basic.setRemotePassword("pw");
        assertThat(GoModules.withoutForeignCredentials(basic, URI.create("https://sum.golang.org/latest")).getRemotePassword()).isNull();

        // a checksum database served by the same upstream gets the credentials, so does one without any
        assertThat(GoModules.withoutForeignCredentials(privateProxy, URI.create("https://goproxy.corp.example/sumdb/sum.golang.org/latest")))
                .isSameAs(privateProxy);
        assertThat(GoModules.withoutForeignCredentials(privateProxy, URI.create("https://GOPROXY.corp.example:443/sumdb/latest")))
                .isSameAs(privateProxy);
        assertThat(GoModules.withoutForeignCredentials(proxyRepo, URI.create("https://sum.golang.org/latest"))).isSameAs(proxyRepo);
        // another port or scheme is another origin
        assertThat(GoModules.sameOrigin("https://goproxy.corp.example/", URI.create("https://goproxy.corp.example:8443/x"))).isFalse();
        assertThat(GoModules.sameOrigin("https://goproxy.corp.example/", URI.create("http://goproxy.corp.example/x"))).isFalse();
        assertThat(GoModules.sameOrigin("http://localhost:8081/", URI.create("http://127.0.0.1:8081/x"))).isFalse();
        assertThat(GoModules.sameOrigin(null, URI.create("http://localhost/x"))).isFalse();
        assertThat(GoModules.sameOrigin("not a url", URI.create("http://localhost/x"))).isFalse();

        when(proxy.fetch(any(Repository.class), eq(lookup.raw()), any(URI.class), eq(FetchOptions.artifact()), anyMap()))
                .thenReturn(Optional.empty());
        modules.sumdb(privateProxy, lookup);
        verify(proxy).fetch(org.mockito.ArgumentMatchers.argThat(r -> r.getRemoteToken() == null && r.getId().equals(privateProxy.getId())),
                eq(lookup.raw()), eq(URI.create("https://sum.golang.org/lookup/rsc.io/quote@v1.5.2")), eq(FetchOptions.artifact()), anyMap());
    }

    @Test
    void listDocumentsRoundTrip() {
        assertThat(GoModules.renderList(List.of("v0.1.0", "v1.0.0"))).isEqualTo("v0.1.0\nv1.0.0\n".getBytes(StandardCharsets.UTF_8));
        assertThat(GoModules.renderList(List.of())).isEmpty();
        assertThat(GoModules.parseList("v1.0.0\r\n  v0.9.0 2020-01-01T00:00:00Z\n\nv0.0.0-20200101000000-abcdef123456\nmaster\n".getBytes(StandardCharsets.UTF_8)))
                .containsExactly("v1.0.0", "v0.9.0");
        assertThat(GoModules.parseList(new byte[0])).isEmpty();
    }

    private void store(Repository repo, String escapedVersion) {
        store(repo, MODULE, escapedVersion);
    }

    private void store(Repository repo, String module, String escapedVersion) {
        storage.put(repo, module + "/@v/" + escapedVersion + ".zip", ("zip " + escapedVersion).getBytes(StandardCharsets.UTF_8), "application/zip", false);
        storage.put(repo, module + "/@v/" + escapedVersion + ".mod", ("module " + module + "\n").getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", false);
    }
}
