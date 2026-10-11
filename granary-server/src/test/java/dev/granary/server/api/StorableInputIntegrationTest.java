package dev.granary.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StorageService.ComponentCoordinates;
import dev.granary.server.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Input that a database column cannot hold: a NUL character, which PostgreSQL refuses in every text value while H2
 * accepts it, and values longer than their column. Each must get the same proper answer on both databases (a 400 for
 * input to store, "not found" or "no match" for a lookup), never a database error reported as {@code 409
 * storage.conflict} or {@code 500}. Run with {@code -Ppostgres} to check the PostgreSQL side.
 */
class StorableInputIntegrationTest extends IntegrationTest {

    /** A NUL character as a JSON escape. */
    static final String NUL = "\\u0000";

    @Autowired
    StorageService storage;

    private ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).header("Authorization", adminBasic())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions putJson(String url, String json) throws Exception {
        return mvc.perform(put(url).header("Authorization", adminBasic())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void credentialsWithANulInTheUsernameAreSimplyWrong() throws Exception {
        hosted("nul-raw", "raw");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ad" + NUL + "min\",\"password\":\"admin123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.invalid"));
        // Basic credentials are checked in a filter, where a database error used to escape as a 500
        mvc.perform(get("/repository/nul-raw/a.txt").header("Authorization", basic("ad\0min", ADMIN_PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/repository/nul-raw/a.txt").header("Authorization", basic("a".repeat(150), "x")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void searchTermsWithANulMatchNothing() throws Exception {
        Repository repo = hosted("nul-npm", "npm");
        storage.ensureComponent(repo, new ComponentCoordinates("", "left-pad", "1.0.0"));
        mvc.perform(get("/api/v1/search").param("q", "left\0pad").header("Authorization", adminBasic()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/repositories/nul-npm/components").param("q", "le\0ft").header("Authorization", adminBasic()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        // npm search reads the hosted components through the same query
        mvc.perform(get("/repository/nul-npm/-/v1/search").param("text", "left\0pad"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects.length()").value(0));
        mvc.perform(get("/api/v1/search").param("q", "left-pad").header("Authorization", adminBasic()))
                .andExpect(jsonPath("$.total").value(1));
    }

    /** The term is matched literally on both databases: LIKE wildcards and the escape character are plain text. */
    @Test
    void searchTermsAreMatchedLiterally() throws Exception {
        Repository repo = hosted("like-raw", "raw");
        storage.ensureComponent(repo, new ComponentCoordinates("", "a_b", "1"));
        storage.ensureComponent(repo, new ComponentCoordinates("", "axb", "1"));
        storage.ensureComponent(repo, new ComponentCoordinates("", "100%", "1"));
        storage.ensureComponent(repo, new ComponentCoordinates("", "back\\slash", "1"));
        mvc.perform(get("/api/v1/search").param("q", "a_b").header("Authorization", adminBasic()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("a_b"));
        mvc.perform(get("/api/v1/search").param("q", "%").header("Authorization", adminBasic()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("100%"));
        mvc.perform(get("/api/v1/search").param("q", "k\\s").header("Authorization", adminBasic()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("back\\slash"));
        mvc.perform(get("/api/v1/search").param("q", "\\").header("Authorization", adminBasic()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/search").param("q", "A_B").header("Authorization", adminBasic()))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void repositoryTextWithANulIsRefusedPerField() throws Exception {
        postJson("/api/v1/repositories", "{\"name\":\"nul-desc\",\"format\":\"raw\",\"type\":\"HOSTED\",\"description\":\"a" + NUL + "b\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation"))
                .andExpect(jsonPath("$.details.description").value("must not contain NUL characters"));
        postJson("/api/v1/repositories", "{\"name\":\"nul-proxy\",\"format\":\"raw\",\"type\":\"PROXY\","
                        + "\"remoteUrl\":\"http://example.com/\",\"remoteUsername\":\"u" + NUL + "x\",\"remotePassword\":\"p\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.remoteUsername").value("must not contain NUL characters"));
        postJson("/api/v1/repositories", "{\"name\":\"nul-proxy\",\"format\":\"raw\",\"type\":\"PROXY\","
                        + "\"remoteUrl\":\"http://example.com/\",\"remoteToken\":\"t" + NUL + "t\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.remoteToken").value("must not contain NUL characters"));
        assertThat(repositories.find("nul-desc")).isEmpty();
        assertThat(repositories.find("nul-proxy")).isEmpty();

        hosted("nul-edit", "raw");
        putJson("/api/v1/repositories/nul-edit", "{\"name\":\"nul-edit\",\"format\":\"raw\",\"type\":\"HOSTED\",\"description\":\"" + NUL + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.description").value("must not contain NUL characters"));
        assertThat(repositories.require("nul-edit").getDescription()).isNull();
    }

    /** Longer than the column used to be a 409 "concurrent change" on both databases: a retry can never succeed. */
    @Test
    void remoteSettingsLongerThanTheirColumnsAre400() throws Exception {
        String base = "{\"name\":\"long-proxy\",\"format\":\"raw\",\"type\":\"PROXY\",";
        postJson("/api/v1/repositories", base + "\"remoteUrl\":\"http://example.com/" + "x".repeat(1000) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation"))
                .andExpect(jsonPath("$.details.remoteUrl").value("size must be between 0 and 1000"));
        postJson("/api/v1/repositories", base + "\"remoteUrl\":\"http://example.com/\",\"remoteUsername\":\"" + "u".repeat(201) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.remoteUsername").exists());
        postJson("/api/v1/repositories", base + "\"remoteUrl\":\"http://example.com/\",\"remoteUsername\":\"u\",\"remotePassword\":\"" + "p".repeat(501) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.remotePassword").exists());
        postJson("/api/v1/repositories", base + "\"remoteUrl\":\"http://example.com/\",\"remoteToken\":\"" + "t".repeat(1001) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.remoteToken").exists());
        // exactly at the limits it is stored
        postJson("/api/v1/repositories", base + "\"remoteUrl\":\"http://example.com/" + "x".repeat(1000 - 19) + "\","
                        + "\"remoteUsername\":\"" + "u".repeat(200) + "\",\"remotePassword\":\"" + "p".repeat(500) + "\","
                        + "\"remoteToken\":\"" + "t".repeat(1000) + "\"}")
                .andExpect(status().isCreated());
        assertThat(repositories.require("long-proxy").getRemoteUrl()).hasSize(1000);
    }

    @Test
    void aGroupMemberNameWithANulIsAnUnknownMember() throws Exception {
        hosted("member-raw", "raw");
        postJson("/api/v1/repositories", "{\"name\":\"nul-group\",\"format\":\"raw\",\"type\":\"GROUP\",\"members\":[\"member" + NUL + "raw\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.members.unknown"));
    }

    @Test
    void userAndRoleTextWithANulIsRefused() throws Exception {
        postJson("/api/v1/users", "{\"username\":\"nul-user\",\"password\":\"secret123\",\"displayName\":\"d" + NUL + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.displayName").value("must not contain NUL characters"));
        postJson("/api/v1/users", "{\"username\":\"nul-user\",\"password\":\"secret123\",\"email\":\"e" + NUL + "@example.com\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.email").value("must not contain NUL characters"));
        postJson("/api/v1/users", "{\"username\":\"nul-user\",\"password\":\"secret123\",\"roles\":[\"reader" + NUL + "\"]}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("role.notFound"));
        assertThat(users.find("nul-user")).isEmpty();

        postJson("/api/v1/users", "{\"username\":\"nul-user\",\"password\":\"secret123\"}").andExpect(status().isCreated());
        putJson("/api/v1/users/nul-user", "{\"displayName\":\"" + NUL + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.displayName").exists());

        postJson("/api/v1/roles", "{\"name\":\"nul-role\",\"description\":\"d" + NUL + "\",\"privileges\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.description").value("must not contain NUL characters"));
    }

    @Test
    void tokenNamesWithANulAreInvalid() throws Exception {
        postJson("/api/v1/tokens", "{\"name\":\"ci" + NUL + "token\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("token.name.invalid"));
    }

    @Test
    void baseUrlWithANulOrLongerThanTheColumnIsRefused() throws Exception {
        putJson("/api/v1/system/settings", "{\"baseUrl\":\"https://repo" + NUL + ".example.com\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.baseUrl").value("must not contain NUL characters"));
        putJson("/api/v1/system/settings", "{\"baseUrl\":\"https://example.com/" + "x".repeat(2000) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.baseUrl").value("size must be between 0 and 2000"));
        assertThat(settings.baseUrl()).isEmpty();
        putJson("/api/v1/system/settings", "{\"baseUrl\":\"https://repo.example.com\"}").andExpect(status().isOk());
        assertThat(settings.baseUrl()).contains("https://repo.example.com");
    }
}
