package dev.granary.server.hex;

import static dev.granary.server.hex.HexFixtures.ERLANG;
import static dev.granary.server.hex.HexFixtures.USER_AGENT;
import static dev.granary.server.hex.HexFixtures.docsTarball;
import static dev.granary.server.hex.HexFixtures.map;
import static dev.granary.server.hex.HexFixtures.metadata;
import static dev.granary.server.hex.HexFixtures.names;
import static dev.granary.server.hex.HexFixtures.pkg;
import static dev.granary.server.hex.HexFixtures.release;
import static dev.granary.server.hex.HexFixtures.releaseWith;
import static dev.granary.server.hex.HexFixtures.repositoryUrl;
import static dev.granary.server.hex.HexFixtures.retireBody;
import static dev.granary.server.hex.HexFixtures.sha256Hex;
import static dev.granary.server.hex.HexFixtures.term;
import static dev.granary.server.hex.HexFixtures.termList;
import static dev.granary.server.hex.HexFixtures.versions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.GranaryException;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.storage.StorageService;
import dev.granary.format.hex.HexRegistry;
import dev.granary.format.hex.HexSettings;
import dev.granary.server.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** A hosted Hex repository driven the way mix hex.publish, mix deps.get, hex.retire and hex.info drive it. */
class HexHostedIntegrationTest extends IntegrationTest {

    private static final String REPO = "hex-hosted";
    private static final String URL = "/repository/" + REPO;

    @Autowired
    private StorageService storage;

    private Repository repo;
    private String key;

    @BeforeEach
    void setUp() {
        repo = hosted(REPO, "hex");
        key = tokens.issue(users.require(ADMIN), "mix", null).secret();
    }

    /** {@code POST api/packages/<name>/releases?replace=…} with the headers mix sent in the recording. */
    private ResultActions publish(String name, byte[] tarball, boolean replace) throws Exception {
        return mvc.perform(post(URL + "/api/packages/" + name + "/releases").queryParam("replace", Boolean.toString(replace))
                .header("Authorization", key).header("User-Agent", USER_AGENT).header("Accept", ERLANG)
                .contentType("application/octet-stream").content(tarball));
    }

