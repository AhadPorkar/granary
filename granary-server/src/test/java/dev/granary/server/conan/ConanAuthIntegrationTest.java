package dev.granary.server.conan;

import static dev.granary.server.conan.ConanFixtures.CAPABILITIES;
import static dev.granary.server.conan.ConanFixtures.RREV;
import static dev.granary.server.conan.ConanFixtures.recipe;
import static dev.granary.server.conan.ConanFixtures.revision;
import static dev.granary.server.conan.ConanFixtures.text;
import static dev.granary.server.conan.ConanFixtures.upload;
import static dev.granary.server.conan.ConanFixtures.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.ApiToken;
import dev.granary.core.model.Repository;
import dev.granary.server.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** {@code conan remote login}, tokens, and who may read and write a Conan repository. */
class ConanAuthIntegrationTest extends IntegrationTest {

    private static final String REPO = "conan-hosted";
    private static final String URL = "/repository/" + REPO;
    private static final String ZLIB = recipe("zlib", "1.3.1");

    @BeforeEach
    void createRepository() {
        hosted(REPO, "conan");
    }

    @Test
    void loginExchangesBasicCredentialsForAnExpiringApiToken() throws Exception {
        String token = text(mvc.perform(get(URL + "/v2/users/authenticate").header("Authorization", adminBasic()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn());
        assertThat(token).startsWith("gry_");

        ApiToken issued = tokens.list(users.require(ADMIN)).stream().filter(t -> t.getName().equals("conan")).findFirst().orElseThrow();
        assertThat(issued.getExpiresAt()).isBetween(Instant.now().plus(Duration.ofHours(23)), Instant.now().plus(Duration.ofHours(25)));

        mvc.perform(get(URL + "/v2/users/check_credentials").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string("admin"));
        mvc.perform(upload(URL + revision(ZLIB, RREV) + "/files/conanfile.py", utf8("x"), "Bearer " + token))
                .andExpect(status().isCreated());
    }

    @Test
    void conan1LogsInThroughTheV1Routes() throws Exception {
        // Conan 1.x with revisions enabled uses v2 for everything except "conan user"
        String token = text(mvc.perform(get(URL + "/v1/users/authenticate").header("Authorization", adminBasic()))
                .andExpect(status().isOk()).andReturn());
        assertThat(token).startsWith("gry_");
        mvc.perform(get(URL + "/v1/users/check_credentials").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string("admin"));
        mvc.perform(get(URL + "/v1/users/authenticate").header("Authorization", basic(ADMIN, "wrong")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/v1/conans/zlib/1.3.1/_/_/digest"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("revisions enabled")));
    }

    @Test
    void loginWithAnApiTokenAsPasswordReturnsThatToken() throws Exception {
        String existing = tokens.issue(users.require(ADMIN), "ci", null).secret();
        mvc.perform(get(URL + "/v2/users/authenticate").header("Authorization", basic(ADMIN, existing)))
                .andExpect(status().isOk()).andExpect(content().string(existing));
        assertThat(tokens.list(users.require(ADMIN))).extracting(ApiToken::getName).doesNotContain("conan");
    }

    @Test
    void tokenLifetimeIsARepositorySetting() throws Exception {
        Repository repo = repositories.require(REPO);
        repo.setFormatSettings(Map.of("conan.tokenTtlHours", "0"));
        repositories.update(REPO, repo);
        mvc.perform(post(URL + "/v2/users/authenticate").header("Authorization", adminBasic())).andExpect(status().isOk());
        ApiToken issued = tokens.list(users.require(ADMIN)).stream().filter(t -> t.getName().equals("conan")).findFirst().orElseThrow();
        assertThat(issued.getExpiresAt()).isNull();
    }

    @Test
    void wrongCredentialsAndMissingCredentials() throws Exception {
        mvc.perform(get(URL + "/v2/users/authenticate").header("Authorization", basic(ADMIN, "wrong")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/v2/users/authenticate"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")));
        // the client probes with a placeholder bearer when it has no token: that must be a 401, never a 500
        mvc.perform(get(URL + "/v2/users/check_credentials").header("Authorization", "Bearer unset"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/v2/users/check_credentials")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/v2/users/check_credentials").header("Authorization", "Bearer gry_expired_or_revoked_token_value_xx"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousWritesAre401WithAChallenge() throws Exception {
        mvc.perform(upload(URL + revision(ZLIB, RREV) + "/files/conanfile.py", utf8("x"), null))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")));
        mvc.perform(delete(URL + revision(ZLIB, RREV))).andExpect(status().isUnauthorized());
    }

    @Test
    void readersMayInstallButNotUpload() throws Exception {
        users.create("conan-reader", "readerpass1", "Reader", null, Set.of("reader"));
        String token = text(mvc.perform(get(URL + "/v2/users/authenticate").header("Authorization", basic("conan-reader", "readerpass1")))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(get(URL + "/v2/users/check_credentials").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string("conan-reader"));
        mvc.perform(upload(URL + revision(ZLIB, RREV) + "/files/conanfile.py", utf8("x"), "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(delete(URL + revision(ZLIB, RREV)).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get(URL + ZLIB + "/latest").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    @Test
    void withAnonymousReadOffEverythingButPingAndLoginNeedsCredentials() throws Exception {
        settings.setAnonymousReadEnabled(false);
        mvc.perform(get(URL + "/v1/ping")).andExpect(status().isOk())
                .andExpect(header().string("X-Conan-Server-Capabilities", CAPABILITIES));
        mvc.perform(get(URL + ZLIB + "/latest")).andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(get(URL + "/v2/conans/search").param("q", "*")).andExpect(status().isUnauthorized());
        String token = text(mvc.perform(get(URL + "/v2/users/authenticate").header("Authorization", adminBasic()))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(get(URL + ZLIB + "/latest").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/v2/conans/search").param("q", "*").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void offlineRepositoryStillAnswersPing() throws Exception {
        Repository repo = repositories.require(REPO);
        repo.setOnline(false);
        repositories.update(REPO, repo);
        mvc.perform(get(URL + "/v1/ping")).andExpect(status().isOk());
        mvc.perform(get(URL + ZLIB + "/latest")).andExpect(status().isServiceUnavailable());
    }

    /** Users outlive repositories in the shared test context, so the ones a test creates are removed again. */
    @AfterEach
    void removeTestUsers() {
        for (String username : List.of("conan-reader")) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }
}
