package dev.granary.server.cargo;

import static dev.granary.server.cargo.CargoFixtures.api;
import static dev.granary.server.cargo.CargoFixtures.download;
import static dev.granary.server.cargo.CargoFixtures.index;
import static dev.granary.server.cargo.CargoFixtures.publish;
import static dev.granary.server.cargo.CargoFixtures.publishBody;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.Repository;
import dev.granary.server.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * How cargo authenticates: the token is sent bare in {@code Authorization}, only for writes unless
 * {@code config.json} says {@code auth-required}, in which case it goes on every request.
 */
class CargoAuthIntegrationTest extends IntegrationTest {

    private static final String REPO = "cargo-hosted";
    private static final String URL = "/repository/" + REPO;
    private static final List<String> TEST_USERS = List.of("cargo-alice", "cargo-bob");

    private Repository repo;

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "cargo");
    }

    @AfterEach
    void removeTestUsers() {
        for (String username : TEST_USERS) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }

    private String tokenOf(String username) {
        return tokens.issue(users.require(username), "cargo", null).secret();
    }

    @Test
    void anonymousPublishIs401WithAChallengeAndNothingIsStored() throws Exception {
        mvc.perform(publish(URL, null, publishBody("acme", "1.0.0")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", Matchers.startsWith("Basic")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(index(URL + "/index/ac/me/acme")).andExpect(status().isNotFound());
    }

    @Test
    void readersCannotPublishYankOrChangeOwnersButDevelopersCan() throws Exception {
        users.create("cargo-alice", "alicepassword", "Alice", null, Set.of("reader"));
        users.create("cargo-bob", "bobpassword1", "Bob", null, Set.of("developer"));
        String reader = tokenOf("cargo-alice");
        String developer = tokenOf("cargo-bob");

        mvc.perform(publish(URL, reader, publishBody("acme", "1.0.0"))).andExpect(status().isForbidden());
        mvc.perform(publish(URL, developer, publishBody("acme", "1.0.0"))).andExpect(status().isOk());

        mvc.perform(api(delete(URL + "/api/v1/crates/acme/1.0.0/yank"), reader)).andExpect(status().isForbidden());
        mvc.perform(api(put(URL + "/api/v1/crates/acme/owners"), reader)
                .contentType(MediaType.APPLICATION_JSON).content("{\"users\":[\"cargo-alice\"]}"))
                .andExpect(status().isForbidden());
        // reading the owners is a read
        mvc.perform(api(get(URL + "/api/v1/crates/acme/owners"), reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].login").value("cargo-bob"));

        mvc.perform(api(delete(URL + "/api/v1/crates/acme/1.0.0/yank"), developer)).andExpect(status().isOk());
    }

    @Test
    void theTokenWorksBareAsBearerAndAsBasicPassword() throws Exception {
        String token = tokenOf(ADMIN);
        mvc.perform(publish(URL, token, publishBody("acme", "1.0.0"))).andExpect(status().isOk());
        mvc.perform(publish(URL, "Bearer " + token, publishBody("acme", "1.0.1"))).andExpect(status().isOk());
        mvc.perform(publish(URL, basic(ADMIN, token), publishBody("acme", "1.0.2"))).andExpect(status().isOk());
        mvc.perform(publish(URL, "gry_definitely_not_a_valid_token_value", publishBody("acme", "1.0.3")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withoutAnonymousReadsConfigJsonDemandsAuthAndEveryReadNeedsTheToken() throws Exception {
        String token = tokenOf(ADMIN);
        mvc.perform(publish(URL, token, publishBody("acme", "1.0.0"))).andExpect(status().isOk());
        settings.setAnonymousReadEnabled(false);

        // cargo first asks without a token; the 401 makes it retry with one
        mvc.perform(index(URL + "/index/config.json"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(index(URL + "/index/config.json").header("authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth-required").value(true));

        mvc.perform(index(URL + "/index/ac/me/acme")).andExpect(status().isUnauthorized());
        mvc.perform(index(URL + "/index/ac/me/acme").header("authorization", token)).andExpect(status().isOk());
        mvc.perform(download(URL + "/api/v1/crates/acme/1.0.0/download")).andExpect(status().isUnauthorized());
        mvc.perform(download(URL + "/api/v1/crates/acme/1.0.0/download").header("authorization", token))
                .andExpect(status().isOk());
        mvc.perform(api(get(URL + "/api/v1/crates").param("q", "acme"), null)).andExpect(status().isUnauthorized());
        mvc.perform(api(get(URL + "/api/v1/crates").param("q", "acme"), token)).andExpect(status().isOk());

        // where `cargo login` points people stays reachable
        mvc.perform(get(URL + "/me")).andExpect(status().isOk());
    }

    @Test
    void authRequiredSettingMakesCargoSendTheTokenEvenWhenAnonymousMayRead() throws Exception {
        mvc.perform(index(URL + "/index/config.json")).andExpect(jsonPath("$.auth-required").value(false));
        repo.setFormatSettings(Map.of("cargo.authRequired", "true"));
        repositories.update(REPO, repo);
        mvc.perform(index(URL + "/index/config.json")).andExpect(jsonPath("$.auth-required").value(true));
    }

    @Test
    void invalidSettingsAreRejectedWhenTheRepositoryIsSaved() throws Exception {
        String body = """
                {"name":"cargo-bad","format":"cargo","type":"PROXY","remoteUrl":"https://index.crates.io/",
                 "formatSettings":{"cargo.downloadUrl":"not a url"}}""";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/repositories")
                        .header("Authorization", adminBasic()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.cargo.downloadUrl.invalid"));
        String apiUrl = body.replace("\"cargo.downloadUrl\":\"not a url\"", "\"cargo.apiUrl\":\"ftp://crates.io\"");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/repositories")
                        .header("Authorization", adminBasic()).contentType(MediaType.APPLICATION_JSON).content(apiUrl))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.cargo.apiUrl.invalid"));
        String flag = body.replace("\"cargo.downloadUrl\":\"not a url\"", "\"cargo.authRequired\":\"maybe\"");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/repositories")
                        .header("Authorization", adminBasic()).contentType(MediaType.APPLICATION_JSON).content(flag))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.cargo.authRequired.invalid"));
        String good = body.replace("\"not a url\"", "\"https://mirror.example.com/{crate}/{version}.crate\"");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/repositories")
                        .header("Authorization", adminBasic()).contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isCreated());
    }
}
