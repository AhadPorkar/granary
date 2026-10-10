package dev.granary.server.cargo;

import static dev.granary.server.cargo.CargoFixtures.api;
import static dev.granary.server.cargo.CargoFixtures.crate;
import static dev.granary.server.cargo.CargoFixtures.download;
import static dev.granary.server.cargo.CargoFixtures.errorDetail;
import static dev.granary.server.cargo.CargoFixtures.index;
import static dev.granary.server.cargo.CargoFixtures.json;
import static dev.granary.server.cargo.CargoFixtures.lines;
import static dev.granary.server.cargo.CargoFixtures.metadata;
import static dev.granary.server.cargo.CargoFixtures.publish;
import static dev.granary.server.cargo.CargoFixtures.publishBody;
import static dev.granary.server.cargo.CargoFixtures.repositoryUrl;
import static dev.granary.server.cargo.CargoFixtures.sha256;
import static dev.granary.server.cargo.CargoFixtures.versions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.node.ObjectNode;

/** Publishing to a hosted registry and reading it back the way cargo does, through the real HTTP surface. */
class CargoHostedIntegrationTest extends IntegrationTest {

    private static final String REPO = "cargo-hosted";
    private static final String URL = "/repository/" + REPO;
    private static final List<String> TEST_USERS = List.of("cargo-bob", "cargo-carol");

    @Autowired
    private StorageService storage;

    @Autowired
    private ComponentDao components;

