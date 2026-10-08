package dev.granary.server.npm;

import static dev.granary.server.npm.NpmFixtures.object;
import static dev.granary.server.npm.NpmFixtures.publishBody;
import static dev.granary.server.npm.NpmFixtures.str;
import static dev.granary.server.npm.NpmFixtures.tarball;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.ApiToken;
import dev.granary.core.model.User;
import dev.granary.server.IntegrationTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.node.ObjectNode;

/** How the npm CLI authenticates: legacy login issuing a token, Bearer/Basic usage, whoami, ping and the anonymous cases. */
class NpmAuthIntegrationTest extends IntegrationTest {

    private static final String URL = "/repository/npm-hosted";

    /** Users outlive repositories in the shared test context, so every user a test creates is removed again. */
    private static final List<String> TEST_USERS = List.of("npm-alice", "npm-bob", "npm-carol");

    @BeforeEach
    void createRepository() {
        hosted("npm-hosted", "npm");
    }

    @AfterEach
    void removeTestUsers() {
        for (String username : TEST_USERS) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }

    @Test
    void legacyLoginIssuesATokenThatAuthorisesPublishing() throws Exception {
        String body = "{\"_id\":\"org.couchdb.user:admin\",\"name\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\","
                + "\"type\":\"user\",\"roles\":[],\"date\":\"2026-10-08T00:00:00.000Z\"}";
        ObjectNode login = object(mvc.perform(put(URL + "/-/user/org.couchdb.user:admin")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.id").value("org.couchdb.user:admin"))
                .andExpect(jsonPath("$.rev").exists())
                .andReturn());
        String token = str(login, "token");
        assertThat(token).startsWith("gry_");

        User admin = users.require(ADMIN);
        assertThat(tokens.list(admin)).extracting(ApiToken::getName).contains("npm login");

        mvc.perform(get(URL + "/-/whoami").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));

        mvc.perform(put(URL + "/demo").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody("demo", "1.0.0", tarball("x"))))
                .andExpect(status().isCreated());

        // the token also works as the password of HTTP Basic (npm's _auth style)
        mvc.perform(get(URL + "/-/whoami").header("Authorization", basic("anything", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void loginWithTheWrongPasswordIs401() throws Exception {
        mvc.perform(put(URL + "/-/user/org.couchdb.user:admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"admin\",\"password\":\"wrong\",\"type\":\"user\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"));
        mvc.perform(put(URL + "/-/user/org.couchdb.user:ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ghost\",\"password\":\"whatever1\",\"type\":\"user\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(tokens.list(users.require(ADMIN))).extracting(ApiToken::getName).doesNotContain("npm login");
    }

    @Test
    void disabledUsersCannotLogIn() throws Exception {
        users.create("npm-carol", "carolpassword", "Carol", null, Set.of("developer"));
        users.update("npm-carol", "Carol", null, false, null);
        mvc.perform(put(URL + "/-/user/org.couchdb.user:npm-carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"npm-carol\",\"password\":\"carolpassword\",\"type\":\"user\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webLoginIs404SoNpmFallsBackToLegacyLogin() throws Exception {
        mvc.perform(post(URL + "/-/v1/login").contentType(MediaType.APPLICATION_JSON).content("{\"hostname\":\"dev\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void pingAndWhoamiAreReachableWithoutRepositoryPrivileges() throws Exception {
        settings.setAnonymousReadEnabled(false);
        mvc.perform(get(URL + "/-/ping")).andExpect(status().isOk()).andExpect(content().json("{}"));
        mvc.perform(get(URL + "/-/whoami")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(get(URL + "/-/whoami").header("Authorization", adminBasic())).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void anonymousPublishIs401WithAChallenge() throws Exception {
        mvc.perform(put(URL + "/demo").contentType(MediaType.APPLICATION_JSON).content(publishBody("demo", "1.0.0", tarball("x"))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")));
        mvc.perform(get(URL + "/demo")).andExpect(status().isNotFound());
    }

    @Test
    void readersCannotPublishButDevelopersCan() throws Exception {
        users.create("npm-alice", "alicepass", "Alice", null, Set.of("reader"));
        users.create("npm-bob", "bobpassword", "Bob", null, Set.of("developer"));
        mvc.perform(put(URL + "/demo").header("Authorization", basic("npm-alice", "alicepass"))
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody("demo", "1.0.0", tarball("x"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(get(URL + "/-/whoami").header("Authorization", basic("npm-alice", "alicepass")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("npm-alice"));

        mvc.perform(put(URL + "/demo").header("Authorization", basic("npm-bob", "bobpassword"))
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody("demo", "1.0.0", tarball("x"))))
                .andExpect(status().isCreated());
        mvc.perform(get(URL + "/demo").header("Authorization", basic("npm-alice", "alicepass"))).andExpect(status().isOk());
    }

    @Test
    void readsNeedCredentialsWhenAnonymousAccessIsOff() throws Exception {
        settings.setAnonymousReadEnabled(false);
        mvc.perform(get(URL + "/demo")).andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(get(URL + "/demo").header("Authorization", adminBasic())).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/-/v1/search").param("text", "x")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/-/v1/search").param("text", "x").header("Authorization", adminBasic())).andExpect(status().isOk());
    }

    @Test
    void badCredentialsAreRejectedBeforeTheHandlerRuns() throws Exception {
        mvc.perform(get(URL + "/-/whoami").header("Authorization", "Bearer gry_definitely_not_a_valid_token_value"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/-/whoami").header("Authorization", basic("admin", "nope")))
                .andExpect(status().isUnauthorized());
    }
}
