package dev.granary.format.pub;

import static dev.granary.format.pub.PubTestSupport.archive;
import static dev.granary.format.pub.PubTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
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
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.GranaryPrincipal;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PubFormatHandlerTest {

    @TempDir
    Path dataDir;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final PubTestSupport.FakeStorage storage = new PubTestSupport.FakeStorage();
    private final ProxyService proxyService = mock(ProxyService.class);
    private final GroupResolver groups = mock(GroupResolver.class);
    private final GranaryPrincipal admin = PubTestSupport.admin();
    private PubFormatHandler handler;
    private Repository hosted;
    private Repository proxied;
    private Repository group;

    @BeforeEach
    void setUp() {
        GranaryProperties properties = new GranaryProperties();
        properties.setDataDir(dataDir);
        PubHosted pubHosted = new PubHosted(storage.mock, json);
        PubProxy proxy = new PubProxy(proxyService, storage.mock, new UpstreamClient(properties), json);
        PubResolver resolver = new PubResolver(pubHosted, proxy, groups);
        handler = new PubFormatHandler(storage.mock, resolver, pubHosted, new PubUploads(pubHosted, json, properties), groups, json);
        hosted = PubTestSupport.repository("pub-hosted", RepositoryType.HOSTED);
        proxied = PubTestSupport.repository("pub-dev", RepositoryType.PROXY);
        group = PubTestSupport.repository("pub-group", RepositoryType.GROUP);
        group.setMembers(List.of("pub-hosted", "pub-dev"));
        when(groups.leafMembers(group)).thenReturn(List.of(hosted, proxied));
        when(proxyService.fetch(any(), anyString(), any(FetchOptions.class), anyMap()))
                .thenAnswer(inv -> storage.find(inv.getArgument(0), inv.getArgument(1)));
        when(proxyService.fetch(any(), anyString(), any(URI.class), any(), anyMap()))
                .thenAnswer(inv -> storage.find(inv.getArgument(0), inv.getArgument(1)));
        when(proxyService.status(any())).thenReturn(new UpstreamHealth.Status(true, 0, null, Instant.now(), null, null));
    }

    private ResponseEntity<?> handle(Repository repo, String method, String path) throws IOException {
        return handler.handle(request(repo, method, path, admin));
    }

    private ResponseEntity<?> handle(Repository repo, String method, String path, String body) throws IOException {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        http.setContent(body.getBytes(StandardCharsets.UTF_8));
        http.setContentType("application/json");
        return handler.handle(request(repo, http, path, admin));
    }

    private JsonNode body(ResponseEntity<?> response) {
        return json.readTree((byte[]) response.getBody());
    }

    private static byte[] bytes(ResponseEntity<?> response) throws IOException {
        try (InputStream in = ((Resource) response.getBody()).getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** The three requests of dart pub publish. */
    private ResponseEntity<?> publish(Repository repo, byte[] archive, GranaryPrincipal principal) throws IOException {
        ResponseEntity<?> start = handler.handle(request(repo, "GET", "api/packages/versions/new", principal));
        if (!start.getStatusCode().is2xxSuccessful()) {
            return start;
        }
        String url = body(start).get("url").stringValue();
        assertThat(url).isEqualTo(PubTestSupport.baseUrl(repo) + "/api/packages/versions/newUpload");
        MockMultipartHttpServletRequest upload = new MockMultipartHttpServletRequest();
        upload.setMethod("POST");
        upload.addFile(new MockMultipartFile("file", "package.tar.gz", "application/octet-stream", archive));
        ResponseEntity<?> uploaded = handler.handle(request(repo, upload, "api/packages/versions/newUpload", principal));
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        URI location = uploaded.getHeaders().getLocation();
        assertThat(location).isNotNull();
        assertThat(location.toString()).startsWith(PubTestSupport.baseUrl(repo) + "/api/packages/versions/newUploadFinish?id=");
        MockHttpServletRequest finish = new MockHttpServletRequest("GET", location.getPath());
        finish.setParameter("id", location.getQuery().substring(3));
        return handler.handle(request(repo, finish, "api/packages/versions/newUploadFinish", principal));
    }

    // --- SPI surface ---------------------------------------------------------------------------------

    @Test
    void describesTheFormat() {
        assertThat(handler.format()).isEqualTo("pub");
        assertThat(handler.displayName()).isEqualTo("pub (Dart / Flutter)");
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder(RepositoryType.values());
    }

    @Test
    void offersTheStandardRepositories() {
        List<RepositoryTemplate> templates = handler.templates();
        assertThat(templates).extracting(RepositoryTemplate::name).containsExactly("pub-dev", "pub-hosted", "pub-group");
        assertThat(templates).allMatch(t -> t.format().equals("pub"));
        assertThat(templates.get(0).type()).isEqualTo(RepositoryType.PROXY);
        assertThat(templates.get(0).remoteUrl()).isEqualTo("https://pub.dev/");
        assertThat(templates.get(1).type()).isEqualTo(RepositoryType.HOSTED);
        assertThat(templates.get(2).type()).isEqualTo(RepositoryType.GROUP);
        assertThat(templates.get(2).members()).containsExactly("pub-hosted", "pub-dev");
    }

    @Test
    void declaresItsSettings() {
        List<SettingDescriptor> settings = handler.settings();
        assertThat(settings).extracting(SettingDescriptor::key).containsExactly("pub.maxArchiveSizeMb", "pub.advisories");
        assertThat(settings.get(0).type()).isEqualTo(SettingDescriptor.Type.INTEGER);
        assertThat(settings.get(0).defaultValue()).isEqualTo("100");
        assertThat(settings.get(0).appliesTo()).containsExactly(RepositoryType.HOSTED);
        assertThat(settings.get(1).type()).isEqualTo(SettingDescriptor.Type.BOOLEAN);
        assertThat(settings.get(1).appliesTo()).containsExactly(RepositoryType.PROXY);
    }

    @Test
    void validatesItsSettings() {
        hosted.setFormatSettings(Map.of(PubSettings.MAX_ARCHIVE_SIZE, "250", PubSettings.ADVISORIES, "no"));
        handler.validate(hosted);
        for (String bad : new String[] {"0", "-1", "abc", "1.5", "99999"}) {
            hosted.setFormatSettings(Map.of(PubSettings.MAX_ARCHIVE_SIZE, bad));
            assertThatThrownBy(() -> handler.validate(hosted)).as(bad).isInstanceOf(GranaryException.class)
                    .satisfies(e -> assertThat(((GranaryException) e).code()).isEqualTo("repository.pub.maxArchiveSizeMb.invalid"));
        }
        proxied.setFormatSettings(Map.of(PubSettings.ADVISORIES, "maybe"));
        assertThatThrownBy(() -> handler.validate(proxied)).isInstanceOf(GranaryException.class);
    }

    @Test
    void onlyArchivesAreImmutable() {
        assertThat(handler.isMetadataPath("api/packages/path")).isTrue();
        assertThat(handler.isMetadataPath("api/packages/path/advisories")).isTrue();
        assertThat(handler.isMetadataPath("packages/path/versions/1.9.1.tar.gz")).isFalse();
    }

    // --- access ----------------------------------------------------------------------------------------

    @Test
    void entitledCallersNeedTheNaturalAccessLevel() {
        GranaryPrincipal reader = PubTestSupport.user("reader", "repository:read:*");
        assertThat(handler.requiredAccess(request(hosted, "GET", "api/packages/x", reader))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "HEAD", "packages/x/versions/1.0.0.tar.gz", reader))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "GET", "api/packages/versions/new", admin))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hosted, "GET", "api/packages/versions/newUploadFinish", admin))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hosted, "POST", "api/packages/versions/newUpload", admin))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hosted, "DELETE", "api/packages/x", admin))).isEqualTo(AccessLevel.WRITE);
        assertThat(PubFormatHandler.accessFor(PubPath.parse("api/packages/x/options"), "PUT")).isEqualTo(AccessLevel.WRITE);
        assertThat(PubFormatHandler.accessFor(PubPath.parse("api/packages/x/options"), "GET")).isEqualTo(AccessLevel.READ);
    }

    @Test
    void callersWithoutTheAccessArePassedOnSoThePubChallengeCanBeSent() {
        GranaryPrincipal reader = PubTestSupport.user("reader", "repository:read:*");
        assertThat(handler.requiredAccess(request(hosted, "GET", "api/packages/versions/new", reader))).isEqualTo(AccessLevel.NONE);
        assertThat(handler.requiredAccess(request(hosted, "GET", "api/packages/x", PubTestSupport.anonymous(false))))
                .isEqualTo(AccessLevel.NONE);
        assertThat(handler.requiredAccess(request(hosted, "GET", "api/packages/x", PubTestSupport.anonymous(true))))
                .isEqualTo(AccessLevel.READ);
    }

    @Test
    void anonymousCallersGetTheBearerChallenge() throws IOException {
        ResponseEntity<?> response = handler.handle(request(hosted, "GET", "api/packages/x", PubTestSupport.anonymous(false)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer realm=\"pub\", message=\"Granary repository 'pub-hosted' needs an API token to read it.\"");
        assertThat(response.getBody().toString()).contains("\"code\":\"MissingAuthentication\"");

        ResponseEntity<?> publish = handler.handle(request(hosted, "GET", "api/packages/versions/new", PubTestSupport.anonymous(true)));
        assertThat(publish.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(publish.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("to publish to or change it");
    }

    @Test
    void authenticatedCallersWithoutThePrivilegeGetForbidden() throws IOException {
        GranaryPrincipal reader = PubTestSupport.user("reader", "repository:read:pub-hosted");
        ResponseEntity<?> response = handler.handle(request(hosted, "POST", "api/packages/versions/newUpload", reader));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Bearer realm=\"pub\", message=\"User 'reader' may not publish to or change");
        assertThat(response.getBody().toString()).contains("InsufficientPermissions");
        ResponseEntity<?> other = handler.handle(request(group, "GET", "api/packages/x", reader));
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- publishing and reading back -------------------------------------------------------------------

    @Test
    void publishesAndServesAPackage() throws IOException {
        byte[] archive = archive("demo", "1.0.0");
        ResponseEntity<?> finish = publish(hosted, archive, admin);
        assertThat(finish.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(finish).get("success").get("message").stringValue()).contains("demo version 1.0.0");

        ResponseEntity<?> listing = handle(hosted, "GET", "api/packages/demo");
        assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listing.getHeaders().getContentType().toString()).isEqualTo("application/vnd.pub.v2+json;charset=utf-8");
        JsonNode doc = body(listing);
        assertThat(doc.get("name").stringValue()).isEqualTo("demo");
        assertThat(doc.get("latest").get("archive_url").stringValue())
                .isEqualTo("http://granary.test/repository/pub-hosted/packages/demo/versions/1.0.0.tar.gz");
        assertThat(doc.get("latest").get("archive_sha256").stringValue()).isEqualTo(PubTestSupport.sha256(archive));
        assertThat(doc.has("advisoriesUpdated")).isFalse();

        JsonNode version = body(handle(hosted, "GET", "api/packages/demo/versions/1.0.0"));
        assertThat(version.get("version").stringValue()).isEqualTo("1.0.0");
        assertThat(version.get("pubspec").get("name").stringValue()).isEqualTo("demo");

        ResponseEntity<?> download = handle(hosted, "GET", "packages/demo/versions/1.0.0.tar.gz");
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bytes(download)).isEqualTo(archive);
        assertThat(download.getHeaders().getETag()).isEqualTo("\"" + PubTestSupport.sha256(archive) + "\"");
        assertThat(download.getHeaders().getContentType()).hasToString("application/gzip");
        assertThat(storage.downloads).hasSize(1);

        ResponseEntity<?> head = handle(hosted, "HEAD", "packages/demo/versions/1.0.0.tar.gz");
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isEqualTo(archive.length);
        assertThat(storage.downloads).hasSize(1);

        assertThat(body(handle(hosted, "GET", "api/packages/demo/advisories")).toString())
                .isEqualTo("{\"advisories\":[],\"advisoriesUpdated\":null}");
    }

    @Test
    void listingsSupportConditionalRequestsAndHead() throws IOException {
        publish(hosted, archive("demo", "1.0.0"), admin);
        ResponseEntity<?> first = handle(hosted, "GET", "api/packages/demo");
        String etag = first.getHeaders().getETag();
        assertThat(etag).isNotBlank();
        MockHttpServletRequest conditional = new MockHttpServletRequest("GET", "/x");
        conditional.addHeader("If-None-Match", etag);
        assertThat(handler.handle(request(hosted, conditional, "api/packages/demo", admin)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_MODIFIED);
        ResponseEntity<?> head = handle(hosted, "HEAD", "api/packages/demo");
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isEqualTo(((byte[]) first.getBody()).length);
        // a new version changes the ETag
        publish(hosted, archive("demo", "1.1.0"), admin);
        assertThat(handle(hosted, "GET", "api/packages/demo").getHeaders().getETag()).isNotEqualTo(etag);
    }

    @Test
    void missingThingsAreNotFoundInPubShape() throws IOException {
        ResponseEntity<?> pkg = handle(hosted, "GET", "api/packages/nope");
        assertThat(pkg.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(pkg).get("error").get("code").stringValue()).isEqualTo("NotFound");
        assertThat(body(pkg).get("error").get("message").stringValue()).contains("nope");
        publish(hosted, archive("demo", "1.0.0"), admin);
        assertThat(handle(hosted, "GET", "api/packages/demo/versions/2.0.0").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handle(hosted, "GET", "packages/demo/versions/2.0.0.tar.gz").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handle(hosted, "GET", "api/packages/nope/advisories").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handle(hosted, "GET", "something/else").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void malformedNamesAndVersionsAreBadRequests() throws IOException {
        for (String path : new String[] {"api/packages/Not-Valid", "api/packages/x/versions/1.0", "packages/x/versions/abc.tar.gz"}) {
            ResponseEntity<?> response = handle(hosted, "GET", path);
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body(response).get("error").get("code").stringValue()).isEqualTo("InvalidInput");
        }
    }

    @Test
    void wrongMethodsAreRefusedWithTheAllowedOnes() throws IOException {
        ResponseEntity<?> archive = handle(hosted, "PUT", "packages/x/versions/1.0.0.tar.gz");
        assertThat(archive.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(archive.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.HEAD);
        ResponseEntity<?> pkg = handle(hosted, "POST", "api/packages/x");
        assertThat(pkg.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.DELETE);
        ResponseEntity<?> retract = handle(hosted, "GET", "api/packages/x/versions/1.0.0/retract");
        assertThat(retract.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE);
        assertThat(handle(hosted, "GET", "api/packages/versions/newUpload").getHeaders().getAllow())
                .containsExactlyInAnyOrder(HttpMethod.POST, HttpMethod.PUT);
        assertThat(handle(hosted, "POST", "api/packages/versions/new").getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handle(hosted, "POST", "").getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body(pkg).get("error").get("code").stringValue()).isEqualTo("MethodNotAllowed");
    }

    @Test
    void theRootDescribesTheRepository() throws IOException {
        JsonNode info = body(handle(group, "GET", ""));
        assertThat(info.get("format").stringValue()).isEqualTo("pub");
        assertThat(info.get("type").stringValue()).isEqualTo("group");
        assertThat(info.get("usage").stringValue()).isEqualTo("export PUB_HOSTED_URL=http://granary.test/repository/pub-group");
    }

    // --- read-only repositories ----------------------------------------------------------------------

    @Test
    void groupsRefusePublishingAndNameTheirHostedMember() throws IOException {
        ResponseEntity<?> start = handle(group, "GET", "api/packages/versions/new");
        assertThat(start.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(start).get("error").get("message").stringValue())
                .contains("'pub-group' is a group repository")
                .contains("publish_to: http://granary.test/repository/pub-hosted");
        ResponseEntity<?> delete = handle(group, "DELETE", "api/packages/x/versions/1.0.0");
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(delete.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.HEAD);

        when(groups.leafMembers(group)).thenReturn(List.of(proxied));
        assertThat(body(handle(group, "GET", "api/packages/versions/new")).get("error").get("message").stringValue())
                .contains("without a hosted member");
    }

    @Test
    void proxiesRefuseChanges() throws IOException {
        ResponseEntity<?> start = handle(proxied, "GET", "api/packages/versions/new");
        assertThat(start.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(start).get("error").get("message").stringValue()).contains("is a proxy of https://pub.dev/");
        assertThat(handle(proxied, "POST", "api/packages/x/versions/1.0.0/retract").getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handle(proxied, "PUT", "api/packages/x/options", "{\"isDiscontinued\":true}").getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    // --- management ------------------------------------------------------------------------------------

    @Test
    void deletesVersionsAndPackages() throws IOException {
        publish(hosted, archive("demo", "1.0.0"), admin);
        publish(hosted, archive("demo", "1.1.0"), admin);
        ResponseEntity<?> deleted = handle(hosted, "DELETE", "api/packages/demo/versions/1.0.0");
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(handle(hosted, "GET", "packages/demo/versions/1.0.0.tar.gz").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handle(hosted, "DELETE", "api/packages/demo/versions/1.0.0").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(handle(hosted, "DELETE", "api/packages/demo")).get("success").get("message").stringValue())
                .isEqualTo("Deleted package demo (1 version).");
        assertThat(handle(hosted, "GET", "api/packages/demo").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handle(hosted, "DELETE", "api/packages/demo").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void retractsAndRestoresVersions() throws IOException {
        publish(hosted, archive("demo", "1.0.0"), admin);
        publish(hosted, archive("demo", "1.1.0"), admin);
        assertThat(handle(hosted, "POST", "api/packages/demo/versions/1.1.0/retract").getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode listing = body(handle(hosted, "GET", "api/packages/demo"));
        assertThat(listing.get("versions").get(1).get("retracted").booleanValue()).isTrue();
        assertThat(listing.get("latest").get("version").stringValue()).isEqualTo("1.0.0");
        assertThat(body(handle(hosted, "GET", "api/packages/demo/versions/1.1.0/options")).get("isRetracted").booleanValue()).isTrue();
        assertThat(handle(hosted, "DELETE", "api/packages/demo/versions/1.1.0/retract").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(handle(hosted, "GET", "api/packages/demo")).get("latest").get("version").stringValue()).isEqualTo("1.1.0");

        JsonNode options = body(handle(hosted, "PUT", "api/packages/demo/versions/1.0.0/options", "{\"isRetracted\":true}"));
        assertThat(options.get("isRetracted").booleanValue()).isTrue();
        assertThat(handle(hosted, "PUT", "api/packages/demo/versions/1.0.0/options", "{\"isRetracted\":\"yes\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handle(hosted, "POST", "api/packages/demo/versions/9.9.9/retract").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void discontinuesPackages() throws IOException {
        publish(hosted, archive("demo", "1.0.0"), admin);
        JsonNode options = body(handle(hosted, "PUT", "api/packages/demo/options", "{\"isDiscontinued\":true,\"replacedBy\":\"demo2\"}"));
        assertThat(options.get("isDiscontinued").booleanValue()).isTrue();
        assertThat(options.get("replacedBy").stringValue()).isEqualTo("demo2");
        assertThat(options.get("isUnlisted").booleanValue()).isFalse();
        JsonNode listing = body(handle(hosted, "GET", "api/packages/demo"));
        assertThat(listing.get("isDiscontinued").booleanValue()).isTrue();
        assertThat(listing.get("replacedBy").stringValue()).isEqualTo("demo2");

        JsonNode lifted = body(handle(hosted, "PUT", "api/packages/demo/options", "{\"isDiscontinued\":false,\"replacedBy\":null}"));
        assertThat(lifted.get("isDiscontinued").booleanValue()).isFalse();
        assertThat(lifted.get("replacedBy").isNull()).isTrue();

        for (String bad : new String[] {"{}", "[]", "not json", "{\"isDiscontinued\":true,\"replacedBy\":5}",
                "{\"isDiscontinued\":true,\"replacedBy\":\"no way\"}"}) {
            assertThat(handle(hosted, "PUT", "api/packages/demo/options", bad).getStatusCode()).as(bad)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(handle(hosted, "PUT", "api/packages/demo/options", "{\"isDiscontinued\":true," + "\"x\":\""
                + "a".repeat(70_000) + "\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handle(hosted, "PUT", "api/packages/nope/options", "{\"isDiscontinued\":true}").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void duplicatesAndBrokenUploadsAreReportedByFinish() throws IOException {
        assertThat(publish(hosted, archive("demo", "1.0.0"), admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<?> duplicate = publish(hosted, archive("demo", "1.0.0"), admin);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(duplicate).get("error").get("message").stringValue()).contains("already exists");
        ResponseEntity<?> broken = publish(hosted, "nope".getBytes(StandardCharsets.UTF_8), admin);
        assertThat(broken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void groupsServeHostedPackagesWithGroupArchiveUrls() throws IOException {
        byte[] archive = archive("demo", "1.0.0");
        publish(hosted, archive, admin);
        JsonNode listing = body(handle(group, "GET", "api/packages/demo"));
        assertThat(listing.get("latest").get("archive_url").stringValue())
                .isEqualTo("http://granary.test/repository/pub-group/packages/demo/versions/1.0.0.tar.gz");
        assertThat(bytes(handle(group, "GET", "packages/demo/versions/1.0.0.tar.gz"))).isEqualTo(archive);
        assertThat(body(handle(group, "GET", "api/packages/demo/versions/1.0.0")).get("archive_url").stringValue())
                .startsWith("http://granary.test/repository/pub-group/");
    }

    @Test
    void upstreamChecksumMismatchesAreBadGateways() throws IOException {
        storage.put(proxied, "api/packages/x", ("{\"name\":\"x\",\"versions\":[{\"version\":\"1.0.0\",\"pubspec\":{},"
                + "\"archive_url\":\"https://pub.dev/api/archives/x-1.0.0.tar.gz\",\"archive_sha256\":\"" + "0".repeat(64)
                + "\"}]}").getBytes(StandardCharsets.UTF_8), "application/json", true);
        when(proxyService.fetch(any(), anyString(), any(URI.class), any(), anyMap())).thenAnswer(inv ->
                Optional.of(storage.put(proxied, "packages/x/versions/1.0.0.tar.gz", new byte[] {1, 2, 3}, "application/gzip", false)));
        ResponseEntity<?> response = handle(proxied, "GET", "packages/x/versions/1.0.0.tar.gz");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(body(response).get("error").get("code").stringValue()).isEqualTo("UpstreamError");
    }
}