    private Repository repo;
    private String token;

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "cargo");
        token = tokens.issue(users.require(ADMIN), "cargo", null).secret();
    }

    @AfterEach
    void removeTestUsers() {
        for (String username : TEST_USERS) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }

    private MvcResult publishOk(String name, String version) throws Exception {
        return mvc.perform(publish(URL, token, publishBody(name, version)))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void configJsonNamesTheRepositoryAsDownloadAndApiHost() throws Exception {
        MvcResult result = mvc.perform(index(URL + "/index/config.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().exists("ETag"))
                .andReturn();
        ObjectNode config = json(result);
        assertThat(config.path("dl").stringValue()).isEqualTo(repositoryUrl(REPO) + "/api/v1/crates");
        assertThat(config.path("api").stringValue()).isEqualTo(repositoryUrl(REPO));
        assertThat(config.path("auth-required").booleanValue()).isFalse();

        // cargo revalidates with If-None-Match
        mvc.perform(index(URL + "/index/config.json").header("if-none-match", result.getResponse().getHeader("ETag")))
                .andExpect(status().isNotModified());

        // a configured base URL wins over the request's host
        settings.setBaseUrl("https://granary.example.com");
        mvc.perform(index(URL + "/index/config.json"))
                .andExpect(jsonPath("$.dl").value("https://granary.example.com/repository/" + REPO + "/api/v1/crates"))
                .andExpect(jsonPath("$.api").value("https://granary.example.com/repository/" + REPO));
    }

    @Test
    void publishedCrateIsIndexedAndDownloadable() throws Exception {
        byte[] crate = crate("acme-hello", "0.1.0");
        MvcResult published = mvc.perform(publish(URL, token, publishBody(metadata("acme-hello", "0.1.0"), crate)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(json(published).path("warnings").path("invalid_categories").isArray()).isTrue();
        assertThat(json(published).path("warnings").path("other").isArray()).isTrue();

        MvcResult indexFile = mvc.perform(index(URL + "/index/ac/me/acme-hello"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(header().exists("ETag"))
                .andReturn();
        List<ObjectNode> lines = lines(indexFile);
        assertThat(lines).hasSize(1);
        ObjectNode line = lines.getFirst();
        assertThat(line.path("name").stringValue()).isEqualTo("acme-hello");
        assertThat(line.path("vers").stringValue()).isEqualTo("0.1.0");
        assertThat(line.path("cksum").stringValue()).isEqualTo(sha256(crate));
        assertThat(line.path("yanked").booleanValue()).isFalse();
        assertThat(line.path("pubtime").stringValue()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ");
        ObjectNode dep = (ObjectNode) line.path("deps").path(0);
        assertThat(dep.path("name").stringValue()).isEqualTo("itoa");
        assertThat(dep.path("req").stringValue()).isEqualTo("^1");
        assertThat(dep.path("kind").stringValue()).isEqualTo("normal");
        assertThat(dep.path("registry").stringValue()).isEqualTo(CargoFixtures.CRATES_IO_INDEX);
        assertThat(dep.has("version_req")).isFalse();

        // the index file is unchanged until the next publish, so cargo's conditional request is a 304
        mvc.perform(index(URL + "/index/ac/me/acme-hello").header("if-none-match", indexFile.getResponse().getHeader("ETag")))
                .andExpect(status().isNotModified());

        // cargo looks crates up by their lowercased name; any spelling finds the file
        mvc.perform(index(URL + "/index/ac/me/Acme-Hello")).andExpect(status().isOk());

        assertThat(mvc.perform(download(URL + "/api/v1/crates/acme-hello/0.1.0/download"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(crate);
        mvc.perform(head(URL + "/api/v1/crates/acme-hello/0.1.0/download"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", crate.length));

        StoredContent stored = storage.find(repo, "crates/acme-hello/acme-hello-0.1.0.crate").orElseThrow();
        assertThat(stored.asset().getBlobSha256()).isEqualTo(sha256(crate));
        assertThat(stored.asset().isMetadata()).isFalse();
        assertThat(stored.asset().getDownloadCount()).isEqualTo(1);
        assertThat(storage.find(repo, "index/ac/me/acme-hello").orElseThrow().asset().isMetadata()).isTrue();

        List<Component> found = components.findByRepositoryIdOrderByNamespaceAscNameAscVersionAsc(repo.getId(),
                PageRequest.of(0, 10)).getContent();
        assertThat(found).extracting(Component::getName).containsExactly("acme-hello");
        assertThat(found).extracting(Component::getVersion).containsExactly("0.1.0");
    }

    @Test
    void renamedOptionalDependencyAndDepFeaturesAreIndexedLikeCratesIo() throws Exception {
        byte[] body = publishBody(CargoFixtures.renamedDependencyMetadata("rec-demo", "0.2.0"), crate("rec-demo", "0.2.0"));
        mvc.perform(publish(URL, token, body)).andExpect(status().isOk());

        ObjectNode line = lines(mvc.perform(index(URL + "/index/re/c-/rec-demo")).andExpect(status().isOk()).andReturn())
                .getFirst();
        // the dependency appears under the name the manifest uses, with the real crate in `package`
        assertThat(line.path("deps").path(0).path("name").stringValue()).isEqualTo("itoa");
        ObjectNode json = (ObjectNode) line.path("deps").path(1);
        assertThat(json.path("name").stringValue()).isEqualTo("json");
        assertThat(json.path("package").stringValue()).isEqualTo("serde_json");
        assertThat(json.path("optional").booleanValue()).isTrue();
        assertThat(line.path("deps").path(0).has("package")).isFalse();
        // features using `dep:` move to features2, which older cargos skip
        assertThat(line.path("features").path("plain").isArray()).isTrue();
        assertThat(line.path("features").has("fast")).isFalse();
        assertThat(line.path("features2").path("fast").path(0).stringValue()).isEqualTo("dep:json");
        assertThat(line.path("v").intValue()).isEqualTo(2);
    }

    @Test
    void versionsAreListedInPublishOrderAndAnExistingVersionIsRefused() throws Exception {
        publishOk("acme", "1.10.0");
        publishOk("acme", "1.2.0");
        publishOk("acme", "1.2.0-rc.1");
        // like index.crates.io, a new version appends a line
        assertThat(versions(mvc.perform(index(URL + "/index/ac/me/acme")).andReturn()))
                .containsExactly("1.10.0", "1.2.0", "1.2.0-rc.1");

        // refused with 409 and the registry web API's error document, which cargo prints
        MvcResult again = mvc.perform(publish(URL, token, publishBody("acme", "1.2.0")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(errorDetail(again)).isEqualTo("crate version `1.2.0` is already uploaded");
        // build metadata does not make a new version
        assertThat(errorDetail(mvc.perform(publish(URL, token, publishBody("acme", "1.2.0+linux")))
                .andExpect(status().isConflict()).andReturn()))
                .isEqualTo("crate version `1.2.0` is already uploaded");
        assertThat(versions(mvc.perform(index(URL + "/index/ac/me/acme")).andReturn())).hasSize(3);
    }

    @Test
    void redeployableRepositoriesReplaceTheVersion() throws Exception {
        repo.setAllowRedeploy(true);
        repositories.update(REPO, repo);
        publishOk("acme", "1.0.0");
        byte[] second = crate("acme", "1.0.0", "pub fn second() {}\n");
        mvc.perform(publish(URL, token, publishBody(metadata("acme", "1.0.0"), second))).andExpect(status().isOk());

        List<ObjectNode> lines = lines(mvc.perform(index(URL + "/index/ac/me/acme")).andReturn());
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().path("cksum").stringValue()).isEqualTo(sha256(second));
        assertThat(mvc.perform(download(URL + "/api/v1/crates/acme/1.0.0/download")).andReturn().getResponse()
                .getContentAsByteArray()).isEqualTo(second);
    }

    @Test
    void deletingAVersionInTheAdminApiTakesItOutOfTheIndex() throws Exception {
        publishOk("acme", "1.0.0");
        publishOk("acme", "1.1.0");
        Component v11 = components.findByRepositoryIdOrderByNamespaceAscNameAscVersionAsc(repo.getId(), PageRequest.of(0, 10))
                .getContent().stream().filter(c -> c.getVersion().equals("1.1.0")).findFirst().orElseThrow();
        mvc.perform(delete("/api/v1/repositories/" + REPO + "/components/" + v11.getId()).header("Authorization", adminBasic()))
                .andExpect(status().isOk());

        assertThat(versions(mvc.perform(index(URL + "/index/ac/me/acme")).andExpect(status().isOk()).andReturn()))
                .containsExactly("1.0.0");
        mvc.perform(download(URL + "/api/v1/crates/acme/1.1.0/download")).andExpect(status().isNotFound());
        // the version number is free again, and the index is rewritten without the stale line
        publishOk("acme", "1.1.0");
        assertThat(versions(mvc.perform(index(URL + "/index/ac/me/acme")).andReturn())).containsExactly("1.0.0", "1.1.0");
        assertThat(storage.find(repo, "index/ac/me/acme").orElseThrow().readUtf8().lines().count()).isEqualTo(2);
    }

    @Test
    void namesThatDifferOnlyInCaseOrSeparatorsAreTheSameCrate() throws Exception {
        publishOk("acme-hello", "0.1.0");
        MvcResult upper = mvc.perform(publish(URL, token, publishBody("Acme-Hello", "0.2.0")))
                .andExpect(status().isConflict()).andReturn();
        assertThat(errorDetail(upper)).contains("acme-hello").contains("already exists");
        MvcResult underscore = mvc.perform(publish(URL, token, publishBody("acme_hello", "0.2.0")))
                .andExpect(status().isConflict()).andReturn();
        assertThat(errorDetail(underscore)).contains("too similar");
        publishOk("acme-hello", "0.2.0");
    }

    @Test
    void yankAndUnyankFlipTheIndexFlagButKeepTheFile() throws Exception {
        publishOk("acme", "1.0.0");
        mvc.perform(api(delete(URL + "/api/v1/crates/acme/1.0.0/yank"), token))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"ok\":true}"));
        assertThat(lines(mvc.perform(index(URL + "/index/ac/me/acme")).andReturn()).getFirst().path("yanked").booleanValue())
                .isTrue();
        // lock files may still pin a yanked version
        mvc.perform(download(URL + "/api/v1/crates/acme/1.0.0/download")).andExpect(status().isOk());

        mvc.perform(api(put(URL + "/api/v1/crates/acme/1.0.0/unyank"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(lines(mvc.perform(index(URL + "/index/ac/me/acme")).andReturn()).getFirst().path("yanked").booleanValue())
                .isFalse();

        MvcResult missing = mvc.perform(api(delete(URL + "/api/v1/crates/acme/9.9.9/yank"), token))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(errorDetail(missing)).contains("9.9.9");
    }

    @Test
    void ownersStartWithThePublisherAndCanBeChanged() throws Exception {
        users.create("cargo-bob", "bobpassword1", "Bob", null, Set.of("developer"));
        publishOk("acme", "1.0.0");

        mvc.perform(api(get(URL + "/api/v1/crates/acme/owners"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].login").value("admin"))
                .andExpect(jsonPath("$.users.length()").value(1));

        mvc.perform(api(put(URL + "/api/v1/crates/acme/owners"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"users\":[\"cargo-bob\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.msg").value("user cargo-bob has been added as an owner of crate acme"));
        mvc.perform(api(get(URL + "/api/v1/crates/acme/owners"), token))
                .andExpect(jsonPath("$.users[*].login").value(org.hamcrest.Matchers.contains("admin", "cargo-bob")));

        MvcResult ghost = mvc.perform(api(put(URL + "/api/v1/crates/acme/owners"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"users\":[\"nobody-here\"]}"))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(errorDetail(ghost)).isEqualTo("could not find user with login `nobody-here`");

        mvc.perform(api(delete(URL + "/api/v1/crates/acme/owners"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"users\":[\"admin\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("owners successfully removed"));
        MvcResult last = mvc.perform(api(delete(URL + "/api/v1/crates/acme/owners"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"users\":[\"cargo-bob\"]}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(errorDetail(last)).isEqualTo("cannot remove all owners of crate acme");

        mvc.perform(api(get(URL + "/api/v1/crates/unknown/owners"), token)).andExpect(status().isNotFound());
    }

    @Test
    void searchFindsPublishedCratesByNameAndDescription() throws Exception {
        publishOk("acme-hello", "0.1.0");
        publishOk("acme-hello", "0.2.0");
        publishOk("widgets", "1.0.0");

        mvc.perform(api(get(URL + "/api/v1/crates").param("q", "acme").param("per_page", "10"), null))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.crates.length()").value(1))
                .andExpect(jsonPath("$.crates[0].name").value("acme-hello"))
                .andExpect(jsonPath("$.crates[0].max_version").value("0.2.0"))
                .andExpect(jsonPath("$.crates[0].description").value("Hello from acme-hello"))
                .andExpect(jsonPath("$.meta.total").value(1));
        mvc.perform(api(get(URL + "/api/v1/crates").param("q", "hello from"), null))
                .andExpect(jsonPath("$.meta.total").value(2));
        mvc.perform(api(get(URL + "/api/v1/crates").param("q", "nothing-like-it"), null))
                .andExpect(jsonPath("$.crates.length()").value(0))
                .andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void malformedPublishesAreRejectedWithCargoErrors() throws Exception {
        MvcResult empty = mvc.perform(publish(URL, token, new byte[0])).andExpect(status().isBadRequest()).andReturn();
        assertThat(errorDetail(empty)).isNotBlank();

        byte[] truncated = java.util.Arrays.copyOf(publishBody("acme", "1.0.0"), 40);
        mvc.perform(publish(URL, token, truncated)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].detail").exists());

        MvcResult badName = mvc.perform(publish(URL, token, publishBody(metadata("1acme", "1.0.0"), crate("1acme", "1.0.0"))))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(errorDetail(badName)).contains("1acme");

        MvcResult badVersion = mvc.perform(publish(URL, token, publishBody(metadata("acme", "1.0"), crate("acme", "1.0"))))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(errorDetail(badVersion)).contains("semver");

        // the archive must unpack to <name>-<version>/ with a manifest
        MvcResult wrongRoot = mvc.perform(publish(URL, token, publishBody(metadata("acme", "1.0.0"), crate("other", "1.0.0"))))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(errorDetail(wrongRoot)).contains("outside");
        MvcResult notGzip = mvc.perform(publish(URL, token, publishBody(metadata("acme", "1.0.0"),
                        "not a crate".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(errorDetail(notGzip)).contains("gzip");
        MvcResult noManifest = mvc.perform(publish(URL, token, publishBody(metadata("acme", "1.0.0"),
                        CargoFixtures.tarGz(Map.of("acme-1.0.0/src/lib.rs", "")))))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(errorDetail(noManifest)).contains("Cargo.toml");

        // nothing of the above left anything behind
        mvc.perform(index(URL + "/index/ac/me/acme")).andExpect(status().isNotFound());
        assertThat(storage.list(repo, "crates/")).isEmpty();
    }

    @Test
    void missingThingsAreCargoStyle404s() throws Exception {
        MvcResult noCrate = mvc.perform(index(URL + "/index/no/ne/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(errorDetail(noCrate)).isEqualTo("crate `nonexistent` not found");
        mvc.perform(download(URL + "/api/v1/crates/nonexistent/1.0.0/download")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].detail").value("crate `nonexistent` version `1.0.0` not found"));
        // an index file under directories cargo would never use for the name
        mvc.perform(index(URL + "/index/xx/yy/nonexistent")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/api/v1/crates/bad..name/1.0.0/download")).andExpect(status().isBadRequest());
        mvc.perform(post(URL + "/api/v1/crates/new").header("authorization", token)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/repository/no-such-repo/index/config.json")).andExpect(status().isNotFound());
    }

    @Test
    void everyReadAnswersHeadAndConditionalRequests() throws Exception {
        byte[] crate = crate("acme-hello", "0.1.0");
        mvc.perform(publish(URL, token, publishBody(metadata("acme-hello", "0.1.0"), crate))).andExpect(status().isOk());
        for (String path : List.of("/index/config.json", "/index/ac/me/acme-hello", "/api/v1/crates/acme-hello/0.1.0/download")) {
            MvcResult full = mvc.perform(get(URL + path)).andExpect(status().isOk()).andReturn();
            byte[] body = full.getResponse().getContentAsByteArray();
            String etag = full.getResponse().getHeader("ETag");
            assertThat(etag).as(path).isNotBlank();
            mvc.perform(head(URL + path))
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", etag))
                    .andExpect(header().longValue("Content-Length", body.length))
                    .andExpect(header().string("Content-Type", full.getResponse().getContentType()))
                    .andExpect(content().bytes(new byte[0]));
            mvc.perform(get(URL + path).header("If-None-Match", etag)).andExpect(status().isNotModified());
            mvc.perform(head(URL + path).header("If-None-Match", etag)).andExpect(status().isNotModified());
            mvc.perform(get(URL + path).header("If-None-Match", "\"something-else\"")).andExpect(status().isOk());
        }
        // HEAD and 304 are not downloads
        assertThat(storage.find(repo, "crates/acme-hello/acme-hello-0.1.0.crate").orElseThrow().asset().getDownloadCount())
                .isEqualTo(2);
        // the search, owner and HTML pages answer HEAD too
        for (String path : List.of("/api/v1/crates?q=acme", "/api/v1/crates/acme-hello/owners", "/me", "/")) {
            mvc.perform(head(URL + path)).andExpect(status().isOk()).andExpect(content().bytes(new byte[0]));
        }
    }

    @Test
    void traversalOddNamesAndEncodedSeparatorsAreBadRequests() throws Exception {
        publishOk("acme-hello", "0.1.0");
        // raw URIs, so the percent-encoding reaches the server exactly as written
        for (String raw : List.of("/index/ac%2Fme/acme-hello", "/index/ac/me/acme%2Fhello", "/index/ac/me/acme%5Chello",
                "/api/v1/crates/acme-hello/0.1.0/download%2F", "/api/v1/crates/acme-hello%2F0.1.0/download",
                "/api/v1/crates/..%2Facme-hello/0.1.0/download", "/index/ac/me/..%2F..%2Fconfig.json",
                "/index/ac/me/acm%C3%A9", "/api/v1/crates/acme-hello/0.1.0%20/download", "/index/2/a%00")) {
            mvc.perform(get(URI.create(URL + raw)).header("User-Agent", CargoFixtures.USER_AGENT))
                    .andExpect(status().isBadRequest());
        }
        // the plain spellings still answer
        mvc.perform(index(URL + "/index/ac/me/acme-hello")).andExpect(status().isOk());
        mvc.perform(download(URL + "/api/v1/crates/acme-hello/0.1.0/download")).andExpect(status().isOk());
        assertThat(storage.list(repo, "")).extracting(a -> a.getPath())
                .allMatch(p -> p.startsWith("index/") || p.startsWith("crates/") || p.startsWith("meta/"));
    }

    @Test
    void loginPageAndLandingPageExplainTheSetup() throws Exception {
        String me = mvc.perform(get(URL + "/me"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(me).contains("cargo login --registry granary").contains(CargoFixtures.SERVER + "/account");

        String landing = mvc.perform(get(URL + "/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(landing).contains("sparse+" + repositoryUrl(REPO) + "/index/");
    }

    @Test
    void formatAndConnectSnippetsAreAdvertised() throws Exception {
        mvc.perform(get("/api/v1/formats").header("Authorization", adminBasic()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'cargo')].templates[*]")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("cargo-proxy", "cargo-hosted", "cargo-group")));

        MvcResult connect = mvc.perform(get("/api/v1/repositories/" + REPO + "/connect").header("Authorization", adminBasic()))
                .andExpect(status().isOk())
                .andReturn();
        String body = connect.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("cargo-registry", "cargo-login", "cargo-env", "cargo-dependency", "cargo-add", "cargo-publish")
                .contains("sparse+" + repositoryUrl(REPO) + "/index/")
                .doesNotContain("cargo-mirror");
    }
}
