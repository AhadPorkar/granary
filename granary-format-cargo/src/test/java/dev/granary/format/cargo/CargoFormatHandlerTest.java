package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import dev.granary.core.GranaryException;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.storage.Checksums;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.node.ObjectNode;

class CargoFormatHandlerTest {

    @TempDir
    Path temp;

    private CargoFixture fx;
    private CargoFormatHandler handler;
    private Repository hosted;
    private Repository proxy;
    private Repository group;

    @BeforeEach
    void setUp() {
        fx = new CargoFixture(temp);
        handler = fx.handler;
        hosted = CargoTestSupport.hosted("cargo-hosted");
        proxy = CargoTestSupport.proxy("cargo-proxy", "https://index.crates.io/");
        group = CargoTestSupport.group("cargo-group", "cargo-hosted", "cargo-proxy");
        fx.group(group, hosted, proxy);
    }

    private ResponseEntity<?> call(Repository repo, String method, String path) throws IOException {
        return handler.handle(CargoTestSupport.request(repo, method, path));
    }

    private ResponseEntity<?> call(Repository repo, String method, String path, byte[] body) throws IOException {
        return handler.handle(CargoTestSupport.request(repo, method, path, body, Map.of()));
    }

    private ResponseEntity<?> publish(Repository repo, String name, String version) throws IOException {
        return call(repo, "PUT", "api/v1/crates/new", CargoTestSupport.publishBody(name, version));
    }

    @Test
    void identity() {
        assertThat(handler.format()).isEqualTo("cargo");
        assertThat(handler.displayName()).isEqualTo("Cargo (crates.io)");
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder(RepositoryType.values());
        assertThat(handler.routeRoot(null)).isEmpty();
    }

    @Test
    void templates() {
        List<RepositoryTemplate> templates = handler.templates();
        assertThat(templates).extracting(RepositoryTemplate::name).containsExactly("cargo-proxy", "cargo-hosted", "cargo-group");
        assertThat(templates).allSatisfy(t -> assertThat(t.format()).isEqualTo("cargo"));
        assertThat(templates.get(0).type()).isEqualTo(RepositoryType.PROXY);
        assertThat(templates.get(0).remoteUrl()).isEqualTo("https://index.crates.io/");
        assertThat(templates.get(1).type()).isEqualTo(RepositoryType.HOSTED);
        assertThat(templates.get(2).type()).isEqualTo(RepositoryType.GROUP);
        assertThat(templates.get(2).members()).containsExactly("cargo-hosted", "cargo-proxy");
    }

    @Test
    void settingsAndValidation() {
        assertThat(handler.settings()).extracting(d -> d.key())
                .containsExactly("cargo.authRequired", "cargo.downloadUrl", "cargo.apiUrl");
        proxy.setFormatSettings(Map.of("cargo.downloadUrl", "not a url {crate}"));
        assertThatThrownBy(() -> handler.validate(proxy)).isInstanceOf(GranaryException.class);
        proxy.setFormatSettings(Map.of());
        handler.validate(proxy);
    }

    @Test
    void snippetsPointGroupsAtTheirHostedMember() {
        ConnectContext ctx = new ConnectContext(group, "http://g:1/repository/cargo-group", "http://g:1", "u", "<api-token>", true);
        List<ConnectSnippet> snippets = handler.connectSnippets(ctx);
        assertThat(snippets).filteredOn(s -> s.id().equals("cargo-publish")).singleElement()
                .satisfies(s -> assertThat(s.code()).contains("sparse+http://g:1/repository/cargo-hosted/index/"));
        ConnectContext proxyCtx = new ConnectContext(proxy, "http://g:1/repository/cargo-proxy", "http://g:1", "u", "t", true);
        assertThat(handler.connectSnippets(proxyCtx)).extracting(ConnectSnippet::id).doesNotContain("cargo-publish");
    }

    @Test
    void metadataPaths() {
        assertThat(handler.isMetadataPath("index/config.json")).isTrue();
        assertThat(handler.isMetadataPath("index/se/rd/serde")).isTrue();
        assertThat(handler.isMetadataPath("/index/se/rd/serde")).isTrue();
        assertThat(handler.isMetadataPath("meta/serde.json")).isTrue();
        assertThat(handler.isMetadataPath("api/v1/crates")).isTrue();
        assertThat(handler.isMetadataPath("crates/serde/serde-1.0.0.crate")).isFalse();
        assertThat(handler.isMetadataPath("api/v1/crates/serde/1.0.0/download")).isFalse();
        assertThat(handler.isMetadataPath(null)).isFalse();
    }

