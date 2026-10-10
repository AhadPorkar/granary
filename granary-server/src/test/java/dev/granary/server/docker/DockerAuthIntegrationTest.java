package dev.granary.server.docker;

import static dev.granary.server.docker.DockerFixtures.OCI_MANIFEST;
import static dev.granary.server.docker.DockerFixtures.config;
import static dev.granary.server.docker.DockerFixtures.layer;
import static dev.granary.server.docker.DockerFixtures.manifest;
import static dev.granary.server.docker.DockerFixtures.pushImage;
import static dev.granary.server.docker.DockerFixtures.uploadBlob;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.Repository;
import dev.granary.core.model.User;
import dev.granary.server.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * How container clients authenticate: the Basic challenge on the {@code /v2/} ping that makes docker send its
 * login, API tokens as the password, and the anonymous cases.
 */
class DockerAuthIntegrationTest extends IntegrationTest {

    private static final String REPO = "docker-hosted";
    private static final String URL = "/repository/" + REPO;
    private static final List<String> TEST_USERS = List.of("docker-alice", "docker-bob");

    private Repository repo;

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "docker");
    }

    @AfterEach
    void removeTestUsers() {
        for (String username : TEST_USERS) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }

    @Test
    void anonymousPingIsChallengedSoDockerSendsItsLogin() throws Exception {
        mvc.perform(get(URL + "/v2/"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Granary\""))
                .andExpect(header().string("Docker-Distribution-API-Version", "registry/2.0"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.errors[0].detail").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get(URL + "/v2/").header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
        // anonymous reads of content are still allowed while anonymous read is on
        mvc.perform(get(URL + "/v2/acme/app/manifests/1.0")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("MANIFEST_UNKNOWN"));
        mvc.perform(get(URL + "/v2/_catalog")).andExpect(status().isOk());
    }

    @Test
    void challengeCanBeTurnedOffForAnonymousPulls() throws Exception {
        repo.setFormatSettings(Map.of("docker.challengeAnonymous", "false"));
        repositories.update(REPO, repo);
        mvc.perform(get(URL + "/v2/")).andExpect(status().isOk()).andExpect(content().json("{}"));

        settings.setAnonymousReadEnabled(false);
        mvc.perform(get(URL + "/v2/"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Granary\""))
                .andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"));
    }

    @Test
    void readsNeedCredentialsWhenAnonymousAccessIsOff() throws Exception {
        pushImage(mvc, URL, "acme/app", "1.0", adminBasic());
        settings.setAnonymousReadEnabled(false);
        mvc.perform(get(URL + "/v2/acme/app/manifests/1.0"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("Basic")));
        mvc.perform(get(URL + "/v2/acme/app/tags/list")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/v2/acme/app/manifests/1.0").header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousPushIs401WithAChallenge() throws Exception {
        mvc.perform(post(URL + "/v2/acme/app/blobs/uploads/"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("Basic")));
        mvc.perform(put(URL + "/v2/acme/app/manifests/1.0").contentType(OCI_MANIFEST)
                        .content(manifest(OCI_MANIFEST, config("x"), layer("x"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/v2/_catalog")).andExpect(status().isOk()).andExpect(jsonPath("$.repositories").isEmpty());
    }

    @Test
    void apiTokenWorksAsPasswordAndAsBearer() throws Exception {
        User admin = users.require(ADMIN);
        String secret = tokens.issue(admin, "docker login", null).secret();
        assertThat(secret).startsWith("gry_");

        // docker login -u admin -p gry_...
        mvc.perform(get(URL + "/v2/").header(HttpHeaders.AUTHORIZATION, basic(ADMIN, secret))).andExpect(status().isOk());
        uploadBlob(mvc, URL, "acme/app", layer("t"), basic(ADMIN, secret));
        // containerd, oras and helm can also send the token as a bearer
        uploadBlob(mvc, URL, "acme/app", config("t"), "Bearer " + secret);
        mvc.perform(put(URL + "/v2/acme/app/manifests/1.0").header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                        .contentType(OCI_MANIFEST).content(manifest(OCI_MANIFEST, config("t"), layer("t"))))
                .andExpect(status().isCreated());

        mvc.perform(get(URL + "/v2/").header(HttpHeaders.AUTHORIZATION, basic(ADMIN, "wrong")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/v2/").header(HttpHeaders.AUTHORIZATION, "Bearer gry_not_a_real_token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readersCannotPushButDevelopersCan() throws Exception {
        users.create("docker-alice", "alicepass", "Alice", null, Set.of("reader"));
        users.create("docker-bob", "bobpassword", "Bob", null, Set.of("developer"));
        mvc.perform(post(URL + "/v2/acme/app/blobs/uploads/").header(HttpHeaders.AUTHORIZATION, basic("docker-alice", "alicepass")))
                .andExpect(status().isForbidden());
        mvc.perform(get(URL + "/v2/").header(HttpHeaders.AUTHORIZATION, basic("docker-alice", "alicepass"))).andExpect(status().isOk());
        pushImage(mvc, URL, "acme/app", "1.0", basic("docker-bob", "bobpassword"));
        mvc.perform(get(URL + "/v2/acme/app/manifests/1.0").header(HttpHeaders.AUTHORIZATION, basic("docker-alice", "alicepass")))
                .andExpect(status().isOk());
    }
}
