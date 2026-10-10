package dev.granary.format.conan;

import static dev.granary.format.conan.ConanTestSupport.CONANINFO;
import static dev.granary.format.conan.ConanTestSupport.MAPPER;
import static dev.granary.format.conan.ConanTestSupport.PID;
import static dev.granary.format.conan.ConanTestSupport.PREV;
import static dev.granary.format.conan.ConanTestSupport.PREV2;
import static dev.granary.format.conan.ConanTestSupport.RREV;
import static dev.granary.format.conan.ConanTestSupport.RREV2;
import static dev.granary.format.conan.ConanTestSupport.as;
import static dev.granary.format.conan.ConanTestSupport.body;
import static dev.granary.format.conan.ConanTestSupport.bodyBytes;
import static dev.granary.format.conan.ConanTestSupport.http;
import static dev.granary.format.conan.ConanTestSupport.json;
import static dev.granary.format.conan.ConanTestSupport.request;
import static dev.granary.format.conan.ConanTestSupport.tgz;
import static dev.granary.format.conan.ConanTestSupport.upload;
import static dev.granary.format.conan.ConanTestSupport.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.GranaryException;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.ApiToken;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.model.User;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.ApiTokenService;
import dev.granary.core.security.UserService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class ConanFormatHandlerTest {

    private static final String UPSTREAM = "https://center.example.org";
    private static final ConanReference ZLIB = new ConanReference("zlib", "1.3.1", null, null);
    private static final ConanReference ACME = new ConanReference("acme", "1.0", "team", "stable");
    private static final String TOKEN = "gry_" + "A".repeat(43);

    @TempDir
    Path dataDir;

    private final FakeStorage storage = new FakeStorage();
    private final FakeUpstream upstream = new FakeUpstream(storage);
    private final GroupResolver groups = mock(GroupResolver.class);
    private final ApiTokenService tokens = mock(ApiTokenService.class);
    private final UserService users = mock(UserService.class);

    private final Repository hostedRepo = ConanTestSupport.hosted("conan-hosted");
    private final Repository proxyRepo = ConanTestSupport.proxy("conancenter", UPSTREAM);
    private final Repository groupRepo = ConanTestSupport.group("conan-group", "conancenter", "conan-hosted");
    private ConanFormatHandler handler;

    @BeforeEach
    void setUp() {
        GranaryProperties properties = new GranaryProperties();
        properties.setDataDir(dataDir);
        handler = new ConanFormatHandler(storage.mock, upstream.proxy, upstream.client, groups, tokens, users, properties, MAPPER);
        // the proxy is listed first on purpose: hosted members must still take precedence
        when(groups.leafMembers(groupRepo)).thenReturn(List.of(proxyRepo, hostedRepo));
    }

    // --- identity, templates, settings, validation -------------------------------------------

    @Test
    void identity() {
        assertThat(handler.format()).isEqualTo("conan");
        assertThat(handler.displayName()).isEqualTo("Conan (C/C++)");
        assertThat(handler.supportedTypes()).isEqualTo(EnumSet.allOf(RepositoryType.class));
    }

    @Test
    void templatesProxyConanCenterAndGroupHostedFirst() {
        List<RepositoryTemplate> templates = handler.templates();
        assertThat(templates).extracting(RepositoryTemplate::name).containsExactly("conancenter", "conan-hosted", "conan-group");
        assertThat(templates).allSatisfy(t -> assertThat(t.format()).isEqualTo("conan"));
        assertThat(templates.get(0).type()).isEqualTo(RepositoryType.PROXY);
        assertThat(templates.get(0).remoteUrl()).isEqualTo("https://center2.conan.io");
        assertThat(templates.get(1).type()).isEqualTo(RepositoryType.HOSTED);
        assertThat(templates.get(2).type()).isEqualTo(RepositoryType.GROUP);
        assertThat(templates.get(2).members()).containsExactly("conan-hosted", "conancenter");
        assertThat(templates).allSatisfy(t -> assertThat(t.description()).isNotBlank());
    }

    @Test
    void settingsAreDeclared() {
        List<SettingDescriptor> settings = handler.settings();
        assertThat(settings).extracting(SettingDescriptor::key).containsExactly("conan.tokenTtlHours", "conan.upstreamAuth");
        assertThat(settings.get(0).type()).isEqualTo(SettingDescriptor.Type.INTEGER);
        assertThat(settings.get(0).defaultValue()).isEqualTo("24");
        assertThat(settings.get(0).appliesTo()).isEqualTo(EnumSet.allOf(RepositoryType.class));
        assertThat(settings.get(1).type()).isEqualTo(SettingDescriptor.Type.STRING);
        assertThat(settings.get(1).defaultValue()).isEqualTo("token");
        assertThat(settings.get(1).appliesTo()).containsExactly(RepositoryType.PROXY);
    }

    @Test
    void validation() {
        handler.validate(hostedRepo);
        hostedRepo.setFormatSettings(Map.of("conan.tokenTtlHours", "0"));
        handler.validate(hostedRepo);
        hostedRepo.setFormatSettings(Map.of("conan.tokenTtlHours", "720"));
        handler.validate(hostedRepo);
        hostedRepo.setFormatSettings(Map.of("conan.tokenTtlHours", " ")); // blank means "use the default"
        handler.validate(hostedRepo);
        for (String bad : List.of("-1", "abc", "1.5", "99999999999")) {
            hostedRepo.setFormatSettings(Map.of("conan.tokenTtlHours", bad));
            assertThatThrownBy(() -> handler.validate(hostedRepo)).as(bad).isInstanceOf(GranaryException.class);
        }
        proxyRepo.setFormatSettings(Map.of("conan.upstreamAuth", "Basic"));
        handler.validate(proxyRepo);
        proxyRepo.setFormatSettings(Map.of("conan.upstreamAuth", "token"));
        handler.validate(proxyRepo);
        proxyRepo.setFormatSettings(Map.of("conan.upstreamAuth", "oauth"));
        assertThatThrownBy(() -> handler.validate(proxyRepo)).isInstanceOf(GranaryException.class);
    }

    @Test
    void requiredAccess() {
        assertThat(handler.requiredAccess(request(hostedRepo, "GET", "v1/ping"))).isEqualTo(AccessLevel.NONE);
        assertThat(handler.requiredAccess(request(hostedRepo, "GET", "v2/users/authenticate"))).isEqualTo(AccessLevel.NONE);
        assertThat(handler.requiredAccess(request(hostedRepo, "GET", "v2/users/check_credentials"))).isEqualTo(AccessLevel.NONE);
        assertThat(handler.requiredAccess(request(hostedRepo, "GET", ConanRoutes.recipeLatest(ZLIB)))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "HEAD", ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py"))))
                .isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "PUT", ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py"))))
                .isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hostedRepo, "DELETE", ConanRoutes.recipeRevision(ZLIB, RREV))))
                .isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hostedRepo, "GET", "v2/conans/search"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hostedRepo, "GET", "../etc"))).isEqualTo(AccessLevel.READ);
    }

    @Test
    void metadataPaths() {
        assertThat(handler.isMetadataPath(ConanRoutes.recipeLatest(ZLIB))).isTrue();
        assertThat(handler.isMetadataPath(ConanRoutes.recipeRevisions(ZLIB))).isTrue();
        assertThat(handler.isMetadataPath(ConanRoutes.recipeFiles(ZLIB, RREV))).isTrue();
        assertThat(handler.isMetadataPath(ConanRoutes.packageSearch(ZLIB, RREV))).isTrue();
        assertThat(handler.isMetadataPath(ConanRoutes.search("zlib", true))).isTrue();
        assertThat(handler.isMetadataPath(ConanRoutes.recipeFile(ZLIB, RREV, "metadata/sign/x.sig"))).isTrue();
        assertThat(handler.isMetadataPath(ConanLayout.exportPath(ZLIB, RREV, "metadata/logs/a.log"))).isTrue();
        assertThat(handler.isMetadataPath(ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py"))).isFalse();
        assertThat(handler.isMetadataPath(ConanLayout.exportPath(ZLIB, RREV, "conan_export.tgz"))).isFalse();
        assertThat(handler.isMetadataPath(ConanLayout.packagePath(ZLIB, RREV, PID, PREV, "conan_package.tgz"))).isFalse();
        assertThat(handler.isMetadataPath("v1/ping")).isFalse();
    }

    // --- server endpoints ------------------------------------------------------------------

    @Test
    void pingAnnouncesTheCapabilitiesTheClientNeeds() throws Exception {
        ResponseEntity<?> ping = handler.handle(request(hostedRepo, "GET", "v1/ping"));
        assertThat(ping.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ping.getHeaders().getFirst("X-Conan-Server-Capabilities"))
                .isEqualTo("complex_search,checksum_deploy,revisions,only_v2");
        assertThat(handler.handle(request(groupRepo, "GET", "v2/ping")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(handler.handle(request(hostedRepo, "POST", "v1/ping")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void rootDescribesTheRepository() throws Exception {
        JsonNode info = json(handler.handle(request(groupRepo, "GET", "")));
        assertThat(info.path("name").stringValue()).isEqualTo("conan-group");
        assertThat(info.path("format").stringValue()).isEqualTo("conan");
        assertThat(info.path("type").stringValue()).isEqualTo("group");
        assertThat(handler.handle(request(groupRepo, "DELETE", "")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void authenticateNeedsCredentials() throws Exception {
        ResponseEntity<?> response = handler.handle(request(hostedRepo, "GET", "v2/users/authenticate"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic realm=");
        assertThat(json(response).path("code").stringValue()).isEqualTo("auth.required");
        verify(tokens, never()).issue(any(), anyString(), any());
    }

    @Test
    void authenticateMintsAnApiTokenThatExpires() throws Exception {
        User alice = new User();
        alice.setUsername("alice");
        when(users.find("alice")).thenReturn(Optional.of(alice));
        when(tokens.issue(eq(alice), eq("conan"), any())).thenReturn(new ApiTokenService.IssuedToken(new ApiToken(), TOKEN));

        RepositoryRequest login = as(request(hostedRepo, "GET", "v2/users/authenticate"), "alice");
        http(login).addHeader(HttpHeaders.AUTHORIZATION, basic("alice", "password1"));
        Instant before = Instant.now();
        ResponseEntity<?> response = handler.handle(login);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(body(response)).isEqualTo(TOKEN);

        ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
        verify(tokens).issue(eq(alice), eq("conan"), expiry.capture());
        assertThat(expiry.getValue()).isBetween(before.plus(Duration.ofHours(24)).minusSeconds(1),
                Instant.now().plus(Duration.ofHours(24)).plusSeconds(1));

        // the client may also POST
        assertThat(handler.handle(as(request(hostedRepo, "POST", "v2/users/authenticate"), "alice")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(handler.handle(as(request(hostedRepo, "PUT", "v2/users/authenticate"), "alice")).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void tokenLifetimeZeroNeverExpires() throws Exception {
        User alice = new User();
        alice.setUsername("alice");
        when(users.find("alice")).thenReturn(Optional.of(alice));
        when(tokens.issue(eq(alice), eq("conan"), isNull())).thenReturn(new ApiTokenService.IssuedToken(new ApiToken(), TOKEN));
        hostedRepo.setFormatSettings(Map.of("conan.tokenTtlHours", "0"));
        assertThat(body(handler.handle(as(request(hostedRepo, "GET", "v2/users/authenticate"), "alice")))).isEqualTo(TOKEN);
        verify(tokens).issue(eq(alice), eq("conan"), isNull());
    }

    @Test
    void authenticateWithAnApiTokenHandsTheSameTokenBack() throws Exception {
        RepositoryRequest login = as(request(hostedRepo, "GET", "v2/users/authenticate"), "alice");
        http(login).addHeader(HttpHeaders.AUTHORIZATION, basic("alice", TOKEN));
        ResponseEntity<?> response = handler.handle(login);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response)).isEqualTo(TOKEN);
        verify(tokens, never()).issue(any(), anyString(), any());
    }

    @Test
    void authenticateForAUserThatVanished() throws Exception {
        when(users.find("ghost")).thenReturn(Optional.empty());
        ResponseEntity<?> response = handler.handle(as(request(hostedRepo, "GET", "v2/users/authenticate"), "ghost"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void presentedTokenParsing() {
        assertThat(ConanFormatHandler.presentedToken(basic("alice", TOKEN))).contains(TOKEN);
        assertThat(ConanFormatHandler.presentedToken(basic("alice", "password1"))).isEmpty();
        assertThat(ConanFormatHandler.presentedToken("Bearer " + TOKEN)).isEmpty();
        assertThat(ConanFormatHandler.presentedToken("Basic !!!not-base64")).isEmpty();
        assertThat(ConanFormatHandler.presentedToken("Basic " + Base64.getEncoder().encodeToString(utf8(TOKEN)))).isEmpty();
        assertThat(ConanFormatHandler.presentedToken("Basic")).isEmpty();
        assertThat(ConanFormatHandler.presentedToken(null)).isEmpty();
    }

    @Test
    void checkCredentials() throws Exception {
        assertThat(handler.handle(request(hostedRepo, "GET", "v2/users/check_credentials")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<?> ok = handler.handle(as(request(hostedRepo, "GET", "v2/users/check_credentials"), "alice"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(ok)).isEqualTo("alice");
        assertThat(handler.handle(as(request(hostedRepo, "POST", "v2/users/check_credentials"), "alice")).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void tokenTtlFallsBackToTheDefault() {
        assertThat(ConanFormatHandler.tokenTtlHours(hostedRepo)).isEqualTo(24);
        hostedRepo.setFormatSettings(Map.of("conan.tokenTtlHours", "garbage"));
        assertThat(ConanFormatHandler.tokenTtlHours(hostedRepo)).isEqualTo(24);
        hostedRepo.setFormatSettings(Map.of("conan.tokenTtlHours", "-5"));
        assertThat(ConanFormatHandler.tokenTtlHours(hostedRepo)).isZero();
        hostedRepo.setFormatSettings(Map.of("conan.tokenTtlHours", "2"));
        assertThat(ConanFormatHandler.tokenTtlHours(hostedRepo)).isEqualTo(2);
    }

    // --- hosted ----------------------------------------------------------------------------

    @Test
    void hostedUploadThenEveryReadTheClientMakes() throws Exception {
        assertThat(handler.handle(request(hostedRepo, "GET", ConanRoutes.recipeLatest(ZLIB))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, byte[]> files = publish(hostedRepo, ZLIB, RREV, PID, PREV, "one");

        JsonNode latest = json(handler.handle(request(hostedRepo, "GET", ConanRoutes.recipeLatest(ZLIB))));
        assertThat(latest.path("revision").stringValue()).isEqualTo(RREV);
        assertThat(ConanRevisions.parseTime(latest.path("time").stringValue())).isPresent();

        JsonNode revisions = json(handler.handle(request(hostedRepo, "GET", ConanRoutes.recipeRevisions(ZLIB))));
        assertThat(revisions.path("revisions")).hasSize(1);

        JsonNode recipeFiles = json(handler.handle(request(hostedRepo, "GET", ConanRoutes.recipeFiles(ZLIB, RREV))));
        assertThat(recipeFiles.path("files").propertyNames())
                .containsExactlyInAnyOrder("conan_export.tgz", "conanfile.py", "conanmanifest.txt");

        ResponseEntity<?> conanfile = handler.handle(request(hostedRepo, "GET", ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py")));
        assertThat(conanfile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyBytes(conanfile)).isEqualTo(files.get("conanfile.py"));
        assertThat(conanfile.getHeaders().getContentType().toString()).isEqualTo("text/x-python");
        assertThat(conanfile.getHeaders().getETag()).isNotBlank();
        assertThat(storage.downloads).containsExactly(ConanLayout.exportPath(ZLIB, RREV, "conanfile.py"));

        JsonNode search = json(handler.handle(request(hostedRepo, "GET", ConanRoutes.packageSearch(ZLIB, RREV) + "?list_only=True")));
        assertThat(search.path(PID).path("settings").path("os").stringValue()).isEqualTo("Linux");
        assertThat(search.path(PID).path("options").path("shared").stringValue()).isEqualTo("False");
        JsonNode searchWithoutRevision = json(handler.handle(request(hostedRepo, "GET", ConanRoutes.recipe(ZLIB) + "/search")));
        assertThat(searchWithoutRevision.has(PID)).isTrue();

        JsonNode pkgLatest = json(handler.handle(request(hostedRepo, "GET", ConanRoutes.packageLatest(ZLIB, RREV, PID))));
        assertThat(pkgLatest.path("revision").stringValue()).isEqualTo(PREV);
        JsonNode pkgRevisions = json(handler.handle(request(hostedRepo, "GET", ConanRoutes.packageRevisions(ZLIB, RREV, PID))));
        assertThat(pkgRevisions.path("revisions").get(0).path("revision").stringValue()).isEqualTo(PREV);
        JsonNode pkgFiles = json(handler.handle(request(hostedRepo, "GET", ConanRoutes.packageFiles(ZLIB, RREV, PID, PREV))));
        assertThat(pkgFiles.path("files").propertyNames())
                .containsExactlyInAnyOrder("conan_package.tgz", "conaninfo.txt", "conanmanifest.txt");
        ResponseEntity<?> pkg = handler.handle(request(hostedRepo, "GET",
                ConanRoutes.packageFile(ZLIB, RREV, PID, PREV, "conan_package.tgz")));
        assertThat(bodyBytes(pkg)).isEqualTo(files.get("conan_package.tgz"));
        assertThat(pkg.getHeaders().getContentType().toString()).isEqualTo("application/gzip");

        JsonNode found = json(handler.handle(request(hostedRepo, "GET", "v2/conans/search?q=zlib")));
        assertThat(found.path("results")).hasSize(1);
        assertThat(found.path("results").get(0).stringValue()).isEqualTo("zlib/1.3.1");
        assertThat(json(handler.handle(request(hostedRepo, "GET", "v2/conans/search?q=openssl"))).path("results")).isEmpty();
        assertThat(json(handler.handle(request(hostedRepo, "GET", "v2/conans/search"))).path("results")).hasSize(1);

        // every stored file is linked to the recipe's component
        assertThat(storage.coordinates(hostedRepo, ConanLayout.exportPath(ZLIB, RREV, "conanfile.py")))
                .isEqualTo(new dev.granary.core.storage.StorageService.ComponentCoordinates("", "zlib", "1.3.1"));
    }

    @Test
    void userAndChannelReferences() throws Exception {
        publish(hostedRepo, ACME, RREV, PID, PREV, "acme");
        assertThat(storage.coordinates(hostedRepo, ConanLayout.exportPath(ACME, RREV, "conanfile.py")))
                .isEqualTo(new dev.granary.core.storage.StorageService.ComponentCoordinates("team/stable", "acme", "1.0"));
        JsonNode found = json(handler.handle(request(hostedRepo, "GET", "v2/conans/search?q=acme/*@team/*")));
        assertThat(found.path("results").get(0).stringValue()).isEqualTo("acme/1.0@team/stable");
        assertThat(json(handler.handle(request(hostedRepo, "GET", "v2/conans/search?q=ACME&ignorecase=False")))
                .path("results")).isEmpty();
        assertThat(json(handler.handle(request(hostedRepo, "GET", "v2/conans/search?q=ACME&ignorecase=True")))
                .path("results")).hasSize(1);
        assertThat(handler.handle(request(hostedRepo, "GET", ConanRoutes.recipeLatest(
                new ConanReference("acme", "1.0", null, null)))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void newestRevisionWins() throws Exception {
        storage.now = Instant.parse("2026-01-01T00:00:00Z");
        publish(hostedRepo, ZLIB, RREV, PID, PREV, "one");
        storage.now = Instant.parse("2026-02-01T00:00:00Z");
        publish(hostedRepo, ZLIB, RREV2, PID, PREV2, "two");
        assertThat(json(handler.handle(request(hostedRepo, "GET", ConanRoutes.recipeLatest(ZLIB)))).path("revision").stringValue())
                .isEqualTo(RREV2);
        JsonNode revisions = json(handler.handle(request(hostedRepo, "GET", ConanRoutes.recipeRevisions(ZLIB))));
        assertThat(revisions.path("revisions").get(0).path("revision").stringValue()).isEqualTo(RREV2);
        assertThat(revisions.path("revisions").get(0).path("time").stringValue()).isEqualTo("2026-02-01T00:00:00.000+0000");
        assertThat(revisions.path("revisions").get(1).path("revision").stringValue()).isEqualTo(RREV);
    }

    @Test
    void anInterruptedUploadLeavesNoVisibleRevision() throws Exception {
        handler.handle(upload(hostedRepo, ConanRoutes.recipeFile(ZLIB, RREV, "conan_export.tgz"), tgz("x"), "alice"));
        handler.handle(upload(hostedRepo, ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py"), utf8("py"), "alice"));
        assertThat(handler.handle(request(hostedRepo, "GET", ConanRoutes.recipeLatest(ZLIB))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hostedRepo, "GET", ConanRoutes.recipeFiles(ZLIB, RREV))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(handler.handle(request(hostedRepo, "GET", "v2/conans/search?q=*"))).path("results")).isEmpty();
    }

    @Test
    void hostedNotFoundAnswers() throws Exception {
        publish(hostedRepo, ZLIB, RREV, PID, PREV, "one");
        for (String path : List.of(
                ConanRoutes.recipeLatest(ACME),
                ConanRoutes.recipeRevisions(ACME),
                ConanRoutes.recipeFiles(ZLIB, RREV2),
                ConanRoutes.recipeFile(ZLIB, RREV, "missing.txt"),
                ConanRoutes.packageSearch(ZLIB, RREV2),
                ConanRoutes.recipe(ACME) + "/search",
                ConanRoutes.packageLatest(ZLIB, RREV, "otherpid"),
                ConanRoutes.packageRevisions(ZLIB, RREV2, PID),
                ConanRoutes.packageFiles(ZLIB, RREV, PID, PREV2),
                ConanRoutes.packageFile(ZLIB, RREV, PID, PREV, "missing.tgz"),
                ConanRoutes.recipe(ZLIB),
                ConanRoutes.recipeRevision(ZLIB, RREV),
                ConanRoutes.packages(ZLIB, RREV),
                ConanRoutes.packages(ZLIB, RREV) + "/" + PID,
                ConanRoutes.packageRevisions(ZLIB, RREV, PID) + "/" + PREV,
                "v1/conans/zlib/1.3.1/_/_/download_urls",
                "v2/whatever")) {
            ResponseEntity<?> response = handler.handle(request(hostedRepo, "GET", path));
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            JsonNode error = json(response);
            assertThat(error.path("errors").get(0).path("status").intValue()).as(path).isEqualTo(404);
            assertThat(error.path("message").stringValue()).as(path).isNotBlank();
        }
    }

    @Test
    void invalidPathsAreBadRequests() throws Exception {
        for (String path : List.of("v2/conans/zlib/1.3.1/_/_/revisions/a%2Fb/files",
                "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/..",
                "v2/conans/zl ib/1.3.1/_/_/latest")) {
            ResponseEntity<?> response = handler.handle(request(hostedRepo, "GET", path));
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(response).path("code").stringValue()).isEqualTo("path.invalid");
        }
        ResponseEntity<?> put = handler.handle(upload(hostedRepo,
                "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/a\\b", utf8("x"), "alice"));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(storage.paths(hostedRepo)).isEmpty();
    }

    @Test
    void methodsThatMakeNoSense() throws Exception {
        for (String[] call : List.of(
                new String[] {"POST", ConanRoutes.recipeLatest(ZLIB)},
                new String[] {"PUT", ConanRoutes.recipeRevisions(ZLIB)},
                new String[] {"PUT", ConanRoutes.recipeFiles(ZLIB, RREV)},
                new String[] {"DELETE", ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py")},
                new String[] {"POST", ConanRoutes.recipe(ZLIB)},
                new String[] {"POST", ConanRoutes.recipeRevision(ZLIB, RREV)},
                new String[] {"PUT", ConanRoutes.packageSearch(ZLIB, RREV)},
                new String[] {"PUT", ConanRoutes.packages(ZLIB, RREV)},
                new String[] {"PUT", ConanRoutes.packages(ZLIB, RREV) + "/" + PID},
                new String[] {"PUT", ConanRoutes.packageLatest(ZLIB, RREV, PID)},
                new String[] {"PUT", ConanRoutes.packageRevisions(ZLIB, RREV, PID)},
                new String[] {"PUT", ConanRoutes.packageRevisions(ZLIB, RREV, PID) + "/" + PREV},
                new String[] {"PUT", ConanRoutes.packageFiles(ZLIB, RREV, PID, PREV)},
                new String[] {"DELETE", ConanRoutes.packageFile(ZLIB, RREV, PID, PREV, "conaninfo.txt")},
                new String[] {"PUT", "v2/conans/search"})) {
            assertThat(handler.handle(as(request(hostedRepo, call[0], call[1]), "alice")).getStatusCode())
                    .as(call[0] + " " + call[1]).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }
    }

    @Test
    void headAndConditionalRequests() throws Exception {
        publish(hostedRepo, ZLIB, RREV, PID, PREV, "one");
        ResponseEntity<?> head = handler.handle(request(hostedRepo, "HEAD", ConanRoutes.recipeFile(ZLIB, RREV, "conan_export.tgz")));
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isPositive();
        assertThat(storage.downloads).isEmpty(); // a HEAD is not a download

        ResponseEntity<?> latest = handler.handle(request(hostedRepo, "GET", ConanRoutes.recipeLatest(ZLIB)));
        RepositoryRequest conditional = request(hostedRepo, "GET", ConanRoutes.recipeLatest(ZLIB));
        http(conditional).addHeader(HttpHeaders.IF_NONE_MATCH, latest.getHeaders().getETag());
        assertThat(handler.handle(conditional).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        RepositoryRequest conditionalFile = request(hostedRepo, "GET", ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py"));
        String etag = handler.handle(request(hostedRepo, "HEAD", ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py")))
                .getHeaders().getETag();
        http(conditionalFile).addHeader(HttpHeaders.IF_NONE_MATCH, etag);
        assertThat(handler.handle(conditionalFile).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(storage.downloads).isEmpty();
    }

    @Test
    void reUploadRules() throws Exception {
        publish(hostedRepo, ZLIB, RREV, PID, PREV, "one");
        String file = ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py");
        // the same bytes again: the client re-running an interrupted upload
        assertThat(handler.handle(upload(hostedRepo, file, utf8("conanfile one"), "alice")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<?> conflict = handler.handle(upload(hostedRepo, file, utf8("tampered"), "alice"));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(conflict).path("errors").get(0).path("message").stringValue()).contains("conanfile.py");

        RepositoryRequest mismatch = request(hostedRepo, "PUT", file, utf8("other"));
        http(mismatch).addHeader(ConanHostedWriter.CHECKSUM_SHA1, "0000000000000000000000000000000000000000");
        assertThat(handler.handle(as(mismatch, "alice")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        RepositoryRequest dedup = request(hostedRepo, "PUT", file, new byte[0]);
        http(dedup).addHeader(ConanHostedWriter.CHECKSUM_DEPLOY, "true");
        http(dedup).addHeader(ConanHostedWriter.CHECKSUM_SHA1, ConanTestSupport.sha1(utf8("conanfile one")));
        assertThat(handler.handle(as(dedup, "alice")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        RepositoryRequest dedupMiss = request(hostedRepo, "PUT", ConanRoutes.recipeFile(ZLIB, RREV2, "conanfile.py"), new byte[0]);
        http(dedupMiss).addHeader(ConanHostedWriter.CHECKSUM_DEPLOY, "true");
        http(dedupMiss).addHeader(ConanHostedWriter.CHECKSUM_SHA1, ConanTestSupport.sha1(utf8("conanfile one")));
        assertThat(handler.handle(as(dedupMiss, "alice")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletes() throws Exception {
        storage.now = Instant.parse("2026-01-01T00:00:00Z");
        publish(hostedRepo, ZLIB, RREV, PID, PREV, "one");
        storage.now = Instant.parse("2026-01-02T00:00:00Z");
        publishPackage(hostedRepo, ZLIB, RREV, PID, PREV2, "one-b");
        storage.now = Instant.parse("2026-01-03T00:00:00Z");
        publish(hostedRepo, ZLIB, RREV2, PID, PREV, "two");

        // one package revision
        assertThat(handler.handle(as(request(hostedRepo, "DELETE",
                ConanRoutes.packageRevisions(ZLIB, RREV, PID) + "/" + PREV2), "alice")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(handler.handle(request(hostedRepo, "GET", ConanRoutes.packageLatest(ZLIB, RREV, PID))))
                .path("revision").stringValue()).isEqualTo(PREV);
        // one binary
        assertThat(handler.handle(as(request(hostedRepo, "DELETE", ConanRoutes.packages(ZLIB, RREV) + "/" + PID), "alice"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(handler.handle(request(hostedRepo, "GET", ConanRoutes.packageSearch(ZLIB, RREV)))).isEmpty()).isTrue();
        // all binaries of a revision: nothing left is a 404 the client double-checks with a search
        assertThat(handler.handle(as(request(hostedRepo, "DELETE", ConanRoutes.packages(ZLIB, RREV)), "alice")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(as(request(hostedRepo, "DELETE", ConanRoutes.packages(ZLIB, RREV2)), "alice")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // all revisions of a binary via .../revisions
        publish(hostedRepo, ZLIB, RREV2, PID, PREV, "two");
        assertThat(handler.handle(as(request(hostedRepo, "DELETE", ConanRoutes.packageRevisions(ZLIB, RREV2, PID)), "alice"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        // a recipe revision
        assertThat(handler.handle(as(request(hostedRepo, "DELETE", ConanRoutes.recipeRevision(ZLIB, RREV2)), "alice"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(handler.handle(request(hostedRepo, "GET", ConanRoutes.recipeLatest(ZLIB)))).path("revision").stringValue())
                .isEqualTo(RREV);
        assertThat(handler.handle(as(request(hostedRepo, "DELETE", ConanRoutes.recipeRevision(ZLIB, RREV2)), "alice"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // the whole recipe, via .../revisions and via the bare reference
        assertThat(handler.handle(as(request(hostedRepo, "DELETE", ConanRoutes.recipeRevisions(ZLIB)), "alice")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(storage.paths(hostedRepo)).isEmpty();
        assertThat(handler.handle(as(request(hostedRepo, "DELETE", ConanRoutes.recipe(ZLIB)), "alice")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        publish(hostedRepo, ZLIB, RREV, PID, PREV, "again");
        assertThat(handler.handle(as(request(hostedRepo, "DELETE", ConanRoutes.recipe(ZLIB)), "alice")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(handler.handle(as(request(hostedRepo, "DELETE",
                ConanRoutes.packageRevisions(ZLIB, RREV, PID) + "/" + PREV), "alice")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(as(request(hostedRepo, "DELETE", ConanRoutes.packages(ZLIB, RREV) + "/" + PID), "alice"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- proxy -----------------------------------------------------------------------------

    @Test
    void proxyForwardsEveryReadAndCachesFilesForever() throws Exception {
        byte[] export = tgz("upstream-export");
        stubUpstreamRecipe(ZLIB, RREV, export);

        JsonNode latest = json(handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeLatest(ZLIB))));
        assertThat(latest.path("revision").stringValue()).isEqualTo(RREV);
        assertThat(upstream.uris()).containsExactly(UPSTREAM + "/" + ConanRoutes.recipeLatest(ZLIB));
        assertThat(upstream.calls.get(0).headers()).containsEntry("Accept", "application/json");
        assertThat(upstream.calls.get(0).metadata()).isTrue();
        assertThat(upstream.calls.get(0).cachePath()).isEqualTo(ConanRoutes.recipeLatest(ZLIB));

        JsonNode files = json(handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeFiles(ZLIB, RREV))));
        assertThat(files.path("files").propertyNames()).contains("conan_export.tgz");

        for (int i = 0; i < 2; i++) {
            ResponseEntity<?> file = handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeFile(ZLIB, RREV, "conan_export.tgz")));
            assertThat(file.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bodyBytes(file)).isEqualTo(export);
        }
        String fileUri = UPSTREAM + "/" + ConanRoutes.recipeFile(ZLIB, RREV, "conan_export.tgz");
        assertThat(upstream.count(fileUri)).isEqualTo(1);
        // cached under the hosted layout and linked to the component, so the UI shows it
        String cached = ConanLayout.exportPath(ZLIB, RREV, "conan_export.tgz");
        assertThat(storage.bytes(proxyRepo, cached)).isEqualTo(export);
        assertThat(storage.find(proxyRepo, cached).orElseThrow().asset().getComponentId()).isEqualTo(storage.componentId);
        assertThat(storage.coordinates(proxyRepo, "#component"))
                .isEqualTo(new dev.granary.core.storage.StorageService.ComponentCoordinates("", "zlib", "1.3.1"));
        assertThat(storage.downloads).hasSize(2);

        JsonNode revisions = json(handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeRevisions(ZLIB))));
        assertThat(revisions.path("revisions").get(0).path("revision").stringValue()).isEqualTo(RREV);
        // the upstream's time text is passed through untouched
        assertThat(revisions.path("revisions").get(0).path("time").stringValue()).isEqualTo("2025-11-03T10:20:30.123+0000");
    }

    @Test
    void proxiedFilesAreServedWithTheirOwnContentTypeWhateverTheUpstreamSent() throws Exception {
        upstream.fileContentType = "auto"; // what conan_server sends with every file
        stubUpstreamRecipe(ZLIB, RREV, tgz("upstream-export"));
        upstream.put(UPSTREAM + "/" + ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py"), "from conan import ConanFile");

        ResponseEntity<?> export = handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeFile(ZLIB, RREV, "conan_export.tgz")));
        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(export.getHeaders().getContentType()).hasToString("application/gzip");
        for (int i = 0; i < 2; i++) {
            ResponseEntity<?> conanfile = handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py")));
            assertThat(conanfile.getHeaders().getContentType()).hasToString("text/x-python");
        }
        assertThat(storage.find(proxyRepo, ConanLayout.exportPath(ZLIB, RREV, "conanfile.py")).orElseThrow().asset().getContentType())
                .isEqualTo("text/x-python");
    }

    @Test
    void proxyPackagesFlow() throws Exception {
        byte[] pkg = tgz("binary");
        upstream.put(UPSTREAM + "/" + ConanRoutes.packageSearch(ZLIB, RREV),
                "{\"" + PID + "\":{\"settings\":{\"os\":\"Linux\"},\"options\":{},\"requires\":[]}}");
        upstream.put(UPSTREAM + "/" + ConanRoutes.packageLatest(ZLIB, RREV, PID),
                "{\"revision\":\"" + PREV + "\",\"time\":\"2025-11-03T10:20:30.123+0000\"}");
        upstream.put(UPSTREAM + "/" + ConanRoutes.packageRevisions(ZLIB, RREV, PID),
                "{\"revisions\":[{\"revision\":\"" + PREV + "\",\"time\":\"2025-11-03T10:20:30.123+0000\"}]}");
        upstream.put(UPSTREAM + "/" + ConanRoutes.packageFiles(ZLIB, RREV, PID, PREV),
                "{\"files\":{\"conan_package.tgz\":{},\"conaninfo.txt\":{},\"conanmanifest.txt\":{}}}");
        upstream.put(UPSTREAM + "/" + ConanRoutes.packageFile(ZLIB, RREV, PID, PREV, "conan_package.tgz"), pkg);

        JsonNode search = json(handler.handle(request(proxyRepo, "GET", ConanRoutes.packageSearch(ZLIB, RREV) + "?list_only=True")));
        assertThat(search.path(PID).path("settings").path("os").stringValue()).isEqualTo("Linux");
        assertThat(json(handler.handle(request(proxyRepo, "GET", ConanRoutes.packageLatest(ZLIB, RREV, PID))))
                .path("revision").stringValue()).isEqualTo(PREV);
        assertThat(json(handler.handle(request(proxyRepo, "GET", ConanRoutes.packageRevisions(ZLIB, RREV, PID))))
                .path("revisions")).hasSize(1);
        assertThat(json(handler.handle(request(proxyRepo, "GET", ConanRoutes.packageFiles(ZLIB, RREV, PID, PREV))))
                .path("files").propertyNames()).hasSize(3);
        assertThat(bodyBytes(handler.handle(request(proxyRepo, "GET",
                ConanRoutes.packageFile(ZLIB, RREV, PID, PREV, "conan_package.tgz"))))).isEqualTo(pkg);
        assertThat(storage.bytes(proxyRepo, ConanLayout.packagePath(ZLIB, RREV, PID, PREV, "conan_package.tgz"))).isEqualTo(pkg);
        // the upstream package search is asked without the client's query string
        assertThat(upstream.uris()).contains(UPSTREAM + "/" + ConanRoutes.packageSearch(ZLIB, RREV));
    }

    @Test
    void proxyLatestFallsBackToTheRevisionList() throws Exception {
        upstream.put(UPSTREAM + "/" + ConanRoutes.recipeRevisions(ZLIB),
                "{\"revisions\":[{\"revision\":\"" + RREV2 + "\",\"time\":\"2025-12-01T00:00:00.000+0000\"},"
                        + "{\"revision\":\"" + RREV + "\",\"time\":\"2025-11-01T00:00:00.000+0000\"}]}");
        assertThat(json(handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeLatest(ZLIB)))).path("revision").stringValue())
                .isEqualTo(RREV2);
        upstream.put(UPSTREAM + "/" + ConanRoutes.packageRevisions(ZLIB, RREV, PID),
                "{\"revisions\":[{\"revision\":\"" + PREV + "\",\"time\":\"2025-11-03T10:20:30.123+0000\"}]}");
        assertThat(json(handler.handle(request(proxyRepo, "GET", ConanRoutes.packageLatest(ZLIB, RREV, PID))))
                .path("revision").stringValue()).isEqualTo(PREV);
    }

    @Test
    void proxySearchPassesThePatternThrough() throws Exception {
        upstream.put(UPSTREAM + "/v2/conans/search?q=zlib%2F*",
                "{\"results\":[\"zlib/1.3.1@_/_\",\"zlib/1.2.13\"]}");
        JsonNode found = json(handler.handle(request(proxyRepo, "GET", "v2/conans/search?q=zlib/*")));
        assertThat(found.path("results")).hasSize(2);
        assertThat(found.path("results").get(0).stringValue()).isEqualTo("zlib/1.2.13");
        assertThat(found.path("results").get(1).stringValue()).isEqualTo("zlib/1.3.1");
        assertThat(upstream.calls.get(0).cachePath()).isEqualTo("v2/conans/search?q=zlib%2F*");
        // upstream without an answer: no results, not an error
        assertThat(json(handler.handle(request(proxyRepo, "GET", "v2/conans/search?q=nothing"))).path("results")).isEmpty();
        upstream.put(UPSTREAM + "/v2/conans/search?q=Zlib&ignorecase=False", "{\"results\":[]}");
        handler.handle(request(proxyRepo, "GET", "v2/conans/search?q=Zlib&ignorecase=False"));
        assertThat(upstream.uris()).contains(UPSTREAM + "/v2/conans/search?q=Zlib&ignorecase=False");
    }

    @Test
    void proxyMissesAreNotFound() throws Exception {
        assertThat(handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeLatest(ZLIB))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py"))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(proxyRepo, "GET", ConanRoutes.packageSearch(ZLIB, RREV))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(proxyRepo, "GET", ConanRoutes.recipe(ZLIB) + "/search")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void malformedUpstreamDocumentsAreMissesNotErrors() throws Exception {
        upstream.put(UPSTREAM + "/" + ConanRoutes.recipeLatest(ZLIB), "<html>maintenance</html>");
        upstream.put(UPSTREAM + "/" + ConanRoutes.recipeRevisions(ZLIB), "null");
        upstream.put(UPSTREAM + "/" + ConanRoutes.recipeFiles(ZLIB, RREV), "{\"files\":[]}");
        upstream.put(UPSTREAM + "/" + ConanRoutes.packageSearch(ZLIB, RREV), "[1,2]");
        assertThat(handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeLatest(ZLIB))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeFiles(ZLIB, RREV))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(proxyRepo, "GET", ConanRoutes.packageSearch(ZLIB, RREV))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void offlineProxyServesTheCacheAndNeverLogsInUpstream() throws Exception {
        byte[] export = tgz("upstream-export");
        stubUpstreamRecipe(ZLIB, RREV, export);
        handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeLatest(ZLIB)));
        handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeFile(ZLIB, RREV, "conan_export.tgz")));
        int before = upstream.calls.size();

        proxyRepo.setRemoteUsername("ci");
        proxyRepo.setRemotePassword("secret");
        upstream.offline = true;
        assertThat(json(handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeLatest(ZLIB)))).path("revision").stringValue())
                .isEqualTo(RREV);
        assertThat(bodyBytes(handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeFile(ZLIB, RREV, "conan_export.tgz")))))
                .isEqualTo(export);
        assertThat(handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeLatest(ACME))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(upstream.calls).hasSize(before);
        verify(upstream.client, never()).getAbsolute(any(), any(), anyMap(), anyMap());
    }

    @Test
    void unreachableUpstreamServesStaleDocuments() throws Exception {
        stubUpstreamRecipe(ZLIB, RREV, tgz("x"));
        handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeLatest(ZLIB)));
        upstream.down = true;
        assertThat(json(handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeLatest(ZLIB)))).path("revision").stringValue())
                .isEqualTo(RREV);
    }

    @Test
    void privateUpstreamGetsTheExchangedBearerToken() throws Exception {
        proxyRepo.setRemoteUsername("ci");
        proxyRepo.setRemotePassword("secret");
        UpstreamClient.Response login = mock(UpstreamClient.Response.class);
        when(login.status()).thenReturn(200);
        when(login.body()).thenReturn(new ByteArrayInputStream("upstream-jwt".getBytes(StandardCharsets.UTF_8)));
        when(upstream.client.getAbsolute(any(), any(), anyMap(), anyMap())).thenReturn(login);
        stubUpstreamRecipe(ZLIB, RREV, tgz("x"));

        handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeLatest(ZLIB)));
        handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeFile(ZLIB, RREV, "conan_export.tgz")));
        assertThat(upstream.calls).hasSize(2).allSatisfy(call -> {
            assertThat(call.repo().getRemoteToken()).isEqualTo("upstream-jwt");
            assertThat(call.repo().getRemotePassword()).isNull();
            assertThat(call.repo().getId()).isEqualTo(proxyRepo.getId());
        });
        verify(upstream.client).getAbsolute(eq(proxyRepo), eq(java.net.URI.create(UPSTREAM + "/v2/users/authenticate")),
                anyMap(), anyMap());
    }

    @Test
    void anUpstream401MakesTheNextRequestLogInAgain() throws Exception {
        proxyRepo.setRemoteUsername("ci");
        proxyRepo.setRemotePassword("secret");
        UpstreamClient.Response first = mock(UpstreamClient.Response.class);
        when(first.status()).thenReturn(200);
        when(first.body()).thenReturn(new ByteArrayInputStream(utf8("token-1")));
        UpstreamClient.Response second = mock(UpstreamClient.Response.class);
        when(second.status()).thenReturn(200);
        when(second.body()).thenReturn(new ByteArrayInputStream(utf8("token-2")));
        when(upstream.client.getAbsolute(any(), any(), anyMap(), anyMap())).thenReturn(first, second);
        stubUpstreamRecipe(ZLIB, RREV, tgz("x"));

        handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeLatest(ZLIB)));
        assertThat(upstream.calls.getLast().repo().getRemoteToken()).isEqualTo("token-1");

        // the upstream restarted with a new secret: the cached token is refused
        upstream.status = new dev.granary.core.proxy.UpstreamHealth.Status(true, 1, "HTTP 401 for " + ConanRoutes.recipeLatest(ZLIB),
                null, Instant.now(), null);
        Thread.sleep(5);
        handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeRevisions(ZLIB)));
        assertThat(upstream.calls.getLast().repo().getRemoteToken()).isEqualTo("token-2");
        // the same failure seen again keeps the token obtained after it
        handler.handle(request(proxyRepo, "GET", ConanRoutes.recipeFiles(ZLIB, RREV)));
        assertThat(upstream.calls.getLast().repo().getRemoteToken()).isEqualTo("token-2");
        verify(upstream.client, org.mockito.Mockito.times(2)).getAbsolute(any(), any(), anyMap(), anyMap());
    }

    @Test
    void metadataFilesAreRevalidatedNotCachedForever() throws Exception {
        String route = ConanRoutes.recipeFile(ZLIB, RREV, "metadata/logs/build.log");
        upstream.put(UPSTREAM + "/" + route, "first");
        assertThat(body(handler.handle(request(proxyRepo, "GET", route)))).isEqualTo("first");
        upstream.put(UPSTREAM + "/" + route, "second");
        assertThat(body(handler.handle(request(proxyRepo, "GET", route)))).isEqualTo("second");
        assertThat(upstream.count(UPSTREAM + "/" + route)).isEqualTo(2);
    }

    @Test
    void proxiesAreReadOnly() throws Exception {
        ResponseEntity<?> put = handler.handle(upload(proxyRepo, ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py"), utf8("x"), "alice"));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(put).path("code").stringValue()).isEqualTo("repository.readOnly");
        assertThat(json(put).path("message").stringValue()).contains("proxy").contains(UPSTREAM);
        assertThat(handler.handle(as(request(proxyRepo, "DELETE", ConanRoutes.recipe(ZLIB)), "alice")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upstream.calls).isEmpty();
    }

    // --- group -----------------------------------------------------------------------------

    @Test
    void groupMergesRevisionsWithHostedFirst() throws Exception {
        storage.now = Instant.parse("2026-03-01T00:00:00Z");
        publish(hostedRepo, ZLIB, RREV2, PID, PREV2, "hosted");
        // upstream knows RREV (older) and RREV2 with a different time; the hosted entry wins for RREV2
        upstream.put(UPSTREAM + "/" + ConanRoutes.recipeRevisions(ZLIB),
                "{\"revisions\":[{\"revision\":\"" + RREV2 + "\",\"time\":\"2026-04-01T00:00:00.000+0000\"},"
                        + "{\"revision\":\"" + RREV + "\",\"time\":\"2025-01-01T00:00:00.000+0000\"}]}");
        upstream.put(UPSTREAM + "/" + ConanRoutes.recipeLatest(ZLIB),
                "{\"revision\":\"" + RREV + "\",\"time\":\"2025-01-01T00:00:00.000+0000\"}");

        JsonNode revisions = json(handler.handle(request(groupRepo, "GET", ConanRoutes.recipeRevisions(ZLIB))));
        assertThat(revisions.path("revisions")).hasSize(2);
        assertThat(revisions.path("revisions").get(0).path("revision").stringValue()).isEqualTo(RREV2);
        assertThat(revisions.path("revisions").get(0).path("time").stringValue()).isEqualTo("2026-03-01T00:00:00.000+0000");
        assertThat(revisions.path("revisions").get(1).path("revision").stringValue()).isEqualTo(RREV);

        JsonNode latest = json(handler.handle(request(groupRepo, "GET", ConanRoutes.recipeLatest(ZLIB))));
        assertThat(latest.path("revision").stringValue()).isEqualTo(RREV2);
    }

    @Test
    void groupLatestIsTheNewestAcrossMembers() throws Exception {
        storage.now = Instant.parse("2025-01-01T00:00:00Z");
        publish(hostedRepo, ZLIB, RREV, PID, PREV, "hosted");
        upstream.put(UPSTREAM + "/" + ConanRoutes.recipeLatest(ZLIB),
                "{\"revision\":\"" + RREV2 + "\",\"time\":\"2026-06-01T00:00:00.000+0000\"}");
        assertThat(json(handler.handle(request(groupRepo, "GET", ConanRoutes.recipeLatest(ZLIB)))).path("revision").stringValue())
                .isEqualTo(RREV2);
        upstream.put(UPSTREAM + "/" + ConanRoutes.packageLatest(ZLIB, RREV, PID),
                "{\"revision\":\"" + PREV2 + "\",\"time\":\"2026-06-01T00:00:00.000+0000\"}");
        assertThat(json(handler.handle(request(groupRepo, "GET", ConanRoutes.packageLatest(ZLIB, RREV, PID))))
                .path("revision").stringValue()).isEqualTo(PREV2);
        JsonNode prevs = json(handler.handle(request(groupRepo, "GET", ConanRoutes.packageRevisions(ZLIB, RREV, PID))));
        assertThat(prevs.path("revisions")).hasSize(1); // the upstream has no list, the hosted member has PREV
    }

    @Test
    void groupFilesComeFromTheMemberThatHasTheRevision() throws Exception {
        Map<String, byte[]> hostedFiles = publish(hostedRepo, ZLIB, RREV, PID, PREV, "hosted");
        byte[] upstreamExport = tgz("upstream");
        stubUpstreamRecipe(ZLIB, RREV2, upstreamExport);
        // the same revision upstream too: the hosted copy wins without asking upstream
        upstream.put(UPSTREAM + "/" + ConanRoutes.recipeFile(ZLIB, RREV, "conan_export.tgz"), tgz("should not be used"));

        assertThat(bodyBytes(handler.handle(request(groupRepo, "GET", ConanRoutes.recipeFile(ZLIB, RREV, "conan_export.tgz")))))
                .isEqualTo(hostedFiles.get("conan_export.tgz"));
        assertThat(upstream.count(UPSTREAM + "/" + ConanRoutes.recipeFile(ZLIB, RREV, "conan_export.tgz"))).isZero();
        assertThat(bodyBytes(handler.handle(request(groupRepo, "GET", ConanRoutes.recipeFile(ZLIB, RREV2, "conan_export.tgz")))))
                .isEqualTo(upstreamExport);
        assertThat(json(handler.handle(request(groupRepo, "GET", ConanRoutes.recipeFiles(ZLIB, RREV2))))
                .path("files").propertyNames()).contains("conan_export.tgz");
        assertThat(json(handler.handle(request(groupRepo, "GET", ConanRoutes.packageFiles(ZLIB, RREV, PID, PREV))))
                .path("files").propertyNames()).contains("conaninfo.txt");
        assertThat(bodyBytes(handler.handle(request(groupRepo, "GET",
                ConanRoutes.packageFile(ZLIB, RREV, PID, PREV, "conaninfo.txt"))))).isEqualTo(hostedFiles.get("conaninfo.txt"));
        assertThat(handler.handle(request(groupRepo, "GET", ConanRoutes.recipeFiles(ZLIB, "nowhere"))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void groupSearchesAreMerged() throws Exception {
        publish(hostedRepo, ACME, RREV, PID, PREV, "acme");
        upstream.put(UPSTREAM + "/v2/conans/search?q=*", "{\"results\":[\"zlib/1.3.1@_/_\",\"acme/1.0@team/stable\"]}");
        JsonNode found = json(handler.handle(request(groupRepo, "GET", "v2/conans/search?q=*")));
        assertThat(found.path("results")).hasSize(2);

        publish(hostedRepo, ZLIB, RREV, PID, PREV, "hosted");
        upstream.put(UPSTREAM + "/" + ConanRoutes.packageSearch(ZLIB, RREV),
                "{\"" + PID + "\":{\"settings\":{\"os\":\"Windows\"}},\"otherpid\":{\"settings\":{\"os\":\"Macos\"}}}");
        JsonNode packages = json(handler.handle(request(groupRepo, "GET", ConanRoutes.packageSearch(ZLIB, RREV))));
        assertThat(packages.propertyNames()).containsExactlyInAnyOrder(PID, "otherpid");
        assertThat(packages.path(PID).path("settings").path("os").stringValue()).isEqualTo("Linux"); // hosted wins
    }

    @Test
    void groupWritesAreRefusedNamingTheHostedMember() throws Exception {
        ResponseEntity<?> put = handler.handle(upload(groupRepo, ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py"), utf8("x"), "alice"));
        // 403, because that is the status whose message the client prints (it only says "405 Client Error" otherwise)
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(put).path("message").stringValue()).contains("upload to its hosted member 'conan-hosted'");
        assertThat(json(put).path("errors").path(0).path("status").asInt()).isEqualTo(403);
        ResponseEntity<?> delete = handler.handle(as(request(groupRepo, "DELETE", ConanRoutes.recipeRevisions(ZLIB)), "alice"));
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(delete).path("message").stringValue()).contains("remove packages from its hosted member 'conan-hosted'");

        Repository proxiesOnly = ConanTestSupport.group("proxies-only", "conancenter");
        when(groups.leafMembers(proxiesOnly)).thenReturn(List.of(proxyRepo));
        ResponseEntity<?> refused = handler.handle(upload(proxiesOnly, ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py"),
                utf8("x"), "alice"));
        assertThat(json(refused).path("message").stringValue()).contains("hosted repository");
    }

    @Test
    void connectSnippetsUseTheGroupsHostedMember() {
        ConnectContext group = new ConnectContext(groupRepo, "https://g.test/repository/conan-group", "https://g.test",
                "alice", ConnectContext.TOKEN_PLACEHOLDER, true);
        List<ConnectSnippet> snippets = handler.connectSnippets(group);
        assertThat(snippets).extracting(ConnectSnippet::id).contains("remote-add", "login", "upload");
        assertThat(snippets.stream().filter(s -> s.id().equals("upload")).findFirst().orElseThrow().code())
                .contains("https://g.test/repository/conan-hosted");
        ConnectContext proxy = new ConnectContext(proxyRepo, "https://g.test/repository/conancenter", "https://g.test",
                "alice", ConnectContext.TOKEN_PLACEHOLDER, true);
        assertThat(handler.connectSnippets(proxy)).isNotEmpty();
        ConnectContext hosted = new ConnectContext(hostedRepo, "https://g.test/repository/conan-hosted", "https://g.test",
                "alice", ConnectContext.TOKEN_PLACEHOLDER, false);
        assertThat(handler.connectSnippets(hosted)).isNotEmpty();
    }

    // --- hardening: odd requests must be cheap 4xx answers, never 500s or busy threads -----------

    @Test
    void wildcardHeavySearchesFinishQuickly() throws Exception {
        ConanReference longName = new ConanReference("a-rather-long-recipe-name-with-many-letters", "1.0.0",
                "company", "stable");
        publish(hostedRepo, longName, RREV, PID, PREV, "long");
        // a backtracking regular expression needs hours for this against a 60 character reference
        String pattern = "*?".repeat(14) + "X";
        ResponseEntity<?> response = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> handler.handle(request(hostedRepo, "GET", "v2/conans/search?q=" + pattern)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).path("results")).isEmpty();
        assertThat(json(handler.handle(request(hostedRepo, "GET", "v2/conans/search?q=" + "*?".repeat(14))))
                .path("results").get(0).stringValue()).isEqualTo(longName.display());

        ResponseEntity<?> tooLong = handler.handle(request(hostedRepo, "GET",
                "v2/conans/search?q=" + "z".repeat(ConanSearch.MAX_PATTERN_LENGTH + 1)));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(tooLong).path("code").stringValue()).isEqualTo("search.patternTooLong");
        assertThat(handler.handle(request(hostedRepo, "GET",
                "v2/conans/search?q=" + "z".repeat(ConanSearch.MAX_PATTERN_LENGTH))).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void overlongNamesAndFilesAreBadRequestsNotDatabaseErrors() throws Exception {
        String longest = "v".repeat(ConanReference.MAX_SEGMENT_LENGTH);
        String tooLong = longest + "v";
        assertThat(handler.handle(upload(hostedRepo, "v2/conans/zlib/" + tooLong + "/_/_/revisions/" + RREV
                + "/files/conanfile.py", utf8("x"), "alice")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handle(upload(hostedRepo, "v2/conans/" + tooLong + "/1.0/_/_/revisions/" + RREV
                + "/files/conanfile.py", utf8("x"), "alice")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handle(upload(hostedRepo, "v2/conans/zlib/1.0/_/_/revisions/" + tooLong
                + "/files/conanfile.py", utf8("x"), "alice")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String deepFile = "metadata/" + String.join("/", java.util.Collections.nCopies(6, "d".repeat(200)));
        assertThat(handler.handle(upload(hostedRepo, ConanRoutes.recipeFile(ZLIB, RREV, deepFile), utf8("x"), "alice"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handle(request(hostedRepo, "GET", "v2/conans/" + tooLong + "/1.0/_/_/latest")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(storage.paths(hostedRepo)).isEmpty();

        // the longest accepted parts still fit every column
        assertThat(handler.handle(upload(hostedRepo, "v2/conans/zlib/" + longest + "/_/_/revisions/" + RREV
                + "/files/conanfile.py", utf8("x"), "alice")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void encodedSlashesAndBackslashesAreBadRequests() throws Exception {
        // decoded, a%2Fb would become two segments and address the reference a/b@1.0/_ instead
        for (String raw : List.of("v2/conans/a%2Fb/1.0/_/revisions/" + RREV + "/files/conanfile.py",
                "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/metadata%2fsign.txt",
                "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/a%5Cb")) {
            RepositoryRequest put = upload(hostedRepo, java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8), utf8("x"), "alice");
            http(put).setRequestURI("/repository/" + hostedRepo.getName() + "/" + raw);
            ResponseEntity<?> response = handler.handle(put);
            assertThat(response.getStatusCode()).as(raw).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(response).path("code").stringValue()).isEqualTo("path.invalid");
        }
        assertThat(storage.paths(hostedRepo)).isEmpty();
        // percent-encoding of anything else is fine
        RepositoryRequest get = request(hostedRepo, "GET", "v2/conans/search");
        http(get).setRequestURI("/repository/conan-hosted/v2/conans/%73earch");
        assertThat(handler.handle(get).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void headOnAuthenticateIssuesNoToken() throws Exception {
        ResponseEntity<?> head = handler.handle(as(request(hostedRepo, "HEAD", "v2/users/authenticate"), "alice"));
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        verify(tokens, never()).issue(any(), anyString(), any());
        assertThat(handler.handle(request(hostedRepo, "HEAD", "v2/users/authenticate")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ifNoneMatchStarOnADocumentIsNotModified() throws Exception {
        publish(hostedRepo, ZLIB, RREV, PID, PREV, "star");
        RepositoryRequest latest = request(hostedRepo, "GET", ConanRoutes.recipeLatest(ZLIB));
        http(latest).addHeader(HttpHeaders.IF_NONE_MATCH, "*");
        assertThat(handler.handle(latest).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        RepositoryRequest other = request(hostedRepo, "GET", ConanRoutes.recipeLatest(ZLIB));
        http(other).addHeader(HttpHeaders.IF_NONE_MATCH, "\"something-else\"");
        assertThat(handler.handle(other).getStatusCode()).isEqualTo(HttpStatus.OK);

        RepositoryRequest file = request(hostedRepo, "GET", ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py"));
        http(file).addHeader(HttpHeaders.IF_NONE_MATCH, "*");
        ResponseEntity<?> notModified = handler.handle(file);
        assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModified.getHeaders().getETag()).isNotBlank();
        assertThat(storage.downloads).isEmpty();
        RepositoryRequest missing = request(hostedRepo, "GET", ConanRoutes.recipeFile(ZLIB, RREV, "conan_sources.tgz"));
        http(missing).addHeader(HttpHeaders.IF_NONE_MATCH, "*");
        assertThat(handler.handle(missing).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void methodNotAllowedNamesTheSupportedMethods() throws Exception {
        String[][] cases = {
                {"POST", ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py"), "GET, HEAD, PUT"},
                {"PUT", ConanRoutes.recipeLatest(ZLIB), "GET, HEAD"},
                {"PUT", ConanRoutes.recipeRevisions(ZLIB), "GET, HEAD, DELETE"},
                {"DELETE", "v2/users/authenticate", "GET, HEAD, POST"},
                {"POST", "v1/ping", "GET, HEAD"},
        };
        for (String[] c : cases) {
            ResponseEntity<?> response = handler.handle(as(request(hostedRepo, c[0], c[1]), "alice"));
            assertThat(response.getStatusCode()).as(c[0] + " " + c[1]).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).as(c[0] + " " + c[1]).isEqualTo(c[2]);
            assertThat(json(response).path("errors").path(0).path("status").asInt()).isEqualTo(405);
        }
    }

    @Test
    void hostedMetadataFilesAreMutableAndRevisionFilesAreNot() throws Exception {
        publish(hostedRepo, ZLIB, RREV, PID, PREV, "meta");
        handler.handle(upload(hostedRepo, ConanRoutes.recipeFile(ZLIB, RREV, "metadata/logs/build.log"), utf8("log"), "alice"));
        assertThat(storage.find(hostedRepo, ConanLayout.exportPath(ZLIB, RREV, "metadata/logs/build.log")).orElseThrow()
                .asset().isMetadata()).isTrue();
        assertThat(storage.find(hostedRepo, ConanLayout.exportPath(ZLIB, RREV, "conanfile.py")).orElseThrow()
                .asset().isMetadata()).isFalse();
    }

    @Test
    void proxySearchesNeverCachedAreMissesWhileTheUpstreamCannotAnswer() throws Exception {
        upstream.put(UPSTREAM + "/v2/conans/search?q=zlib", "{\"results\":[\"zlib/1.3.1\"]}");
        assertThat(json(handler.handle(request(proxyRepo, "GET", "v2/conans/search?q=zlib"))).path("results")).hasSize(1);

        upstream.offline = true;
        // cached: served as it is; never cached: a 404 the dispatcher turns into 503 upstream.offline
        assertThat(json(handler.handle(request(proxyRepo, "GET", "v2/conans/search?q=zlib"))).path("results")).hasSize(1);
        assertThat(handler.handle(request(proxyRepo, "GET", "v2/conans/search?q=openssl")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // a group still answers with what its other members know
        publish(hostedRepo, ACME, RREV, PID, PREV, "acme");
        JsonNode merged = json(handler.handle(request(groupRepo, "GET", "v2/conans/search?q=*")));
        assertThat(merged.path("results")).hasSize(1);
        assertThat(merged.path("results").get(0).stringValue()).isEqualTo("acme/1.0@team/stable");

        upstream.offline = false;
        upstream.down = true;
        assertThat(handler.handle(request(proxyRepo, "GET", "v2/conans/search?q=boost")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        upstream.down = false;
        // an upstream that answers without a document has nothing: an empty result, not an error
        assertThat(json(handler.handle(request(proxyRepo, "GET", "v2/conans/search?q=boost"))).path("results")).isEmpty();
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Uploads a recipe revision and one binary the way {@code conan upload} does, in its file order. */
    private Map<String, byte[]> publish(Repository repo, ConanReference ref, String rrev, String pid, String prev,
                                        String seed) throws Exception {
        Map<String, byte[]> recipe = new java.util.LinkedHashMap<>();
        recipe.put("conan_export.tgz", tgz("export " + seed));
        recipe.put("conanfile.py", utf8("conanfile " + seed));
        recipe.put("conanmanifest.txt", utf8("1700000000\nconanfile.py: " + seed + "\n"));
        for (Map.Entry<String, byte[]> file : recipe.entrySet()) {
            ResponseEntity<?> response = handler.handle(upload(repo, ConanRoutes.recipeFile(ref, rrev, file.getKey()),
                    file.getValue(), "alice"));
            assertThat(response.getStatusCode().is2xxSuccessful()).as(file.getKey() + ": " + body(response)).isTrue();
        }
        Map<String, byte[]> all = new java.util.HashMap<>(recipe);
        Map<String, byte[]> binary = publishPackage(repo, ref, rrev, pid, prev, seed);
        all.put("conan_package.tgz", binary.get("conan_package.tgz"));
        all.put("conaninfo.txt", binary.get("conaninfo.txt"));
        return all;
    }

    /** Uploads one binary package revision of an already uploaded recipe revision. */
    private Map<String, byte[]> publishPackage(Repository repo, ConanReference ref, String rrev, String pid, String prev,
                                               String seed) throws Exception {
        Map<String, byte[]> binary = new java.util.LinkedHashMap<>();
        binary.put("conan_package.tgz", tgz("package " + seed));
        binary.put("conaninfo.txt", utf8(CONANINFO));
        binary.put("conanmanifest.txt", utf8("1700000000\nconaninfo.txt: " + seed + "\n"));
        for (Map.Entry<String, byte[]> file : binary.entrySet()) {
            ResponseEntity<?> response = handler.handle(upload(repo, ConanRoutes.packageFile(ref, rrev, pid, prev, file.getKey()),
                    file.getValue(), "alice"));
            assertThat(response.getStatusCode().is2xxSuccessful()).as(file.getKey() + ": " + body(response)).isTrue();
        }
        return binary;
    }

    private void stubUpstreamRecipe(ConanReference ref, String rrev, byte[] export) {
        String time = "2025-11-03T10:20:30.123+0000";
        upstream.put(UPSTREAM + "/" + ConanRoutes.recipeLatest(ref), "{\"revision\":\"" + rrev + "\",\"time\":\"" + time + "\"}");
        upstream.put(UPSTREAM + "/" + ConanRoutes.recipeRevisions(ref),
                "{\"revisions\":[{\"revision\":\"" + rrev + "\",\"time\":\"" + time + "\"}]}");
        upstream.put(UPSTREAM + "/" + ConanRoutes.recipeFiles(ref, rrev),
                "{\"files\":{\"conan_export.tgz\":{},\"conanfile.py\":{},\"conanmanifest.txt\":{}}}");
        upstream.put(UPSTREAM + "/" + ConanRoutes.recipeFile(ref, rrev, "conan_export.tgz"), export);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