    @Test
    void requiredAccess() {
        assertThat(access("GET", "me")).isEqualTo(AccessLevel.NONE);
        assertThat(access("GET", "")).isEqualTo(AccessLevel.NONE);
        assertThat(access("GET", "index/config.json")).isEqualTo(AccessLevel.READ);
        assertThat(access("HEAD", "index/se/rd/serde")).isEqualTo(AccessLevel.READ);
        assertThat(access("GET", "api/v1/crates/serde/1.0.0/download")).isEqualTo(AccessLevel.READ);
        assertThat(access("GET", "api/v1/crates")).isEqualTo(AccessLevel.READ);
        assertThat(access("GET", "api/v1/crates/serde/owners")).isEqualTo(AccessLevel.READ);
        assertThat(access("PUT", "api/v1/crates/new")).isEqualTo(AccessLevel.WRITE);
        assertThat(access("DELETE", "api/v1/crates/serde/1.0.0/yank")).isEqualTo(AccessLevel.WRITE);
        assertThat(access("PUT", "api/v1/crates/serde/1.0.0/unyank")).isEqualTo(AccessLevel.WRITE);
        assertThat(access("PUT", "api/v1/crates/serde/owners")).isEqualTo(AccessLevel.WRITE);
        assertThat(access("DELETE", "api/v1/crates/serde/owners")).isEqualTo(AccessLevel.WRITE);
        assertThat(access("POST", "whatever")).isEqualTo(AccessLevel.WRITE);
    }

    private AccessLevel access(String method, String path) {
        return handler.requiredAccess(CargoTestSupport.request(hosted, method, path));
    }

    @Test
    void configNamesTheRepositoryTheClientAsked() throws IOException {
        ResponseEntity<?> response = call(group, "GET", "index/config.json");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(CargoTestSupport.text(response)).isEqualTo("{\"dl\":\"http://granary.test/repository/cargo-group/api/v1/crates\","
                + "\"api\":\"http://granary.test/repository/cargo-group\",\"auth-required\":false}");
        assertThat(response.getHeaders().getETag()).isNotNull();
    }

    @Test
    void configRequiresAuthWhenSetOrWhenAnonymousCannotRead() throws IOException {
        hosted.setFormatSettings(Map.of("cargo.authRequired", "true"));
        assertThat(CargoTestSupport.json(call(hosted, "GET", "index/config.json")).get("auth-required").booleanValue()).isTrue();
        hosted.setFormatSettings(Map.of());
        when(fx.authentication.anonymous()).thenReturn(GranaryPrincipal.anonymous(Set.of()));
        assertThat(CargoTestSupport.json(call(hosted, "GET", "index/config.json")).get("auth-required").booleanValue()).isTrue();
    }

    @Test
    void publishThenReadTheIndexAndDownload() throws IOException {
        ResponseEntity<?> published = publish(hosted, "acme-hello", "0.1.0");
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(CargoTestSupport.json(published).has("warnings")).isTrue();

        ResponseEntity<?> index = call(hosted, "GET", "index/ac/me/acme-hello");
        assertThat(index.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(index.getHeaders().getContentType().toString()).isEqualTo("text/plain;charset=utf-8");
        IndexEntry line = IndexEntry.parseFile(CargoTestSupport.text(index)).getFirst();
        assertThat(line.name()).isEqualTo("acme-hello");

        ResponseEntity<?> download = call(hosted, "GET", "api/v1/crates/acme-hello/0.1.0/download");
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Checksums.hex("SHA-256", CargoTestSupport.body(download))).isEqualTo(line.cksum());
        assertThat(download.getHeaders().getETag()).isEqualTo("\"" + line.cksum() + "\"");

        // the same through the group
        assertThat(CargoTestSupport.text(call(group, "GET", "index/ac/me/acme-hello"))).isEqualTo(CargoTestSupport.text(index));
        assertThat(CargoTestSupport.body(call(group, "GET", "api/v1/crates/Acme-Hello/0.1.0/download")))
                .isEqualTo(CargoTestSupport.body(download));
    }

    @Test
    void duplicatePublishIsAConflictWithCargosErrorDocument() throws IOException {
        publish(hosted, "acme", "1.0.0");
        ResponseEntity<?> again = publish(hosted, "acme", "1.0.0");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(CargoTestSupport.errorDetail(again)).isEqualTo("crate version `1.0.0` is already uploaded");
    }

