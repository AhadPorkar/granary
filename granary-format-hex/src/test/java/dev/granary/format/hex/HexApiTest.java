package dev.granary.format.hex;

import static dev.granary.format.hex.HexTestSupport.ERLANG;
import static dev.granary.format.hex.HexTestSupport.admin;
import static dev.granary.format.hex.HexTestSupport.anonymous;
import static dev.granary.format.hex.HexTestSupport.body;
import static dev.granary.format.hex.HexTestSupport.goldenKey;
import static dev.granary.format.hex.HexTestSupport.group;
import static dev.granary.format.hex.HexTestSupport.hosted;
import static dev.granary.format.hex.HexTestSupport.http;
import static dev.granary.format.hex.HexTestSupport.proxy;
import static dev.granary.format.hex.HexTestSupport.reader;
import static dev.granary.format.hex.HexTestSupport.release;
import static dev.granary.format.hex.HexTestSupport.request;
import static dev.granary.format.hex.HexTestSupport.termList;
import static dev.granary.format.hex.HexTestSupport.termMap;
import static dev.granary.format.hex.HexTestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.ApiToken;
import dev.granary.core.model.Repository;
import dev.granary.core.model.User;
import dev.granary.core.security.ApiTokenService;
import dev.granary.core.security.GranaryPrincipal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The Hex HTTP API with requests shaped like the ones mix 2.5 sends (recorded from the real client). */
class HexApiTest {

    @TempDir
    Path tempDir;

    private HexFixture fx;
    private final Repository hostedRepo = hosted("hex-hosted");
    private final Repository proxyRepo = proxy("hexpm", HexFormatHandler.DEFAULT_REMOTE);
    private final Repository groupRepo = group("hex-group", "hex-hosted", "hexpm");
    private final GranaryPrincipal alice = user("alice");

    @BeforeEach
    void setUp() {
        fx = new HexFixture(goldenKey(tempDir));
        fx.members(groupRepo, hostedRepo, proxyRepo);
    }

    private ResponseEntity<?> handle(RepositoryRequest request) throws Exception {
        return fx.handler.handle(request);
    }

    /** {@code POST api/packages/<name>/releases?replace=false} with the tarball, as mix hex.publish sends it. */
    private ResponseEntity<?> publish(Repository repo, String name, byte[] tarball, boolean replace) throws Exception {
        return handle(request(repo, "POST", "api/packages/" + name + "/releases?replace=" + replace, tarball,
                "application/octet-stream", alice));
    }

    private static byte[] etf(Map<String, Object> map) {
        return Etf.encode(map);
    }

    @Test
    void publishAnswersTheReleaseInErlangTerms() throws Exception {
        byte[] tarball = release("acme", "1.0.0");
        ResponseEntity<?> response = publish(hostedRepo, "acme", tarball, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getContentType().toString()).startsWith(ERLANG);
        Map<String, Object> release = termMap(response);
        assertThat(release).containsEntry("version", "1.0.0")
                .containsEntry("checksum", HexTarball.read(tarball).outerChecksumHex())
                .containsEntry("inner_checksum", HexTarball.read(tarball).innerChecksumHex())
                .containsEntry("has_docs", false)
                .containsEntry("url", "http://granary.test/repository/hex-hosted/api/packages/acme/releases/1.0.0")
                .containsEntry("package_url", "http://granary.test/repository/hex-hosted/api/packages/acme");
        assertThat(Etf.asMap(release.get("publisher"))).containsEntry("username", "alice");
        assertThat(Etf.asMap(release.get("configs"))).containsEntry("mix.exs", "{:acme, \"~> 1.0\", repo: \"hex-hosted\"}");
    }

