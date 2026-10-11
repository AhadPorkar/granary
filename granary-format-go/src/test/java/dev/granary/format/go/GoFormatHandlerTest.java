package dev.granary.format.go;

import static dev.granary.format.go.GoTestSupport.MAPPER;
import static dev.granary.format.go.GoTestSupport.body;
import static dev.granary.format.go.GoTestSupport.bodyBytes;
import static dev.granary.format.go.GoTestSupport.hosted;
import static dev.granary.format.go.GoTestSupport.http;
import static dev.granary.format.go.GoTestSupport.json;
import static dev.granary.format.go.GoTestSupport.request;
import static dev.granary.format.go.GoTestSupport.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.GranaryException;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.ContentResolver;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StoredContent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class GoFormatHandlerTest {

    private static final String MODULE = "example.com/acme/hello";

    @TempDir
    Path dataDir;

    private final FakeStorage storage = new FakeStorage();
    private final ProxyService proxy = mock(ProxyService.class);
    private final GroupResolver groups = mock(GroupResolver.class);
    private final Repository hostedRepo = hosted("go-hosted");
    private final Repository proxyRepo = GoTestSupport.proxy("go-proxy", "https://proxy.golang.org/");
    private final Repository groupRepo = GoTestSupport.group("go-group", "go-hosted", "go-proxy");
    private GoFormatHandler handler;

    @BeforeEach
    void setUp() {
        GranaryProperties properties = new GranaryProperties();
        properties.setDataDir(dataDir);
        ContentResolver resolver = new ContentResolver(storage.mock, proxy, groups);
        GoModules modules = new GoModules(storage.mock, proxy, groups, resolver, MAPPER);
        handler = new GoFormatHandler(storage.mock, modules, new GoHostedWriter(storage.mock, MAPPER, properties), groups, MAPPER);
        when(groups.leafMembers(groupRepo)).thenReturn(List.of(hostedRepo, proxyRepo));
        when(groups.leafMembers(hostedRepo)).thenReturn(List.of(hostedRepo));
        when(groups.leafMembers(proxyRepo)).thenReturn(List.of(proxyRepo));
    }

    @Test
    void identityTemplatesAndSettings() {
        assertThat(handler.format()).isEqualTo("go");
        assertThat(handler.displayName()).isEqualTo("Go modules");
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder(RepositoryType.values());
        assertThat(handler.templates()).extracting(RepositoryTemplate::name).containsExactly("go-proxy", "go-hosted", "go-group");
        assertThat(handler.templates().get(0).remoteUrl()).isEqualTo("https://proxy.golang.org/");
        assertThat(handler.templates().get(2).members()).containsExactly("go-hosted", "go-proxy");
        assertThat(handler.templates()).allSatisfy(t -> assertThat(t.format()).isEqualTo("go"));
        assertThat(handler.settings()).extracting(SettingDescriptor::key).containsExactly("go.sumdbName", "go.sumdbUrl");
        assertThat(handler.settings().get(0).type()).isEqualTo(SettingDescriptor.Type.STRING);
        assertThat(handler.settings().get(0).defaultValue()).isEqualTo("sum.golang.org");
        assertThat(handler.settings().get(1).type()).isEqualTo(SettingDescriptor.Type.URL);
        assertThat(handler.settings().get(1).defaultValue()).isEqualTo("https://sum.golang.org");
        assertThat(handler.settings()).allSatisfy(d -> assertThat(d.appliesTo()).containsExactly(RepositoryType.PROXY));
    }

    @Test
    void validateChecksTheSumdbUrl() {
        handler.validate(proxyRepo);
        proxyRepo.setFormatSettings(Map.of("go.sumdbUrl", "https://sum.golang.google.cn"));
        handler.validate(proxyRepo);
        proxyRepo.setFormatSettings(Map.of("go.sumdbUrl", " "));
        handler.validate(proxyRepo);
        for (String bad : new String[] {"sum.golang.org", "ftp://x", "https://", "http:// bad", "https://sum.golang.org/?x=1", "https://sum.golang.org/#x"}) {
            proxyRepo.setFormatSettings(Map.of("go.sumdbUrl", bad));
            assertThatThrownBy(() -> handler.validate(proxyRepo)).as(bad).isInstanceOf(GranaryException.class)
                    .satisfies(e -> assertThat(((GranaryException) e).code()).isEqualTo("repository.go.sumdbUrl.invalid"));
        }
    }

    @Test
    void validateChecksTheSumdbName() {
        for (String good : new String[] {"sum.golang.org", "sum.example.com", "localhost", "  sum.golang.org  ", ""}) {
            proxyRepo.setFormatSettings(Map.of("go.sumdbName", good));
            handler.validate(proxyRepo);
        }
        for (String bad : new String[] {"Sum.golang.org", "https://sum.golang.org", "sum.golang.org/x", "-sum.org", "sum..org", "sum_db.org"}) {
            proxyRepo.setFormatSettings(Map.of("go.sumdbName", bad));
            assertThatThrownBy(() -> handler.validate(proxyRepo)).as(bad).isInstanceOf(GranaryException.class)
                    .satisfies(e -> assertThat(((GranaryException) e).code()).isEqualTo("repository.go.sumdbName.invalid"));
        }
    }

    @Test
    void connectSnippetsPerRepositoryType() {
        List<ConnectSnippet> hostedSnippets = handler.connectSnippets(context(hostedRepo, true));
        assertThat(hostedSnippets).extracting(ConnectSnippet::id).containsExactly("go-env", "go-private", "netrc", "dockerfile", "curl-publish");
        assertThat(hostedSnippets.get(0).code()).contains("go env -w GOPROXY=http://granary.test/repository/go-hosted,direct")
                .contains("go env -w GOSUMDB=sum.golang.org")
                .contains("has no checksum database")
                .doesNotContain("consulted through the repository");
        assertThat(hostedSnippets.get(2).filename()).isEqualTo("~/.netrc");
        assertThat(hostedSnippets.get(2).code()).contains("machine granary.test login alice password <api-token>")
                .contains("sends credentials only over HTTPS")
                .doesNotContain("alice:<api-token>@");
        assertThat(hostedSnippets.get(3).code()).contains("ENV GOPROXY=http://granary.test/repository/go-hosted,direct");
        assertThat(hostedSnippets.get(4).code()).contains("curl -u alice:<api-token> -T hello-v0.1.0.zip http://granary.test/repository/go-hosted/example.com/acme/hello/@v/v0.1.0.zip")
                .contains("-X DELETE");

        List<ConnectSnippet> proxySnippets = handler.connectSnippets(context(proxyRepo, false));
        assertThat(proxySnippets).extracting(ConnectSnippet::id).containsExactly("go-env", "netrc", "dockerfile");
        assertThat(proxySnippets.get(0).code()).contains("consulted through the repository").contains("\ngo env -w GOSUMDB=sum.golang.org\n");
        assertThat(proxySnippets.get(1).code()).contains("anonymous reads are off");

        List<ConnectSnippet> groupSnippets = handler.connectSnippets(context(groupRepo, true));
        assertThat(groupSnippets).extracting(ConnectSnippet::id).containsExactly("go-env", "go-private", "netrc", "dockerfile");
        assertThat(groupSnippets.get(1).code()).contains("the hosted member go-hosted");

        ConnectContext https = new ConnectContext(groupRepo, "https://granary.example/repository/go-group", "https://granary.example",
                ConnectContext.USERNAME_PLACEHOLDER, ConnectContext.TOKEN_PLACEHOLDER, true);
        assertThat(handler.connectSnippets(https).get(2).code()).doesNotContain("only over HTTPS")
                .contains("machine granary.example login <username> password <api-token>")
                .contains("go env -w GOPROXY=https://<username>:<api-token>@granary.example/repository/go-group,direct");
        assertThat(handler.connectSnippets(https)).allSatisfy(s -> assertThat(s.code()).doesNotContain("granary.test"));

        ConnectContext withPort = new ConnectContext(groupRepo, "https://granary.example:8443/repository/go-group", "https://granary.example:8443",
                "alice", ConnectContext.TOKEN_PLACEHOLDER, false);
        assertThat(handler.connectSnippets(withPort).get(2).code())
                .contains("machine granary.example:8443 login alice password <api-token>\n")
                .contains("machine granary.example login alice password <api-token>\n")
                .contains("GOPROXY=https://alice:<api-token>@granary.example:8443/repository/go-group,direct");
    }

    private static ConnectContext context(Repository repo, boolean anonymousRead) {
        return new ConnectContext(repo, GoTestSupport.baseUrl(repo), GoTestSupport.SERVER, "alice", ConnectContext.TOKEN_PLACEHOLDER, anonymousRead);
    }

    @Test
    void metadataPathsAndRequiredAccess() {
        assertThat(handler.isMetadataPath("rsc.io/quote/@v/list")).isTrue();
        assertThat(handler.isMetadataPath("rsc.io/quote/@latest")).isTrue();
        assertThat(handler.isMetadataPath("rsc.io/quote/@v/master.info")).isTrue();
        assertThat(handler.isMetadataPath("sumdb/sum.golang.org/latest")).isTrue();
        assertThat(handler.isMetadataPath("rsc.io/quote/@v/v1.5.2.info")).isFalse();
        assertThat(handler.isMetadataPath("rsc.io/quote/@v/v1.5.2.zip")).isFalse();
        assertThat(handler.isMetadataPath("sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2")).isFalse();
        assertThat(handler.isMetadataPath("Bad/Path/@v/list")).isFalse();
        assertThat(handler.isMetadataPath(null)).isFalse();

        assertThat(handler.requiredAccess(request(hostedRepo, "GET", MODULE + "/@v/list"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "HEAD", MODULE + "/@v/v1.0.0.zip"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "PUT", MODULE + "/@v/v1.0.0.zip"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hostedRepo, "DELETE", MODULE + "/@v/v1.0.0"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(proxyRepo, "GET", "sumdb/sum.golang.org/supported"))).isEqualTo(AccessLevel.READ);
    }

    @Test
    void hostedPublishAndReadBackTheWayTheGoCommandAsks() throws Exception {
        byte[] zip = GoTestZips.module(MODULE, "v0.1.0");
        ResponseEntity<?> published = handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v0.1.0.zip", zip));
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(str(json(published), "module")).isEqualTo(MODULE);
        assertThat(json(published).path("files")).hasSize(3);

        ResponseEntity<?> list = handler.handle(request(hostedRepo, "GET", MODULE + "/@v/list"));
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("text/plain; charset=utf-8"));
        assertThat(list.getHeaders().getETag()).isNotNull();
        assertThat(list.getHeaders().getCacheControl()).isEqualTo("no-cache");
        assertThat(body(list)).isEqualTo("v0.1.0\n");

        ResponseEntity<?> info = handler.handle(request(hostedRepo, "GET", MODULE + "/@v/v0.1.0.info"));
        assertThat(info.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(info.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(str(json(info), "Version")).isEqualTo("v0.1.0");
        assertThat(str(json(info), "Time")).endsWith("Z");

        ResponseEntity<?> mod = handler.handle(request(hostedRepo, "GET", MODULE + "/@v/v0.1.0.mod"));
        assertThat(mod.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("text/plain; charset=utf-8"));
        assertThat(body(mod)).startsWith("module " + MODULE);

        ResponseEntity<?> download = handler.handle(request(hostedRepo, "GET", MODULE + "/@v/v0.1.0.zip"));
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("application/zip"));
        assertThat(download.getHeaders().getContentLength()).isEqualTo(zip.length);
        assertThat(bodyBytes(download)).isEqualTo(zip);
        String etag = download.getHeaders().getETag();
        assertThat(etag).isNotNull();

        RepositoryRequest conditional = request(hostedRepo, "GET", MODULE + "/@v/v0.1.0.zip");
        http(conditional).addHeader(HttpHeaders.IF_NONE_MATCH, etag);
        assertThat(handler.handle(conditional).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        ResponseEntity<?> head = handler.handle(request(hostedRepo, "HEAD", MODULE + "/@v/v0.1.0.zip"));
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isEqualTo(zip.length);

        ResponseEntity<?> latest = handler.handle(request(hostedRepo, "GET", MODULE + "/@latest"));
        assertThat(latest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(str(json(latest), "Version")).isEqualTo("v0.1.0");

        RepositoryRequest listAgain = request(hostedRepo, "GET", MODULE + "/@v/list");
        http(listAgain).addHeader(HttpHeaders.IF_NONE_MATCH, list.getHeaders().getETag());
        assertThat(handler.handle(listAgain).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        ResponseEntity<?> deleted = handler.handle(request(hostedRepo, "DELETE", MODULE + "/@v/v0.1.0", new byte[0]));
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(deleted).path("deleted")).hasSize(3);
        assertThat(handler.handle(request(hostedRepo, "GET", MODULE + "/@v/list")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hostedRepo, "GET", MODULE + "/@v/v0.1.0.zip")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hostedRepo, "DELETE", MODULE + "/@v/v0.1.0", new byte[0])).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void hostedPutOfInfoAndMod() throws Exception {
        handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v0.1.0.zip", GoTestZips.module(MODULE, "v0.1.0")));
        ResponseEntity<?> info = handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v0.1.0.info",
                "{\"Version\":\"v0.1.0\",\"Time\":\"2020-01-01T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8)));
        assertThat(info.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(handler.handle(request(hostedRepo, "GET", MODULE + "/@v/v0.1.0.info")))).contains("2020-01-01T00:00:00Z");
        // the go.mod already served may be uploaded again (200, nothing written), a different one is a conflict
        byte[] served = bodyBytes(handler.handle(request(hostedRepo, "GET", MODULE + "/@v/v0.1.0.mod")));
        ResponseEntity<?> same = handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v0.1.0.mod", served));
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(same).path("files")).isEmpty();
        ResponseEntity<?> changed = handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v0.1.0.mod",
                ("module " + MODULE + "\n\ngo 1.22\n").getBytes(StandardCharsets.UTF_8)));
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(str(json(changed), "code")).isEqualTo("repository.go.versionExists");
        hostedRepo.setAllowRedeploy(true);
        ResponseEntity<?> mod = handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v0.1.0.mod",
                ("module " + MODULE + "\n\ngo 1.22\n").getBytes(StandardCharsets.UTF_8)));
        assertThat(mod.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(handler.handle(request(hostedRepo, "GET", MODULE + "/@v/v0.1.0.mod")))).contains("go 1.22");
        // the files of a version that was never published are refused
        ResponseEntity<?> orphan = handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v0.9.0.info",
                "{\"Version\":\"v0.9.0\"}".getBytes(StandardCharsets.UTF_8)));
        assertThat(orphan.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(str(json(orphan), "code")).isEqualTo("repository.go.versionMissing");
        ResponseEntity<?> wrong = handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v0.1.0.mod", "module example.com/x\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(str(json(wrong), "code")).isEqualTo("repository.go.badRequest");
    }

    @Test
    void errorsAreJsonWithStableCodes() throws Exception {
        ResponseEntity<?> conflict;
        handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v0.1.0.zip", GoTestZips.module(MODULE, "v0.1.0")));
        conflict = handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v0.1.0.zip", GoTestZips.module(MODULE, "v0.1.0")));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(str(json(conflict), "code")).isEqualTo("repository.go.versionExists");

        ResponseEntity<?> badZip = handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v0.2.0.zip", "nope".getBytes(StandardCharsets.UTF_8)));
        assertThat(badZip.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(str(json(badZip), "code")).isEqualTo("repository.go.invalidZip");

        ResponseEntity<?> badVersion = handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v2.0.0.zip", GoTestZips.module(MODULE, "v2.0.0")));
        assertThat(badVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(str(json(badVersion), "code")).isEqualTo("repository.go.invalidVersion");

        ResponseEntity<?> badPath = handler.handle(request(hostedRepo, "GET", "github.com/BurntSushi/toml/@v/list"));
        assertThat(badPath.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(str(json(badPath), "code")).isEqualTo("repository.go.invalidPath");
        assertThat(str(json(badPath), "message")).contains("!x");

        assertThat(handler.handle(request(hostedRepo, "GET", "example.com/nothing/@v/list")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hostedRepo, "GET", "example.com/nothing/@latest")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hostedRepo, "GET", MODULE + "/@v/master.info")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hostedRepo, "GET", MODULE + "/@v/master.zip")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hostedRepo, "GET", MODULE + "/@v/v0.1.0")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hostedRepo, "GET", "something/else")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hostedRepo, "POST", MODULE + "/@v/list", new byte[0])).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hostedRepo, "PUT", MODULE + "/@latest", new byte[0])).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v0.1.0", new byte[0])).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hostedRepo, "DELETE", "something/else", new byte[0])).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void rootDescribesTheRepository() throws Exception {
        ResponseEntity<?> root = handler.handle(request(groupRepo, "GET", ""));
        assertThat(root.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(str(json(root), "format")).isEqualTo("go");
        assertThat(str(json(root), "type")).isEqualTo("group");
        assertThat(str(json(root), "goproxy")).isEqualTo("http://granary.test/repository/go-group");
        assertThat(handler.handle(request(groupRepo, "PUT", "", new byte[0])).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void groupsAndProxiesRefuseWritesNamingTheHostedMember() throws Exception {
        ResponseEntity<?> group = handler.handle(request(groupRepo, "PUT", MODULE + "/@v/v0.1.0.zip", GoTestZips.module(MODULE, "v0.1.0")));
        assertThat(group.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(str(json(group), "code")).isEqualTo("repository.readOnly");
        assertThat(str(json(group), "message")).contains("its hosted member 'go-hosted'");

        ResponseEntity<?> proxyPut = handler.handle(request(proxyRepo, "PUT", MODULE + "/@v/v0.1.0.info", "{}".getBytes(StandardCharsets.UTF_8)));
        assertThat(proxyPut.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(str(json(proxyPut), "message")).contains("proxy repository");
        assertThat(handler.handle(request(proxyRepo, "DELETE", MODULE + "/@v/v0.1.0", new byte[0])).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        Repository proxiesOnly = GoTestSupport.group("go-public", "go-proxy");
        when(groups.leafMembers(proxiesOnly)).thenReturn(List.of(proxyRepo));
        assertThat(str(json(handler.handle(request(proxiesOnly, "DELETE", MODULE + "/@v/v0.1.0", new byte[0]))), "message"))
                .contains("publish to a hosted repository instead");
        assertThat(storage.paths(hostedRepo)).isEmpty();
    }

    @Test
    void proxyListIsServedFromTheCachedUpstreamDocument() throws Exception {
        StoredContent list = storage.put(proxyRepo, MODULE + "/@v/list", "v1.0.0\nv0.9.0\n".getBytes(StandardCharsets.UTF_8), "text/plain", true);
        when(proxy.fetch(proxyRepo, MODULE + "/@v/list", FetchOptions.index())).thenReturn(Optional.of(list));
        ResponseEntity<?> response = handler.handle(request(proxyRepo, "GET", MODULE + "/@v/list"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response)).isEqualTo("v1.0.0\nv0.9.0\n");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
        when(proxy.fetch(proxyRepo, "example.com/nothing/@v/list", FetchOptions.index())).thenReturn(Optional.empty());
        assertThat(handler.handle(request(proxyRepo, "GET", "example.com/nothing/@v/list")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void groupListMergesAndFilesComeFromTheFirstMember() throws Exception {
        handler.handle(request(hostedRepo, "PUT", MODULE + "/@v/v0.1.0.zip", GoTestZips.module(MODULE, "v0.1.0")));
        StoredContent list = storage.put(proxyRepo, MODULE + "/@v/list", "v1.0.0\n".getBytes(StandardCharsets.UTF_8), "text/plain", true);
        when(proxy.fetch(proxyRepo, MODULE + "/@v/list", FetchOptions.index())).thenReturn(Optional.of(list));
        assertThat(body(handler.handle(request(groupRepo, "GET", MODULE + "/@v/list")))).isEqualTo("v0.1.0\nv1.0.0\n");
        assertThat(bodyBytes(handler.handle(request(groupRepo, "GET", MODULE + "/@v/v0.1.0.zip")))).isEqualTo(GoTestZips.module(MODULE, "v0.1.0"));
        StoredContent info = storage.put(proxyRepo, MODULE + "/@v/v1.0.0.info", "{\"Version\":\"v1.0.0\"}".getBytes(StandardCharsets.UTF_8), "application/json", false);
        when(proxy.fetch(proxyRepo, MODULE + "/@v/v1.0.0.info", FetchOptions.artifact(component("v1.0.0")))).thenReturn(Optional.of(info));
        assertThat(str(json(handler.handle(request(groupRepo, "GET", MODULE + "/@latest"))), "Version")).isEqualTo("v1.0.0");
    }

    @Test
    void sumdbPassthrough() throws Exception {
        assertThat(handler.handle(request(hostedRepo, "GET", "sumdb/sum.golang.org/supported")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<?> supported = handler.handle(request(proxyRepo, "GET", "sumdb/sum.golang.org/supported"));
        assertThat(supported.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(supported.getBody()).isNull();
        assertThat(supported.getHeaders().getContentLength()).isZero();
        assertThat(handler.handle(request(groupRepo, "GET", "sumdb/sum.golang.org/supported")).getStatusCode()).isEqualTo(HttpStatus.OK);

        String path = "sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2";
        StoredContent entry = storage.put(proxyRepo, path, "rsc.io/quote v1.5.2 h1:abc\n".getBytes(StandardCharsets.UTF_8), "text/plain", false);
        when(proxy.fetch(eq(proxyRepo), eq(path), eq(URI.create("https://sum.golang.org/lookup/rsc.io/quote@v1.5.2")), eq(FetchOptions.artifact()), anyMap()))
                .thenReturn(Optional.of(entry));
        ResponseEntity<?> lookup = handler.handle(request(groupRepo, "GET", path));
        assertThat(lookup.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(lookup)).isEqualTo("rsc.io/quote v1.5.2 h1:abc\n");
        assertThat(handler.handle(request(hostedRepo, "GET", path)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(proxy.fetch(eq(proxyRepo), eq("sumdb/sum.golang.org/latest"), any(URI.class), eq(FetchOptions.index()), anyMap())).thenReturn(Optional.empty());
        assertThat(handler.handle(request(proxyRepo, "GET", "sumdb/sum.golang.org/latest")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(proxyRepo, "PUT", path, new byte[0])).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(proxyRepo, "GET", "sumdb/sum.golang.org/../x")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherChecksumDatabasesAreNeverForwarded() throws Exception {
        assertThat(handler.handle(request(proxyRepo, "GET", "sumdb/internal.example/supported")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(groupRepo, "GET", "sumdb/internal.example/supported")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(groupRepo, "GET", "sumdb/internal.example/latest")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(proxyRepo, "GET", "sumdb/169.254.169.254/latest")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(proxy, never()).fetch(any(), any(), any(URI.class), any(), anyMap());

        proxyRepo.setFormatSettings(Map.of("go.sumdbName", "internal.example"));
        assertThat(handler.handle(request(proxyRepo, "GET", "sumdb/internal.example/supported")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(handler.handle(request(proxyRepo, "GET", "sumdb/sum.golang.org/supported")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** The component a publish of {@code version} of the test module records, which a proxy links its files to. */
    private static dev.granary.core.storage.StorageService.ComponentCoordinates component(String version) {
        return new dev.granary.core.storage.StorageService.ComponentCoordinates("example.com/acme", "hello", version);
    }
}