    @Test
    void malformedPublishesAreBadRequests() throws IOException {
        assertThat(call(hosted, "PUT", "api/v1/crates/new", new byte[0]).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ObjectNode meta = CargoTestSupport.metadata("acme", "not-semver");
        ResponseEntity<?> response = call(hosted, "PUT", "api/v1/crates/new",
                CargoTestSupport.publishBody(meta, CargoTestSupport.crate("acme", "not-semver")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(CargoTestSupport.errorDetail(response)).contains("not a valid semver version");
        try (Stream<Path> files = Files.list(temp.resolve("tmp").resolve("cargo"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void encodedSeparatorsInTheUrlAreBadRequests() throws IOException {
        publish(hosted, "acme-hello", "0.1.0");
        for (String raw : List.of("index/ac%2Fme/acme-hello", "index/ac/me/acme%2fhello", "index/ac%5Cme/acme-hello",
                "api/v1/crates/acme-hello/0.1.0/download%2F", "api/v1/crates/acme-hello%2F0.1.0/download")) {
            org.springframework.mock.web.MockHttpServletRequest http =
                    new org.springframework.mock.web.MockHttpServletRequest("GET", "/repository/cargo-hosted/" + raw);
            String decoded = java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
            ResponseEntity<?> response = handler.handle(new RepositoryRequest(hosted, decoded, http,
                    CargoTestSupport.user("alice"), CargoTestSupport.baseUrl(hosted)));
            assertThat(response.getStatusCode()).as(raw).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(CargoTestSupport.errorDetail(response)).isEqualTo("invalid crate name, version or path");
        }
        // the same paths without encoding still work
        assertThat(call(hosted, "GET", "index/ac/me/acme-hello").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(hosted, "GET", "api/v1/crates/acme-hello/0.1.0/download").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void writesToGroupsAndProxiesNameTheHostedMember() throws IOException {
        ResponseEntity<?> response = publish(group, "acme", "1.0.0");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(CargoTestSupport.errorDetail(response)).isEqualTo("cannot publish crates in `cargo-group`: it is a group "
                + "repository; use its hosted member `cargo-hosted` instead");
        assertThat(CargoTestSupport.errorDetail(call(proxy, "DELETE", "api/v1/crates/acme/1.0.0/yank")))
                .isEqualTo("cannot yank crates in `cargo-proxy`: it is a proxy repository; use a hosted repository instead");
        ResponseEntity<?> owners = call(group, "PUT", "api/v1/crates/acme/owners",
                "{\"users\":[\"bob\"]}".getBytes(StandardCharsets.UTF_8));
        assertThat(owners.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(CargoTestSupport.errorDetail(owners)).isEqualTo("cannot change the owners of crates in `cargo-group`: "
                + "it is a group repository; use its hosted member `cargo-hosted` instead");
    }

    @Test
    void yankUnyankAndOwnersOverHttp() throws IOException {
        publish(hosted, "acme", "1.0.0");
        assertThat(CargoTestSupport.text(call(hosted, "DELETE", "api/v1/crates/acme/1.0.0/yank"))).isEqualTo("{\"ok\":true}");
        assertThat(CargoTestSupport.text(call(hosted, "GET", "index/ac/me/acme"))).contains("\"yanked\":true");
        assertThat(CargoTestSupport.text(call(hosted, "PUT", "api/v1/crates/acme/1.0.0/unyank"))).isEqualTo("{\"ok\":true}");
        assertThat(CargoTestSupport.text(call(hosted, "GET", "index/ac/me/acme"))).contains("\"yanked\":false");
        assertThat(call(hosted, "DELETE", "api/v1/crates/acme/2.0.0/yank").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(CargoTestSupport.text(call(hosted, "GET", "api/v1/crates/acme/owners")))
                .isEqualTo("{\"users\":[{\"id\":1,\"login\":\"alice\",\"name\":\"alice\"}]}");
        ResponseEntity<?> added = call(hosted, "PUT", "api/v1/crates/acme/owners", "{\"users\":[\"bob\"]}".getBytes(StandardCharsets.UTF_8));
        assertThat(CargoTestSupport.json(added).get("ok").booleanValue()).isTrue();
        assertThat(CargoTestSupport.json(added).get("msg").stringValue()).contains("bob");
        assertThat(CargoTestSupport.text(call(group, "GET", "api/v1/crates/acme/owners"))).contains("\"login\":\"bob\"");
        ResponseEntity<?> removed = call(hosted, "DELETE", "api/v1/crates/acme/owners", "{\"users\":[\"alice\"]}".getBytes(StandardCharsets.UTF_8));
        assertThat(CargoTestSupport.json(removed).get("msg").stringValue()).isEqualTo("owners successfully removed");
        assertThat(call(hosted, "PUT", "api/v1/crates/acme/owners", "{}".getBytes(StandardCharsets.UTF_8)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(hosted, "PUT", "api/v1/crates/acme/owners", "{\"users\":[1]}".getBytes(StandardCharsets.UTF_8)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(hosted, "PUT", "api/v1/crates/acme/owners", "nope".getBytes(StandardCharsets.UTF_8)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(proxy, "GET", "api/v1/crates/acme/owners").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(group, "GET", "api/v1/crates/nope/owners").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void searchOverHttp() throws IOException {
        publish(hosted, "acme", "1.0.0");
        ObjectNode result = CargoTestSupport.json(call(hosted, "GET", "api/v1/crates?q=acme&per_page=5"));
        assertThat(CargoJson.write(result)).isEqualTo("{\"crates\":[{\"name\":\"acme\",\"max_version\":\"1.0.0\","
                + "\"description\":\"Hello from acme\"}],\"meta\":{\"total\":1}}");
        // nonsense paging parameters fall back to the defaults
        assertThat(call(hosted, "GET", "api/v1/crates?q=acme&per_page=x&page=-3").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void missingThingsAre404sInCargoShape() throws IOException {
        ResponseEntity<?> index = call(hosted, "GET", "index/no/pe/nope");
        assertThat(index.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(CargoTestSupport.errorDetail(index)).isEqualTo("crate `nope` not found");
        assertThat(call(hosted, "GET", "api/v1/crates/nope/1.0.0/download").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(hosted, "GET", "index/xx/yy/nope").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(hosted, "GET", "nothing/here").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void oddPathsAreBadRequests() throws IOException {
        ResponseEntity<?> response = call(hosted, "GET", "api/v1/crates/a b/1.0.0/download");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(CargoTestSupport.errorDetail(response)).isEqualTo("invalid crate name, version or path");
        assertThat(call(hosted, "GET", "index/2/a\\b").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void wrongMethods() throws IOException {
        assertThat(call(hosted, "POST", "index/config.json").getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(call(hosted, "GET", "api/v1/crates/new").getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(call(hosted, "PUT", "api/v1/crates/acme/1.0.0/yank").getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(call(hosted, "DELETE", "api/v1/crates/acme/1.0.0/unyank").getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(call(hosted, "POST", "api/v1/crates/acme/owners").getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(call(hosted, "PUT", "api/v1/crates/acme/1.0.0/download").getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(call(hosted, "DELETE", "index/1/a").getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(call(hosted, "POST", "api/v1/crates").getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(call(hosted, "POST", "me").getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(call(hosted, "POST", "").getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void pagesExplainHowToLogIn() throws IOException {
        ResponseEntity<?> me = call(hosted, "GET", "me");
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(CargoTestSupport.text(me)).contains("cargo login --registry granary").contains("http://granary.test/account");
        assertThat(CargoTestSupport.text(call(group, "GET", ""))).contains("sparse+http://granary.test/repository/cargo-group/index/");
    }

    @Test
    void headRequestsCarryNoBody() throws IOException {
        publish(hosted, "acme", "1.0.0");
        ResponseEntity<?> head = call(hosted, "HEAD", "index/ac/me/acme");
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isPositive();
        ResponseEntity<?> crate = call(hosted, "HEAD", "api/v1/crates/acme/1.0.0/download");
        assertThat(crate.getBody()).isNull();
        assertThat(crate.getHeaders().getContentLength()).isPositive();
    }

    @Test
    void conditionalIndexRequests() throws IOException {
        publish(hosted, "acme", "1.0.0");
        String etag = call(hosted, "GET", "index/ac/me/acme").getHeaders().getETag();
        RepositoryRequest conditional = CargoTestSupport.request(hosted, "GET", "index/ac/me/acme", null, Map.of("If-None-Match", etag));
        assertThat(handler.handle(conditional).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        publish(hosted, "acme", "1.1.0");
        assertThat(handler.handle(CargoTestSupport.request(hosted, "GET", "index/ac/me/acme", null, Map.of("If-None-Match", etag)))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
