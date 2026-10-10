package dev.granary.format.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.GranaryException;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ComposerFormatHandlerTest {

    private static final String SERVER = "http://granary.test";

    @TempDir
    Path dataDir;

    private final ComposerTestSupport.FakeStorage storage = new ComposerTestSupport.FakeStorage();
    private final ProxyService proxyService = mock(ProxyService.class);
    private final UpstreamClient upstream = mock(UpstreamClient.class);
    private final GroupResolver groups = mock(GroupResolver.class);
    private ComposerFormatHandler handler;
    private Repository hosted;
    private Repository proxied;
    private Repository group;

    @BeforeEach
    void setUp() {
        GranaryProperties properties = new GranaryProperties();
        properties.setDataDir(dataDir);
        ComposerHosted composerHosted = new ComposerHosted(storage.mock, ComposerTestSupport.JSON);
        ComposerProxy proxy = new ComposerProxy(proxyService, storage.mock, upstream, new UpstreamHealth(properties),
                new NegativeCache(), ComposerTestSupport.JSON);
        ComposerMetadata metadata = new ComposerMetadata(storage.mock, composerHosted, proxy, groups);
        handler = new ComposerFormatHandler(storage.mock, composerHosted, metadata, ComposerTestSupport.JSON, properties);

        hosted = ComposerTestSupport.repository("composer-hosted", RepositoryType.HOSTED);
        proxied = ComposerTestSupport.repository("packagist", RepositoryType.PROXY);
        proxied.setRemoteUrl("https://repo.packagist.org/");
        group = ComposerTestSupport.repository("composer-group", RepositoryType.GROUP);
        group.setMembers(List.of("packagist", "composer-hosted"));
        when(groups.leafMembers(group)).thenReturn(List.of(proxied, hosted));

        when(proxyService.fetch(any(), any(), any(URI.class), any(), any())).thenReturn(Optional.empty());
        when(proxyService.status(any())).thenReturn(new UpstreamHealth.Status(true, 0, null, null, null, null));
        when(proxyService.metadataMaxAge(any())).thenReturn(Duration.ofMinutes(30));
        when(proxyService.negativeTtl(any())).thenReturn(Duration.ofMinutes(5));
    }

    // --- identity ----------------------------------------------------------------------------------

    @Test
    void identifiesItselfAndItsTemplates() {
        assertThat(handler.format()).isEqualTo("composer");
        assertThat(handler.displayName()).isEqualTo("Composer (Packagist)");
        assertThat(handler.templates()).extracting(RepositoryTemplate::name).containsExactly("packagist", "composer-hosted", "composer-group");
        assertThat(handler.templates().get(0).remoteUrl()).isEqualTo("https://repo.packagist.org/");
        assertThat(handler.templates().get(2).members()).containsExactly("composer-hosted", "packagist");
    }

    @Test
    void offersTheDistRewriteSettingForProxies() {
        assertThat(handler.settings()).singleElement().satisfies(s -> {
            assertThat(s.key()).isEqualTo("composer.distRewrite");
            assertThat(s.type()).isEqualTo(SettingDescriptor.Type.BOOLEAN);
            assertThat(s.defaultValue()).isEqualTo("true");
        });
    }

    @Test
    void validatesTheDistRewriteSetting() {
        proxied.setFormatSettings(Map.of("composer.distRewrite", "false"));
        handler.validate(proxied);
        proxied.setFormatSettings(Map.of("composer.distRewrite", "sometimes"));
        assertThatThrownBy(() -> handler.validate(proxied)).isInstanceOf(GranaryException.class);
    }

    @Test
    void readsAreReadsEvenWhenPosted() {
        assertThat(handler.requiredAccess(request(hosted, "POST", "downloads"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "POST", "api/security-advisories"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "GET", "search.json"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "GET", "p2/acme/lib.json"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "PUT", "packages/acme/lib/1.0.0"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hosted, "POST", "packages"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hosted, "DELETE", "packages/acme/lib/1.0.0"))).isEqualTo(AccessLevel.WRITE);
    }

    @Test
    void metadataPathsAreTheIndexDocuments() {
        assertThat(handler.isMetadataPath("packages.json")).isTrue();
        assertThat(handler.isMetadataPath("p2/psr/log.json")).isTrue();
        assertThat(handler.isMetadataPath("dist/psr/log/3.0.2/abc.zip")).isFalse();
    }

    // --- hosted ------------------------------------------------------------------------------------

    @Test
    void hostedRootAnnouncesMetadataUrlAndAvailablePackages() throws IOException {
        put(hosted, "packages/acme/lib/1.0.0", lib("acme/lib", null));

        ObjectNode root = json(handler.handle(request(hosted, "GET", "packages.json")));

        String base = SERVER + "/repository/composer-hosted";
        assertThat(root.get("packages").isArray()).isTrue();
        assertThat(root.get("metadata-url").stringValue()).isEqualTo(base + "/p2/%package%.json");
        assertThat(root.get("notify-batch").stringValue()).isEqualTo(base + "/downloads");
        assertThat(root.get("search").stringValue()).isEqualTo(base + "/search.json?q=%query%&type=%type%");
        assertThat(root.get("list").stringValue()).isEqualTo(base + "/list.json");
        assertThat(root.get("providers-api").stringValue()).isEqualTo(base + "/providers/%package%.json");
        assertThat(root.get("available-packages")).extracting(JsonNode::stringValue).containsExactly("acme/lib");
        assertThat(json(handler.handle(request(hosted, "GET", "")))).isEqualTo(root);
    }

    @Test
    void uploadsAndServesAPackage() throws IOException {
        byte[] zip = lib("acme/lib", null);

        ResponseEntity<?> created = put(hosted, "packages/acme/lib/1.0.0", zip);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(SERVER + "/repository/composer-hosted/p2/acme/lib.json");
        ObjectNode result = json(created);
        String dist = SERVER + "/repository/composer-hosted/dist/acme/lib/1.0.0/" + ComposerTestSupport.sha1(zip) + ".zip";
        assertThat(result.get("dist").stringValue()).isEqualTo(dist);
        assertThat(result.get("version_normalized").stringValue()).isEqualTo("1.0.0.0");

        ResponseEntity<?> p2 = handler.handle(request(hosted, "GET", "p2/acme/lib.json"));
        assertThat(p2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(p2.getHeaders().getETag()).isNotBlank();
        ObjectNode doc = json(p2);
        assertThat(doc.get("minified").stringValue()).isEqualTo("composer/2.0");
        JsonNode entry = doc.get("packages").get("acme/lib").get(0);
        assertThat(entry.get("dist").get("url").stringValue()).isEqualTo(dist);

        ResponseEntity<?> archive = handler.handle(request(hosted, "GET", dist.substring((SERVER + "/repository/composer-hosted/").length())));
        assertThat(archive.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bytes(archive)).isEqualTo(zip);
        assertThat(archive.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment");
        assertThat(storage.downloads).hasSize(1);
    }

    @Test
    void answersConditionalRequestsWithNotModified() throws IOException {
        put(hosted, "packages/acme/lib/1.0.0", lib("acme/lib", null));
        String etag = handler.handle(request(hosted, "GET", "p2/acme/lib.json")).getHeaders().getETag();

        MockHttpServletRequest http = http(hosted, "GET", "p2/acme/lib.json");
        http.addHeader(HttpHeaders.IF_NONE_MATCH, etag);

        assertThat(handler.handle(new RepositoryRequest(hosted, "p2/acme/lib.json", http, GranaryPrincipal.anonymous(Set.of()),
                base(hosted))).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void headHasNoBody() throws IOException {
        put(hosted, "packages/acme/lib/1.0.0", lib("acme/lib", null));
        ResponseEntity<?> head = handler.handle(request(hosted, "HEAD", "p2/acme/lib.json"));
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
    }

    @Test
    void unknownPackagesAreNotFound() throws IOException {
        assertThat(handler.handle(request(hosted, "GET", "p2/acme/missing.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "p2/acme/missing~dev.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "dist/acme/missing/1.0.0/abc.zip")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void uploadsAsMultipartForm() throws IOException {
        byte[] zip = lib("acme/form", null);
        MockMultipartHttpServletRequest http = new MockMultipartHttpServletRequest();
        http.setMethod("POST");
        http.setRequestURI("/repository/composer-hosted/packages");
        http.setQueryString("version=2.0.0");
        http.addFile(new MockMultipartFile("package", "form.zip", "application/zip", zip));

        ResponseEntity<?> response = handler.handle(new RepositoryRequest(hosted, "packages", http,
                GranaryPrincipal.anonymous(Set.of()), base(hosted)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(response).get("name").stringValue()).isEqualTo("acme/form");
        assertThat(json(response).get("version").stringValue()).isEqualTo("2.0.0");
    }

    @Test
    void multipartVersionMayBeAFormField() throws IOException {
        MockMultipartHttpServletRequest http = new MockMultipartHttpServletRequest();
        http.setMethod("POST");
        http.addFile(new MockMultipartFile("file", "x.zip", "application/zip", lib("acme/field", null)));
        http.addParameter("version", "3.1.4");

        ResponseEntity<?> response = handler.handle(new RepositoryRequest(hosted, "packages", http,
                GranaryPrincipal.anonymous(Set.of()), base(hosted)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(response).get("version").stringValue()).isEqualTo("3.1.4");
    }

    @Test
    void rejectsEmptyAndBrokenUploads() throws IOException {
        assertThat(put(hosted, "packages/acme/lib/1.0.0", new byte[0]).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<?> broken = put(hosted, "packages/acme/lib/1.0.0", "garbage".getBytes(StandardCharsets.UTF_8));
        assertThat(broken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(broken.getBody())).contains("composer.invalidPackage");
    }

    @Test
    void duplicateVersionsConflict() throws IOException {
        put(hosted, "packages/acme/lib/1.0.0", lib("acme/lib", null));
        ResponseEntity<?> again = put(hosted, "packages/acme/lib/1.0.0", lib("acme/lib", "\"homepage\": \"x\""));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void deletesVersionsAndPackages() throws IOException {
        put(hosted, "packages/acme/lib/1.0.0", lib("acme/lib", null));
        put(hosted, "packages/acme/lib/1.1.0", lib("acme/lib", "\"homepage\": \"x\""));

        assertThat(handler.handle(request(hosted, "DELETE", "packages/acme/lib/1.0.0")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handler.handle(request(hosted, "DELETE", "packages/acme/lib/1.0.0")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "DELETE", "packages/acme/lib")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handler.handle(request(hosted, "GET", "p2/acme/lib.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "DELETE", "packages/acme/lib")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void searchesListsAndFindsProvidersInHostedPackages() throws IOException {
        put(hosted, "packages/acme/logger/1.0.0", lib("acme/logger", "\"provide\": {\"psr/log-implementation\": \"3.0\"}"));
        put(hosted, "packages/acme/http/1.0.0", ComposerTestSupport.rootZip(
                "{\"name\":\"acme/http\",\"description\":\"HTTP client\",\"type\":\"library\",\"keywords\":[\"psr-18\"]}"));
        put(hosted, "packages/other/tool/1.0.0", ComposerTestSupport.rootZip(
                "{\"name\":\"other/tool\",\"description\":\"A tool\",\"type\":\"composer-plugin\"}"));

        ObjectNode search = json(handler.handle(request(hosted, "GET", "search.json", "q=acme")));
        assertThat(search.get("results")).extracting(r -> r.get("name").stringValue()).containsExactlyInAnyOrder("acme/logger", "acme/http");
        assertThat(search.get("total").intValue()).isEqualTo(2);
        assertThat(search.get("results").get(0).get("url").stringValue()).startsWith(SERVER + "/repository/composer-hosted/p2/acme/");

        ObjectNode byKeyword = json(handler.handle(request(hosted, "GET", "search.json", "q=psr-18")));
        assertThat(byKeyword.get("results")).extracting(r -> r.get("name").stringValue()).containsExactly("acme/http");

        ObjectNode byType = json(handler.handle(request(hosted, "GET", "search.json", "q=&type=composer-plugin")));
        assertThat(byType.get("results")).extracting(r -> r.get("name").stringValue()).containsExactly("other/tool");

        ObjectNode paged = json(handler.handle(request(hosted, "GET", "search.json", "q=&per_page=1")));
        assertThat(paged.get("results")).hasSize(1);
        assertThat(paged.get("total").intValue()).isEqualTo(3);
        assertThat(paged.get("next").stringValue()).contains("page=2");

        ObjectNode list = json(handler.handle(request(hosted, "GET", "list.json")));
        assertThat(list.get("packageNames")).extracting(JsonNode::stringValue).containsExactly("acme/http", "acme/logger", "other/tool");
        ObjectNode filtered = json(handler.handle(request(hosted, "GET", "list.json", "filter=acme/*")));
        assertThat(filtered.get("packageNames")).extracting(JsonNode::stringValue).containsExactly("acme/http", "acme/logger");
        ObjectNode byVendor = json(handler.handle(request(hosted, "GET", "list.json", "vendor=other")));
        assertThat(byVendor.get("packageNames")).extracting(JsonNode::stringValue).containsExactly("other/tool");

        ObjectNode providers = json(handler.handle(request(hosted, "GET", "providers/psr/log-implementation.json")));
        assertThat(providers.get("providers")).extracting(r -> r.get("name").stringValue()).containsExactly("acme/logger");
    }

    @Test
    void acknowledgesInstallNotifications() throws IOException {
        MockHttpServletRequest http = http(hosted, "POST", "downloads");
        http.setContent("{\"downloads\":[{\"name\":\"acme/lib\",\"version\":\"1.0.0.0\"}]}".getBytes(StandardCharsets.UTF_8));
        ResponseEntity<?> response = handler.handle(new RepositoryRequest(hosted, "downloads", http, GranaryPrincipal.anonymous(Set.of()), base(hosted)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void refusesWrongMethodsAndBadPaths() throws IOException {
        assertThat(handler.handle(request(hosted, "DELETE", "p2/acme/lib.json")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "GET", "downloads")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "PUT", "packages.json")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "GET", "p2/../x.json")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handle(request(hosted, "GET", "nothing/here")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void groupsAndProxiesRefuseUploadsNamingTheHostedMember() throws IOException {
        ResponseEntity<?> toGroup = put(group, "packages/acme/lib/1.0.0", lib("acme/lib", null));
        assertThat(toGroup.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(String.valueOf(toGroup.getBody())).contains("composer-hosted");
        assertThat(put(proxied, "packages/acme/lib/1.0.0", lib("acme/lib", null)).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    // --- proxy -------------------------------------------------------------------------------------

    @Test
    void proxyRootPointsEverythingAtGranary() throws IOException {
        upstreamDocument("packages.json", ComposerTestSupport.resource("packagist-packages.json"));

        ObjectNode root = json(handler.handle(request(proxied, "GET", "packages.json")));

        String base = SERVER + "/repository/packagist";
        assertThat(root.get("metadata-url").stringValue()).isEqualTo(base + "/p2/%package%.json");
        assertThat(root.get("search").stringValue()).startsWith(base + "/search.json");
        assertThat(root.get("notify-batch").stringValue()).isEqualTo(base + "/downloads");
        assertThat(root.get("list").stringValue()).isEqualTo(base + "/list.json");
        assertThat(root.get("providers-api").stringValue()).isEqualTo(base + "/providers/%package%.json");
        assertThat(root.get("security-advisories").get("api-url").stringValue()).isEqualTo(base + "/api/security-advisories");
        assertThat(root.get("security-advisories").get("metadata").booleanValue()).isFalse();
        assertThat(root.has("providers-url")).isFalse();
        assertThat(root.has("provider-includes")).isFalse();
        assertThat(root.has("metadata-changes-url")).isFalse();
        assertThat(root.has("available-packages")).isFalse();
        assertThat(root.toString()).doesNotContain("packagist.org");
    }

    @Test
    void proxyRewritesDistUrls() throws IOException {
        upstreamDocument("packages.json", ComposerTestSupport.resource("packagist-packages.json"));
        upstreamDocument("p2/psr/log.json", ComposerTestSupport.resource("psr-log.json"));

        ObjectNode doc = json(handler.handle(request(proxied, "GET", "p2/psr/log.json")));

        JsonNode latest = doc.get("packages").get("psr/log").get(0);
        assertThat(latest.get("version").stringValue()).isEqualTo("3.0.2");
        assertThat(latest.get("dist").get("url").stringValue())
                .isEqualTo(SERVER + "/repository/packagist/dist/psr/log/3.0.2/f16e1d5863e37f8d8c2a01719f5b34baa2b714d3.zip");
        verify(proxyService).fetch(any(), eq("p2/psr/log.json"),
                argThat(uri -> uri.toString().equals("https://repo.packagist.org/p2/psr/log.json")), any(), any());
    }

    @Test
    void proxyWithoutRewriteKeepsUpstreamUrls() throws IOException {
        proxied.setFormatSettings(Map.of("composer.distRewrite", "false"));
        upstreamDocument("packages.json", ComposerTestSupport.resource("packagist-packages.json"));
        upstreamDocument("p2/psr/log.json", ComposerTestSupport.resource("psr-log.json"));

        ObjectNode doc = json(handler.handle(request(proxied, "GET", "p2/psr/log.json")));

        assertThat(doc.get("packages").get("psr/log").get(0).get("dist").get("url").stringValue()).startsWith("https://api.github.com/");
    }

    @Test
    void upstreamNotFoundIsNotFoundAndRemembered() throws IOException {
        upstreamDocument("packages.json", ComposerTestSupport.resource("packagist-packages.json"));

        assertThat(handler.handle(request(proxied, "GET", "p2/acme/nothing.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(storage.has(proxied, "absent/p2/acme/nothing.json")).isTrue();

        // later, offline: the remembered 404 is an empty document rather than "cannot tell"
        when(proxyService.isOffline(proxied)).thenReturn(true);
        ResponseEntity<?> offline = handler.handle(request(proxied, "GET", "p2/acme/nothing.json"));
        assertThat(offline.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(offline).get("packages").isEmpty()).isTrue();
    }

    @Test
    void offlineTheBranchesOfAPackageUpstreamDeniedAreAbsentToo() throws IOException {
        upstreamDocument("packages.json", ComposerTestSupport.resource("packagist-packages.json"));
        assertThat(handler.handle(request(proxied, "GET", "p2/acme/private.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(proxyService.isOffline(proxied)).thenReturn(true);

        // only the release document was ever asked for; its 404 answers for the ~dev document as well
        ResponseEntity<?> dev = handler.handle(request(proxied, "GET", "p2/acme/private~dev.json"));
        assertThat(dev.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(dev).get("packages").isEmpty()).isTrue();
        // but not the other way round, and not for packages nothing is known about
        assertThat(handler.handle(request(proxied, "GET", "p2/acme/other~dev.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void offlineAMissingBranchDocumentSaysNothingAboutReleases() throws IOException {
        upstreamDocument("packages.json", ComposerTestSupport.resource("packagist-packages.json"));
        assertThat(handler.handle(request(proxied, "GET", "p2/acme/private~dev.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        when(proxyService.isOffline(proxied)).thenReturn(true);

        assertThat(handler.handle(request(proxied, "GET", "p2/acme/private.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(proxied, "GET", "p2/acme/private~dev.json")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anUnreachableUpstreamIsNotRememberedAsAbsent() throws IOException {
        when(proxyService.status(proxied)).thenReturn(new UpstreamHealth.Status(true, 1, "timeout", null, null, null));
        assertThat(handler.handle(request(proxied, "GET", "p2/acme/nothing.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(storage.has(proxied, "absent/p2/acme/nothing.json")).isFalse();
    }

    @Test
    void cachedDistArchivesAreServedWithoutAskingUpstream() throws IOException {
        byte[] zip = ComposerTestSupport.rootZip("{\"name\":\"psr/log\"}");
        storage.put(proxied, "dist/psr/log/3.0.2/f16e1d5863e37f8d8c2a01719f5b34baa2b714d3.zip", zip, "application/zip", false);

        ResponseEntity<?> response = handler.handle(request(proxied, "GET", "dist/psr/log/3.0.2/f16e1d5863e37f8d8c2a01719f5b34baa2b714d3.zip"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bytes(response)).isEqualTo(zip);
        verify(proxyService, never()).fetch(any(), any(), any(URI.class), any(), any());
    }

    @Test
    void distArchivesOfUnknownVersionsAreNotFound() throws IOException {
        upstreamDocument("packages.json", ComposerTestSupport.resource("packagist-packages.json"));
        upstreamDocument("p2/psr/log.json", ComposerTestSupport.resource("psr-log.json"));

        assertThat(handler.handle(request(proxied, "GET", "dist/psr/log/3.0.2/0000000000000000000000000000000000000000.zip"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(proxied, "GET", "dist/psr/log/9.9.9/f16e1d5863e37f8d8c2a01719f5b34baa2b714d3.zip"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- group -------------------------------------------------------------------------------------

    @Test
    void groupMergesHostedFirstAndServesItsArchives() throws IOException {
        upstreamDocument("packages.json", ComposerTestSupport.resource("packagist-packages.json"));
        upstreamDocument("p2/psr/log.json", ComposerTestSupport.resource("psr-log.json"));
        // a private build of psr/log 3.0.2 shadows the public one; 9.0.0 exists only privately
        byte[] privateZip = ComposerTestSupport.rootZip("{\"name\":\"psr/log\",\"description\":\"private fork\"}");
        put(hosted, "packages/psr/log/3.0.2", privateZip);
        put(hosted, "packages/psr/log/9.0.0", ComposerTestSupport.rootZip("{\"name\":\"psr/log\",\"description\":\"newer\"}"));

        ObjectNode doc = json(handler.handle(request(group, "GET", "p2/psr/log.json")));

        JsonNode versions = doc.get("packages").get("psr/log");
        assertThat(versions).hasSize(13);
        List<ObjectNode> expanded = MetadataMinifier.expand(toList(versions));
        assertThat(expanded.get(0).get("version").stringValue()).isEqualTo("9.0.0");
        ObjectNode fork = expanded.get(1);
        assertThat(fork.get("version").stringValue()).isEqualTo("3.0.2");
        assertThat(fork.get("description").stringValue()).isEqualTo("private fork");
        String forkDist = fork.get("dist").get("url").stringValue();
        assertThat(forkDist).startsWith(SERVER + "/repository/composer-group/dist/psr/log/3.0.2/");
        assertThat(expanded.get(2).get("dist").get("url").stringValue()).startsWith(SERVER + "/repository/composer-group/dist/psr/log/3.0.1/");

        ResponseEntity<?> archive = handler.handle(request(group, "GET", forkDist.substring((SERVER + "/repository/composer-group/").length())));
        assertThat(archive.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bytes(archive)).isEqualTo(privateZip);
    }

    @Test
    void groupRootAnnouncesItsOwnApis() throws IOException {
        upstreamDocument("packages.json", ComposerTestSupport.resource("packagist-packages.json"));
        ObjectNode root = json(handler.handle(request(group, "GET", "packages.json")));
        String base = SERVER + "/repository/composer-group";
        assertThat(root.get("metadata-url").stringValue()).isEqualTo(base + "/p2/%package%.json");
        assertThat(root.get("security-advisories").get("api-url").stringValue()).isEqualTo(base + "/api/security-advisories");
        assertThat(root.has("available-packages")).isFalse();
    }

    @Test
    void groupMetadataOfAPackageNobodyHasIsNotFound() throws IOException {
        upstreamDocument("packages.json", ComposerTestSupport.resource("packagist-packages.json"));
        assertThat(handler.handle(request(group, "GET", "p2/acme/nothing.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- parameter parsing -------------------------------------------------------------------------

    @Test
    void readsAdvisoryPackageListsInEveryShapeComposerSends() throws IOException {
        MockHttpServletRequest query = new MockHttpServletRequest("GET", "/x");
        query.addParameter("packages[]", "psr/log", "Monolog/Monolog");
        assertThat(ComposerFormatHandler.advisoryPackages(query)).containsExactly("psr/log", "monolog/monolog");

        MockHttpServletRequest indexed = new MockHttpServletRequest("POST", "/x");
        indexed.addParameter("packages[0]", "psr/log");
        indexed.addParameter("packages[1]", "not a name");
        indexed.addParameter("other", "x/y");
        assertThat(ComposerFormatHandler.advisoryPackages(indexed)).containsExactly("psr/log");

        MockHttpServletRequest form = new MockHttpServletRequest("POST", "/x");
        form.setContentType("application/x-www-form-urlencoded");
        form.setContent("packages%5B0%5D=psr%2Flog&packages%5B1%5D=symfony%2Fhttp-kernel".getBytes(StandardCharsets.UTF_8));
        assertThat(ComposerFormatHandler.advisoryPackages(form)).containsExactly("psr/log", "symfony/http-kernel");
    }

    @Test
    void readsQueryParametersFromTheRawQueryString() {
        assertThat(ComposerFormatHandler.queryParameter("version=1.0.0&x=y", "version")).isEqualTo("1.0.0");
        assertThat(ComposerFormatHandler.queryParameter("version=dev-feature%2Fx", "version")).isEqualTo("dev-feature/x");
        assertThat(ComposerFormatHandler.queryParameter("version=", "version")).isNull();
        assertThat(ComposerFormatHandler.queryParameter(null, "version")).isNull();
        assertThat(ComposerFormatHandler.queryParameter("x=%zz&version=2", "version")).isEqualTo("2");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void upstreamDocument(String cachePath, byte[] body) {
        StoredContent content = storage.put(proxied, cachePath, body, "application/json", true);
        when(proxyService.fetch(any(), eq(cachePath), any(URI.class), any(), any())).thenReturn(Optional.of(content));
    }

    private static byte[] lib(String name, String extra) {
        return ComposerTestSupport.packageZip(ComposerTestSupport.composerJson(name, null, extra));
    }

    private ResponseEntity<?> put(Repository repo, String path, byte[] body) throws IOException {
        MockHttpServletRequest http = http(repo, "PUT", path);
        http.setContent(body);
        http.setContentType("application/zip");
        return handler.handle(new RepositoryRequest(repo, path, http, GranaryPrincipal.anonymous(Set.of()), base(repo)));
    }

    private static String base(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    private static MockHttpServletRequest http(Repository repo, String method, String path) {
        return new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
    }

    private RepositoryRequest request(Repository repo, String method, String path) {
        return new RepositoryRequest(repo, path, http(repo, method, path), GranaryPrincipal.anonymous(Set.of()), base(repo));
    }

    private RepositoryRequest request(Repository repo, String method, String path, String query) {
        MockHttpServletRequest http = http(repo, method, path);
        http.setQueryString(query);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            http.addParameter(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
        }
        return new RepositoryRequest(repo, path, http, GranaryPrincipal.anonymous(Set.of()), base(repo));
    }

    private static ObjectNode json(ResponseEntity<?> response) throws IOException {
        assertThat(response.getStatusCode().is2xxSuccessful()).as("status %s body %s", response.getStatusCode(), response.getBody()).isTrue();
        return ComposerTestSupport.parse(bytes(response));
    }

    private static byte[] bytes(ResponseEntity<?> response) throws IOException {
        Object body = response.getBody();
        if (body instanceof Resource resource) {
            return resource.getContentAsByteArray();
        }
        if (body instanceof byte[] b) {
            return b;
        }
        return String.valueOf(body).getBytes(StandardCharsets.UTF_8);
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new java.util.ArrayList<>();
        array.forEach(list::add);
        return list;
    }
}