    @Test
    void republishIsA422InTheShapeMixPrints() throws Exception {
        publish(hostedRepo, "acme", release("acme", "1.0.0"), false);
        ResponseEntity<?> again = publish(hostedRepo, "acme", release("acme", "1.0.0"), false);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        Map<String, Object> error = termMap(again);
        assertThat(error).containsEntry("status", 422L).containsEntry("message", "Validation error(s)");
        assertThat(Etf.asMap(error.get("errors")))
                .containsEntry("inserted_at", "must include the --replace flag to update an existing release");

        ResponseEntity<?> replaceRefused = publish(hostedRepo, "acme", release("acme", "1.0.0"), true);
        assertThat(replaceRefused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        hostedRepo.setAllowRedeploy(true);
        assertThat(publish(hostedRepo, "acme", release("acme", "1.0.0"), true).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theLegacyPublishEndpointAndNameMismatches() throws Exception {
        ResponseEntity<?> legacy = handle(request(hostedRepo, "POST", "api/publish", release("acme", "0.1.0"),
                "application/octet-stream", alice));
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<?> mismatch = publish(hostedRepo, "other", release("acme", "0.2.0"), false);
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(Etf.asMap(termMap(mismatch).get("errors"))).containsKey("name");

        ResponseEntity<?> empty = publish(hostedRepo, "acme", new byte[0], false);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(Etf.asMap(termMap(empty).get("errors"))).containsKey("tar");
    }

    @Test
    void writesOnGroupsAndProxiesAreRefusedWithAHint() throws Exception {
        ResponseEntity<?> group = publish(groupRepo, "acme", release("acme", "1.0.0"), false);
        assertThat(group.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat((String) termMap(group).get("message")).contains("publish to the hosted repository 'hex-hosted'");
        ResponseEntity<?> viaProxy = publish(proxyRepo, "acme", release("acme", "1.0.0"), false);
        assertThat(viaProxy.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handle(request(groupRepo, "DELETE", "api/packages/acme/releases/1.0.0", null, null, alice))
                .getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void packageLookupsForHexInfoAndSearch() throws Exception {
        publish(hostedRepo, "acme", release("acme", "1.0.0"), false);
        publish(hostedRepo, "acme", release("acme", "1.1.0-rc.1"), false);
        publish(hostedRepo, "beta", release("beta", "0.2.0"), false);

        Map<String, Object> pkg = termMap(handle(request(hostedRepo, "GET", "api/packages/acme", null, null, alice)));
        assertThat(pkg).containsEntry("name", "acme").containsEntry("repository", "hex-hosted")
                .containsEntry("latest_version", "1.1.0-rc.1").containsEntry("latest_stable_version", "1.0.0");
        @SuppressWarnings("unchecked")
        List<Object> releases = (List<Object>) pkg.get("releases");
        assertThat(releases).extracting(r -> Etf.asMap(r).get("version")).containsExactly("1.1.0-rc.1", "1.0.0");
        assertThat(Etf.asMap(pkg.get("meta"))).containsEntry("description", "The acme package");
        assertThat(Etf.asMap(pkg.get("configs"))).containsEntry("rebar.config", "{acme, \"1.0.0\"}");

        List<Object> all = termList(handle(request(hostedRepo, "GET", "api/packages?search=&sort=name&page=1", null, null, alice)));
        assertThat(all).extracting(p -> Etf.asMap(p).get("name")).containsExactly("acme", "beta");
        List<Object> found = termList(handle(request(hostedRepo, "GET", "api/packages?search=bet", null, null, alice)));
        assertThat(found).extracting(p -> Etf.asMap(p).get("name")).containsExactly("beta");
        List<Object> byDescription = termList(handle(request(hostedRepo, "GET", "api/packages?search=package", null, null, alice)));
        assertThat(byDescription).hasSize(2);
        assertThat(termList(handle(request(hostedRepo, "GET", "api/packages?search=name:package", null, null, alice)))).isEmpty();
        publish(hostedRepo, "eLDAP", release("eLDAP", "0.1.0"), false); // hex.pm allows upper case after the first letter
        assertThat(termList(handle(request(hostedRepo, "GET", "api/packages?search=ELDAP", null, null, alice))))
                .extracting(p -> Etf.asMap(p).get("name")).containsExactly("eLDAP");
        assertThat(termMap(handle(request(hostedRepo, "GET", "api/packages/eLDAP", null, null, alice))))
                .containsEntry("name", "eLDAP");
        assertThat(termList(handle(request(hostedRepo, "GET", "api/packages?page=2", null, null, alice)))).isEmpty();
        assertThat(termList(handle(request(hostedRepo, "GET", "api/packages?page=x", null, null, alice)))).hasSize(3);

        Map<String, Object> rel = termMap(handle(request(hostedRepo, "GET", "api/packages/acme/releases/1.0.0", null, null, alice)));
        assertThat(rel).containsEntry("version", "1.0.0").containsEntry("downloads", 0L);
        assertThat(termList(handle(request(hostedRepo, "GET", "api/packages/acme/releases", null, null, alice)))).hasSize(2);

        ResponseEntity<?> missing = handle(request(hostedRepo, "GET", "api/packages/nope", null, null, alice));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(termMap(missing)).containsEntry("message", "package nope does not exist");
    }

    @Test
    void groupsAnswerFromTheirHostedMembersAndProxiesDoNotProxyTheApi() throws Exception {
        publish(hostedRepo, "acme", release("acme", "1.0.0"), false);
        Map<String, Object> pkg = termMap(handle(request(groupRepo, "GET", "api/packages/acme", null, null, alice)));
        assertThat(pkg).containsEntry("repository", "hex-group");
        assertThat(termList(handle(request(groupRepo, "GET", "api/packages", null, null, alice)))).hasSize(1);
        ResponseEntity<?> proxied = handle(request(proxyRepo, "GET", "api/packages/acme", null, null, alice));
        assertThat(proxied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((String) termMap(proxied).get("message")).contains("not proxied");
        assertThat(handle(request(proxyRepo, "GET", "api/packages", null, null, alice)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void jsonForClientsThatAskForIt() throws Exception {
        publish(hostedRepo, "acme", release("acme", "1.0.0"), false);
        RepositoryRequest request = request(hostedRepo, "GET", "api/packages/acme", null, null, alice);
        http(request).removeHeader("Accept");
        http(request).addHeader("Accept", "application/json");
        ResponseEntity<?> response = handle(request);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json");
        assertThat(body(response)).contains("\"name\":\"acme\"").contains("\"html_url\":null");
    }

    @Test
    void retireAndUnretireWithTheBodyMixSends() throws Exception {
        publish(hostedRepo, "acme", release("acme", "1.0.0"), false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "use 1.0.1");
        body.put("reason", "security");
        ResponseEntity<?> retire = handle(request(hostedRepo, "POST", "api/packages/acme/releases/1.0.0/retire", etf(body),
                ERLANG, alice));
        assertThat(retire.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> rel = termMap(handle(request(hostedRepo, "GET", "api/packages/acme/releases/1.0.0", null, null, alice)));
        assertThat(Etf.asMap(rel.get("retirement"))).containsEntry("reason", "security").containsEntry("message", "use 1.0.1");
        Map<String, Object> pkg = termMap(handle(request(hostedRepo, "GET", "api/packages/acme", null, null, alice)));
        assertThat(Etf.asMap(pkg.get("retirements"))).containsKey("1.0.0");

        ResponseEntity<?> unretire = handle(request(hostedRepo, "DELETE", "api/packages/acme/releases/1.0.0/retire", null, null, alice));
        assertThat(unretire.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        rel = termMap(handle(request(hostedRepo, "GET", "api/packages/acme/releases/1.0.0", null, null, alice)));
        assertThat(rel.get("retirement")).isNull();

        ResponseEntity<?> json = handle(request(hostedRepo, "POST", "api/packages/acme/releases/1.0.0/retire",
                "{\"reason\":\"deprecated\"}".getBytes(StandardCharsets.UTF_8), "application/json", alice));
        assertThat(json.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<?> badReason = handle(request(hostedRepo, "POST", "api/packages/acme/releases/1.0.0/retire",
                etf(Map.of("reason", "bored")), ERLANG, alice));
        assertThat(badReason.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<?> malformed = handle(request(hostedRepo, "POST", "api/packages/acme/releases/1.0.0/retire",
                new byte[] {(byte) 131, 116, 0, 0, 0, 9}, ERLANG, alice));
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<?> notAMap = handle(request(hostedRepo, "POST", "api/packages/acme/releases/1.0.0/retire",
                Etf.encode(List.of()), ERLANG, alice));
        assertThat(notAMap.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<?> badJson = handle(request(hostedRepo, "POST", "api/packages/acme/releases/1.0.0/retire",
                "[1]".getBytes(StandardCharsets.UTF_8), "application/json", alice));
        assertThat(badJson.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<?> tooBig = handle(request(hostedRepo, "POST", "api/packages/acme/releases/1.0.0/retire",
                new byte[HexApi.MAX_BODY + 1], "application/json", alice));
        assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void revertThroughTheApi() throws Exception {
        publish(hostedRepo, "acme", release("acme", "1.0.0"), false);
        ResponseEntity<?> revert = handle(request(hostedRepo, "DELETE", "api/packages/acme/releases/1.0.0", null, null, alice));
        assertThat(revert.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handle(request(hostedRepo, "GET", "api/packages/acme", null, null, alice)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handle(request(hostedRepo, "GET", "packages/acme", null, null, alice)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void documentationThroughTheApi() throws Exception {
        publish(hostedRepo, "acme", release("acme", "1.0.0"), false);
        ResponseEntity<?> upload = handle(request(hostedRepo, "POST", "api/packages/acme/releases/1.0.0/docs",
                HexTestSupport.docsTarball(), "application/octet-stream", alice));
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(upload.getHeaders().getLocation().toString())
                .isEqualTo("http://granary.test/repository/hex-hosted/docs/acme-1.0.0.tar.gz");
        ResponseEntity<?> redirect = handle(request(hostedRepo, "GET", "api/packages/acme/releases/1.0.0/docs", null, null, alice));
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(redirect.getHeaders().getLocation()).isEqualTo(upload.getHeaders().getLocation());
        assertThat(handle(request(hostedRepo, "DELETE", "api/packages/acme/releases/1.0.0/docs", null, null, alice))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handle(request(hostedRepo, "GET", "api/packages/acme/releases/1.0.0/docs", null, null, alice))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void usersMeAndAuth() throws Exception {
        User user = new User();
        user.setUsername("alice");
        user.setEmail("alice@example.test");
        user.setDisplayName("Alice");
        when(fx.users.find("alice")).thenReturn(Optional.of(user));

        Map<String, Object> me = termMap(handle(request(hostedRepo, "GET", "api/users/me", null, null, alice)));
        assertThat(me).containsEntry("username", "alice").containsEntry("email", "alice@example.test")
                .containsEntry("full_name", "Alice");

        // rebar3 hex user whoami turns email and full_name into strings, with a default only for absent keys
        User bare = new User();
        bare.setUsername("carol");
        when(fx.users.find("carol")).thenReturn(Optional.of(bare));
        Map<String, Object> carol = termMap(handle(request(hostedRepo, "GET", "api/users/me", null, null, user("carol"))));
        assertThat(carol).containsEntry("username", "carol").doesNotContainKeys("email", "full_name", "inserted_at")
                .doesNotContainValue(null);

        ResponseEntity<?> anonymousMe = handle(request(hostedRepo, "GET", "api/users/me", null, null, anonymous()));
        assertThat(anonymousMe.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymousMe.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic");
        assertThat(handle(request(hostedRepo, "GET", "api/users/me", null, null, user("ghost"))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(handle(request(hostedRepo, "GET", "api/auth?domain=api&resource=write", null, null, alice))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handle(request(hostedRepo, "GET", "api/auth?domain=api&resource=write", null, null, reader("bob")))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(handle(request(hostedRepo, "GET", "api/auth?domain=repository&resource=hex-hosted", null, null, reader("bob")))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handle(request(hostedRepo, "GET", "api/auth?domain=weird", null, null, admin("root")))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(handle(request(hostedRepo, "GET", "api/auth", null, null, anonymous())).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void apiKeysMapToGranaryTokens() throws Exception {
        User user = new User();
        user.setUsername("alice");
        when(fx.users.find("alice")).thenReturn(Optional.of(user));
        ApiToken existing = mock(ApiToken.class);
        UUID existingId = UUID.randomUUID();
        when(existing.getName()).thenReturn("laptop");
        when(existing.getId()).thenReturn(existingId);
        when(fx.tokens.list(user)).thenReturn(List.of(existing));

        List<Object> keys = termList(handle(request(hostedRepo, "GET", "api/keys", null, null, alice)));
        assertThat(keys).extracting(k -> Etf.asMap(k).get("name")).containsExactly("laptop");
        assertThat(termMap(handle(request(hostedRepo, "GET", "api/keys/laptop", null, null, alice))))
                .containsEntry("name", "laptop");
        assertThat(handle(request(hostedRepo, "GET", "api/keys/other", null, null, alice)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ApiToken created = mock(ApiToken.class);
        when(created.getName()).thenReturn("ci");
        when(fx.tokens.issue(eq(user), eq("ci"), any())).thenReturn(new ApiTokenService.IssuedToken(created, "gry_secret"));
        ResponseEntity<?> create = handle(request(hostedRepo, "POST", "api/keys",
                etf(Map.of("name", "ci", "permissions", List.of())), ERLANG, alice));
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(termMap(create)).containsEntry("name", "ci").containsEntry("secret", "gry_secret");
        assertThat(handle(request(hostedRepo, "POST", "api/keys", etf(Map.of("name", " ")), ERLANG, alice)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        assertThat(handle(request(hostedRepo, "DELETE", "api/keys/laptop", null, null, alice)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        verify(fx.tokens).revoke(user, existingId);
        assertThat(handle(request(hostedRepo, "DELETE", "api/keys/none", null, null, alice)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handle(request(hostedRepo, "DELETE", "api/keys", null, null, alice)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handle(request(hostedRepo, "GET", "api/keys", null, null, anonymous())).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void apiRootAndUnsupportedMethods() throws Exception {
        Map<String, Object> root = termMap(handle(request(hostedRepo, "GET", "api", null, null, alice)));
        assertThat(root).containsEntry("repository", "hex-hosted")
                .containsEntry("public_key_url", "http://granary.test/repository/hex-hosted/public_key");
        assertThat(handle(request(hostedRepo, "PUT", "api/packages/acme", null, null, alice)).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handle(request(hostedRepo, "GET", "api/publish", null, null, alice)).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handle(request(hostedRepo, "PATCH", "api/packages/acme/releases/1.0.0", null, null, alice)).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
