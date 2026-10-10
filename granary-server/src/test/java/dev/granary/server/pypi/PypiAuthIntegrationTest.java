package dev.granary.server.pypi;

import static dev.granary.server.pypi.PypiFixtures.PIP_ACCEPT;
import static dev.granary.server.pypi.PypiFixtures.uploadWheel;
import static dev.granary.server.pypi.PypiFixtures.wheel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.User;
import dev.granary.core.security.ApiTokenService;
import dev.granary.server.IntegrationTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** How twine, pip and uv authenticate: HTTP Basic with a password or with {@code __token__} and an API token. */
class PypiAuthIntegrationTest extends IntegrationTest {

    private static final String URL = "/repository/pypi-hosted";
    private static final String WHEEL_PATH = "packages/acme-demo/1.0.0/acme_demo-1.0.0-py3-none-any.whl";

    /** Users outlive repositories in the shared test context, so every user a test creates is removed again. */
    private static final List<String> TEST_USERS = List.of("pypi-alice", "pypi-bob");

    private final byte[] wheel = wheel("acme-demo", "1.0.0");

    @BeforeEach
    void createRepository() {
        hosted("pypi-hosted", "pypi");
    }

    @AfterEach
    void removeTestUsers() {
        for (String username : TEST_USERS) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }

    @Test
    void anonymousUploadIs401WithABasicChallenge() throws Exception {
        mvc.perform(uploadWheel(URL + "/", "acme-demo", "1.0.0", wheel))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Basic")));
        mvc.perform(delete(URL + "/" + WHEEL_PATH))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT)).andExpect(status().isNotFound());
    }

    @Test
    void readersCannotUploadButDevelopersCan() throws Exception {
        users.create("pypi-alice", "alicepass", "Alice", null, Set.of("reader"));
        users.create("pypi-bob", "bobpassword", "Bob", null, Set.of("developer"));
        mvc.perform(uploadWheel(URL + "/", "acme-demo", "1.0.0", wheel).header("Authorization", basic("pypi-alice", "alicepass")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(uploadWheel(URL + "/", "acme-demo", "1.0.0", wheel).header("Authorization", basic("pypi-bob", "bobpassword")))
                .andExpect(status().isOk());
        mvc.perform(get(URL + "/" + WHEEL_PATH).header("Authorization", basic("pypi-alice", "alicepass"))).andExpect(status().isOk());
        mvc.perform(delete(URL + "/" + WHEEL_PATH).header("Authorization", basic("pypi-alice", "alicepass"))).andExpect(status().isForbidden());
        mvc.perform(delete(URL + "/" + WHEEL_PATH).header("Authorization", basic("pypi-bob", "bobpassword"))).andExpect(status().isNoContent());
    }

    @Test
    void apiTokensWorkAsThePasswordOfTokenAndOfTheUser() throws Exception {
        User admin = users.require(ADMIN);
        ApiTokenService.IssuedToken issued = tokens.issue(admin, "twine", null);
        assertThat(issued.secret()).startsWith("gry_");
        // twine's recommended form: username __token__, password the token
        mvc.perform(uploadWheel(URL + "/", "acme-demo", "1.0.0", wheel).header("Authorization", basic("__token__", issued.secret())))
                .andExpect(status().isOk());
        // uv publish and pip also accept the real username with the token as password
        mvc.perform(uploadWheel(URL + "/", "acme-demo", "1.1.0", wheel("acme-demo", "1.1.0")).header("Authorization", basic(ADMIN, issued.secret())))
                .andExpect(status().isOk());
        mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT).header("Authorization", basic("__token__", issued.secret())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.length()").value(2));

        mvc.perform(uploadWheel(URL + "/", "acme-demo", "1.2.0", wheel("acme-demo", "1.2.0")).header("Authorization", basic("__token__", "gry_not_a_real_token")))
                .andExpect(status().isUnauthorized());
        mvc.perform(uploadWheel(URL + "/", "acme-demo", "1.2.0", wheel("acme-demo", "1.2.0")).header("Authorization", basic(ADMIN, "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readsNeedCredentialsWhenAnonymousAccessIsOff() throws Exception {
        mvc.perform(uploadWheel(URL + "/", "acme-demo", "1.0.0", wheel).header("Authorization", adminBasic())).andExpect(status().isOk());
        settings.setAnonymousReadEnabled(false);
        mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT)).andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(get(URL + "/" + WHEEL_PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/simple/")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT).header("Authorization", adminBasic())).andExpect(status().isOk());
        mvc.perform(get(URL + "/" + WHEEL_PATH).header("Authorization", adminBasic())).andExpect(status().isOk());
        mvc.perform(get(URL + "/pypi/acme-demo/json").header("Authorization", adminBasic())).andExpect(status().isOk());
    }
}