    /** A repository GET as hex_core makes it: a bare key in Authorization, no Accept header. */
    private byte[] fetch(String path) throws Exception {
        return mvc.perform(get(URL + "/" + path).header("Authorization", key).header("User-Agent", USER_AGENT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private String publicKey() throws Exception {
        return mvc.perform(get(URL + "/public_key")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/x-pem-file")))
                .andReturn().getResponse().getContentAsString();
    }

    private Map<String, Object> api(String path) throws Exception {
        return term(mvc.perform(get(URL + "/api/" + path).header("Authorization", key).header("Accept", ERLANG))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    void publishThenResolveLikeMixDepsGet() throws Exception {
        byte[] tarball = release("acme_lib", "0.1.0", "jason", "~> 1.4", "false");
        Map<String, Object> published = term(publish("acme_lib", tarball, false)
                .andExpect(status().isCreated())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith(ERLANG)))
                .andReturn());
        assertThat(published).containsEntry("version", "0.1.0").containsEntry("checksum", sha256Hex(tarball))
                .containsEntry("url", repositoryUrl(REPO) + "/api/packages/acme_lib/releases/0.1.0");
        assertThat(map(published.get("publisher"))).containsEntry("username", "admin");

        String pem = publicKey();
        HexRegistry.Package pkg = pkg(fetch("packages/acme_lib"), pem);
        assertThat(pkg.name()).isEqualTo("acme_lib");
        assertThat(pkg.repository()).isEqualTo(REPO);
        HexRegistry.Release release = pkg.release("0.1.0").orElseThrow();
        assertThat(java.util.HexFormat.of().formatHex(release.outerChecksum())).isEqualTo(sha256Hex(tarball));
        assertThat(release.dependencies()).containsExactly(new HexRegistry.Dependency("jason", "~> 1.4", false, "", "hexpm"));
        assertThat(names(fetch("names"), pem).names()).containsExactly("acme_lib");
        assertThat(versions(fetch("versions"), pem).packages())
                .containsExactly(new HexRegistry.VersionsEntry("acme_lib", List.of("0.1.0"), List.of()));

        assertThat(fetch("tarballs/acme_lib-0.1.0.tar")).isEqualTo(tarball);
        assertThat(storage.find(repo, "tarballs/acme_lib-0.1.0.tar").orElseThrow().asset().getDownloadCount()).isEqualTo(1);
        mvc.perform(head(URL + "/tarballs/acme_lib-0.1.0.tar")).andExpect(status().isOk());
        assertThat(storage.find(repo, "tarballs/acme_lib-0.1.0.tar").orElseThrow().asset().getComponentId()).isNotNull();
    }

    @Test
    void theSignatureIsGranarysAndStaysStableAcrossRequests() throws Exception {
        publish("acme_lib", release("acme_lib", "0.1.0"), false).andExpect(status().isCreated());
        byte[] first = fetch("packages/acme_lib");
        byte[] second = fetch("packages/acme_lib");
        assertThat(second).isEqualTo(first);
        String etag = mvc.perform(get(URL + "/packages/acme_lib")).andReturn().getResponse().getHeader("ETag");
        mvc.perform(get(URL + "/packages/acme_lib").header("If-None-Match", etag)).andExpect(status().isNotModified());

        String root = mvc.perform(get(URL + "/")).andExpect(status().isOk())
                .andExpect(jsonPath("$.repository").value(REPO))
                .andExpect(jsonPath("$.api_url").value(repositoryUrl(REPO) + "/api"))
                .andReturn().getResponse().getContentAsString();
        assertThat(root).contains("\"public_key_fingerprint\":\"SHA256:");
    }

    @Test
    void republishingIsRefusedWithTheMessageMixPrints() throws Exception {
        publish("acme_lib", release("acme_lib", "0.1.0"), false).andExpect(status().isCreated());
        Map<String, Object> error = term(publish("acme_lib", release("acme_lib", "0.1.0"), false)
                .andExpect(status().isUnprocessableContent()).andReturn());
        assertThat(error).containsEntry("status", 422L).containsEntry("message", "Validation error(s)");
        assertThat(map(error.get("errors")))
                .containsEntry("inserted_at", "must include the --replace flag to update an existing release");

        Map<String, Object> notAllowed = term(publish("acme_lib", release("acme_lib", "0.1.0"), true)
                .andExpect(status().isUnprocessableContent()).andReturn());
        assertThat((String) map(notAllowed.get("errors")).get("inserted_at")).contains("allowRedeploy is off");

        repo.setAllowRedeploy(true);
        repositories.update(REPO, repo);
        byte[] replacement = releaseWith(metadata("acme_lib", "0.1.0").replace("package ✓", "package, replaced"));
        publish("acme_lib", replacement, true).andExpect(status().isOk());
        assertThat(fetch("tarballs/acme_lib-0.1.0.tar")).isEqualTo(replacement);
        assertThat(java.util.HexFormat.of().formatHex(pkg(fetch("packages/acme_lib"), publicKey()).release("0.1.0").orElseThrow()
                .outerChecksum())).isEqualTo(sha256Hex(replacement));
    }

    @Test
    void invalidTarballsAreRefusedAndLeaveNothingBehind() throws Exception {
        Map<String, Object> garbage = term(publish("acme_lib", "not a tarball".getBytes(), false)
                .andExpect(status().isUnprocessableContent()).andReturn());
        assertThat(map(garbage.get("errors"))).containsKey("tar");
        Map<String, Object> wrongName = term(publish("other_lib", release("acme_lib", "0.1.0"), false)
                .andExpect(status().isUnprocessableContent()).andReturn());
        assertThat(map(wrongName.get("errors"))).containsKey("name");
        for (String literal : List.of("99999999999#1", "\"\\x{FFFFFFFF}\"", "<<1114112/utf8>>")) {
            Map<String, Object> hostile = term(publish("acme_lib",
                    releaseWith(metadata("acme_lib", "0.1.0") + "{<<\"extra\">>, " + literal + "}.\n"), false)
                    .andExpect(status().isUnprocessableContent()).andReturn());
            assertThat((String) map(hostile.get("errors")).get("tar")).as(literal).contains("metadata.config");
        }
        mvc.perform(get(URL + "/packages/acme_lib")).andExpect(status().isNotFound());
        assertThat(storage.list(repo, "tarballs/")).isEmpty();
    }

    @Test
    void theOldPublishEndpointStillWorks() throws Exception {
        mvc.perform(post(URL + "/api/publish").header("Authorization", key).header("Accept", ERLANG)
                        .contentType("application/octet-stream").content(release("old_style", "1.0.0")))
                .andExpect(status().isCreated());
        mvc.perform(get(URL + "/packages/old_style")).andExpect(status().isOk());
    }

    @Test
    void retireAndUnretireLikeMixHexRetire() throws Exception {
        publish("acme_lib", release("acme_lib", "0.1.0"), false).andExpect(status().isCreated());
        mvc.perform(post(URL + "/api/packages/acme_lib/releases/0.1.0/retire").header("Authorization", key)
                        .header("Accept", ERLANG).contentType(ERLANG).content(retireBody("security", "use 0.1.1")))
                .andExpect(status().isNoContent());
        HexRegistry.Release retired = pkg(fetch("packages/acme_lib"), publicKey()).release("0.1.0").orElseThrow();
        assertThat(retired.retired()).isEqualTo(new HexRegistry.Retirement(HexRegistry.RetirementReason.SECURITY, "use 0.1.1"));
        assertThat(versions(fetch("versions"), publicKey()).packages().getFirst().retired()).containsExactly(0);
        assertThat(map(api("packages/acme_lib/releases/0.1.0").get("retirement")))
                .containsEntry("reason", "security").containsEntry("message", "use 0.1.1");

        mvc.perform(delete(URL + "/api/packages/acme_lib/releases/0.1.0/retire").header("Authorization", key)
                        .header("Accept", ERLANG))
                .andExpect(status().isNoContent());
        assertThat(pkg(fetch("packages/acme_lib"), publicKey()).release("0.1.0").orElseThrow().isRetired()).isFalse();

        Map<String, Object> invalid = term(mvc.perform(post(URL + "/api/packages/acme_lib/releases/0.1.0/retire")
                        .header("Authorization", key).header("Accept", ERLANG).contentType(ERLANG)
                        .content(retireBody("bored", "")))
                .andExpect(status().isUnprocessableContent()).andReturn());
        assertThat(map(invalid.get("errors"))).containsKey("reason");
    }

    @Test
    void revertRemovesTheRelease() throws Exception {
        publish("acme_lib", release("acme_lib", "0.1.0"), false).andExpect(status().isCreated());
        publish("acme_lib", release("acme_lib", "0.1.1"), false).andExpect(status().isCreated());
        mvc.perform(delete(URL + "/api/packages/acme_lib/releases/0.1.1").header("Authorization", key).header("Accept", ERLANG))
                .andExpect(status().isNoContent());
        assertThat(pkg(fetch("packages/acme_lib"), publicKey()).releases()).extracting(HexRegistry.Release::version)
                .containsExactly("0.1.0");
        mvc.perform(get(URL + "/tarballs/acme_lib-0.1.1.tar")).andExpect(status().isNotFound());

        mvc.perform(delete(URL + "/api/packages/acme_lib/releases/0.1.0").header("Authorization", key).header("Accept", ERLANG))
                .andExpect(status().isNoContent());
        mvc.perform(get(URL + "/packages/acme_lib")).andExpect(status().isNotFound());
        assertThat(names(fetch("names"), publicKey()).packages()).isEmpty();
        mvc.perform(delete(URL + "/api/packages/acme_lib/releases/0.1.0").header("Authorization", key).header("Accept", ERLANG))
                .andExpect(status().isNotFound());
    }

    @Test
    void developersRevertTheirOwnFreshReleases() throws Exception {
        users.create("hex-dev", "devpassword", "Dev", null, java.util.Set.of("developer"));
        String devKey = tokens.issue(users.require("hex-dev"), "mix", null).secret();
        mvc.perform(post(URL + "/api/packages/acme_lib/releases").queryParam("replace", "false")
                        .header("Authorization", devKey).header("Accept", ERLANG)
                        .contentType("application/octet-stream").content(release("acme_lib", "0.1.0")))
                .andExpect(status().isCreated());
        assertThat(map(api("packages/acme_lib/releases/0.1.0").get("publisher"))).containsEntry("username", "hex-dev");
        mvc.perform(delete(URL + "/api/packages/acme_lib/releases/0.1.0").header("Authorization", devKey)
                        .header("Accept", ERLANG))
                .andExpect(status().isNoContent()); // inside the default 60-minute window
    }

    @Test
    void documentationIsPublishedAndServed() throws Exception {
        publish("acme_lib", release("acme_lib", "0.1.0"), false).andExpect(status().isCreated());
        byte[] docs = docsTarball("acme");
        mvc.perform(post(URL + "/api/packages/acme_lib/releases/0.1.0/docs").header("Authorization", key)
                        .header("Accept", ERLANG).contentType("application/octet-stream").content(docs))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", repositoryUrl(REPO) + "/docs/acme_lib-0.1.0.tar.gz"));
        assertThat(fetch("docs/acme_lib-0.1.0.tar.gz")).isEqualTo(docs);
        assertThat(api("packages/acme_lib/releases/0.1.0")).containsEntry("has_docs", true);
        mvc.perform(get(URL + "/api/packages/acme_lib/releases/0.1.0/docs").header("Authorization", key))
                .andExpect(status().isFound());
        mvc.perform(delete(URL + "/api/packages/acme_lib/releases/0.1.0/docs").header("Authorization", key))
                .andExpect(status().isNoContent());
        mvc.perform(get(URL + "/docs/acme_lib-0.1.0.tar.gz")).andExpect(status().isNotFound());
    }

    @Test
    void hexInfoAndPackageSearch() throws Exception {
        publish("acme_lib", release("acme_lib", "0.1.0"), false).andExpect(status().isCreated());
        publish("acme_lib", release("acme_lib", "0.2.0-rc.1"), false).andExpect(status().isCreated());
        publish("other_lib", release("other_lib", "1.0.0"), false).andExpect(status().isCreated());
        fetch("tarballs/acme_lib-0.1.0.tar");

        Map<String, Object> info = api("packages/acme_lib");
        assertThat(info).containsEntry("name", "acme_lib").containsEntry("repository", REPO)
                .containsEntry("latest_stable_version", "0.1.0").containsEntry("latest_version", "0.2.0-rc.1");
        assertThat(map(info.get("downloads"))).containsEntry("all", 1L);
        assertThat(map(info.get("meta"))).containsEntry("description", "The acme_lib package ✓");
        assertThat(map(info.get("configs"))).containsEntry("mix.exs", "{:acme_lib, \"~> 0.1.0\", repo: \"hex-hosted\"}");

        List<Object> search = termList(mvc.perform(get(URL + "/api/packages").queryParam("search", "acme")
                        .queryParam("sort", "name").queryParam("page", "1")
                        .header("Authorization", key).header("Accept", ERLANG))
                .andExpect(status().isOk()).andReturn());
        assertThat(search).extracting(p -> map(p).get("name")).containsExactly("acme_lib");

        mvc.perform(get(URL + "/api/packages/acme_lib").header("Accept", "application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("acme_lib"))
                .andExpect(jsonPath("$.releases[0].version").value("0.2.0-rc.1"));
        mvc.perform(get(URL + "/api/packages/nope").header("Accept", "application/json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("package nope does not exist"));
    }

    @Test
    void repositoryNameSettingIsSignedIntoTheDocuments() throws Exception {
        repo.setFormatSettings(Map.of(HexSettings.REPOSITORY_NAME, "acme"));
        repositories.update(REPO, repo);
        publish("acme_lib", release("acme_lib", "0.1.0"), false).andExpect(status().isCreated());
        assertThat(pkg(fetch("packages/acme_lib"), publicKey()).repository()).isEqualTo("acme");
        assertThat(names(fetch("names"), publicKey()).repository()).isEqualTo("acme");

        Repository bad = new Repository();
        bad.setName("hex-bad");
        bad.setFormat("hex");
        bad.setType(RepositoryType.HOSTED);
        bad.setFormatSettings(Map.of(HexSettings.REPOSITORY_NAME, "acme:org"));
        assertThatThrownBy(() -> repositories.create(bad)).isInstanceOf(GranaryException.class);
    }

    @Test
    void installsAndOddRequests() throws Exception {
        mvc.perform(get(URL + "/installs/hex-1.x.csv")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")));
        mvc.perform(get(URL + "/packages/Bad-Name")).andExpect(status().isBadRequest());
        mvc.perform(put(URL + "/names").header("Authorization", key).content(new byte[] {1}))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(get(URL + "/packages/never_published")).andExpect(status().isNotFound());
        for (String odd : List.of("/packages/acme_lib%2Fx", "/tarballs%2Facme_lib-0.1.0.tar", "/packages/acme%5Clib",
                "/packages/%2e%2e", "/tarballs/acme_lib-0.1.0.tar%00", "/packages/Acme", "/docs/..%2Fnames")) {
            mvc.perform(get(java.net.URI.create(URL + odd))).andExpect(status().isBadRequest());
        }
        Map<String, Object> unknownApi = term(mvc.perform(get(URL + "/api/orgs").header("Accept", ERLANG))
                .andExpect(status().isNotFound()).andReturn());
        assertThat(unknownApi).containsEntry("status", 404L);
    }

    @Test
    void connectSnippetsForMixAndRebar3() throws Exception {
        mvc.perform(get("/api/v1/repositories/" + REPO + "/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'mix-repo-add')].code").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("mix hex.repo add hex-hosted " + repositoryUrl(REPO)
                                + " --fetch-public-key SHA256:"))))
                .andExpect(jsonPath("$[?(@.id == 'mix-publish')].code").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("HEX_API_URL=" + repositoryUrl(REPO) + "/api"))))
                .andExpect(jsonPath("$[?(@.id == 'rebar3-config')].code").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("repo_public_key => <<\"-----BEGIN PUBLIC KEY-----\\n"))));
        mvc.perform(get("/api/v1/formats").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'hex' && 'hexpm' in @.templates)]").exists())
                .andExpect(jsonPath("$[?(@.id == 'hex' && 'hex-group' in @.templates)]").exists());
        assertThat(MediaType.parseMediaType(ERLANG).getSubtype()).isEqualTo("vnd.hex+erlang");
    }
}
