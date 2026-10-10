package dev.granary.server.go;

import static dev.granary.server.go.GoFixtures.MODULE;
import static dev.granary.server.go.GoFixtures.curlPut;
import static dev.granary.server.go.GoFixtures.goGet;
import static dev.granary.server.go.GoFixtures.moduleZip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.security.ApiTokenService;
import dev.granary.server.IntegrationTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How the go command and curl authenticate: HTTP Basic from {@code .netrc} or from credentials in the GOPROXY URL,
 * with a password or an API token as the password.
 */
class GoAuthIntegrationTest extends IntegrationTest {

    private static final String REPO = "go-hosted";
    private static final String ZIP_PATH = MODULE + "/@v/v0.1.0.zip";

    /** Users outlive repositories in the shared test context, so every user a test creates is removed again. */
    private static final List<String> TEST_USERS = List.of("go-alice", "go-bob");

    private final byte[] zip = moduleZip(MODULE, "v0.1.0");

    @BeforeEach
    void createRepository() {
        hosted(REPO, "go");
    }

    @AfterEach
    void removeTestUsers() {
        for (String username : TEST_USERS) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }

    @Test
    void anonymousPublishingIs401WithABasicChallenge() throws Exception {
        mvc.perform(curlPut(REPO, ZIP_PATH, zip))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Basic")));
        mvc.perform(delete("/repository/" + REPO + "/" + MODULE + "/@v/v0.1.0")).andExpect(status().isUnauthorized());
        mvc.perform(goGet(REPO, MODULE + "/@v/list")).andExpect(status().isNotFound());
    }

    @Test
    void readersCannotPublishButDevelopersCan() throws Exception {
        users.create("go-alice", "alicepass", "Alice", null, Set.of("reader"));
        users.create("go-bob", "bobpassword", "Bob", null, Set.of("developer"));
        mvc.perform(curlPut(REPO, ZIP_PATH, zip).header("Authorization", basic("go-alice", "alicepass")))
                .andExpect(status().isForbidden());
        mvc.perform(curlPut(REPO, ZIP_PATH, zip).header("Authorization", basic("go-bob", "bobpassword")))
                .andExpect(status().isCreated());
        mvc.perform(goGet(REPO, ZIP_PATH).header("Authorization", basic("go-alice", "alicepass"))).andExpect(status().isOk());
        mvc.perform(delete("/repository/" + REPO + "/" + MODULE + "/@v/v0.1.0").header("Authorization", basic("go-alice", "alicepass")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/repository/" + REPO + "/" + MODULE + "/@v/v0.1.0").header("Authorization", basic("go-bob", "bobpassword")))
                .andExpect(status().isOk());
    }

    @Test
    void apiTokensWorkAsTheBasicPasswordAndAsBearer() throws Exception {
        ApiTokenService.IssuedToken issued = tokens.issue(users.require(ADMIN), "go", null);
        assertThat(issued.secret()).startsWith("gry_");
        mvc.perform(curlPut(REPO, ZIP_PATH, zip).header("Authorization", basic(ADMIN, issued.secret()))).andExpect(status().isCreated());
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.2.0.zip", moduleZip(MODULE, "v0.2.0")).header("Authorization", "Bearer " + issued.secret()))
                .andExpect(status().isCreated());
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.3.0.zip", moduleZip(MODULE, "v0.3.0")).header("Authorization", basic(ADMIN, "gry_not_a_real_token")))
                .andExpect(status().isUnauthorized());
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.3.0.zip", moduleZip(MODULE, "v0.3.0")).header("Authorization", basic(ADMIN, "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readsNeedCredentialsWhenAnonymousAccessIsOff() throws Exception {
        mvc.perform(curlPut(REPO, ZIP_PATH, zip).header("Authorization", adminBasic())).andExpect(status().isCreated());
        settings.setAnonymousReadEnabled(false);
        for (String path : new String[] {MODULE + "/@v/list", MODULE + "/@latest", MODULE + "/@v/v0.1.0.info", MODULE + "/@v/v0.1.0.mod", ZIP_PATH}) {
            mvc.perform(goGet(REPO, path)).andExpect(status().isUnauthorized()).andExpect(header().exists("WWW-Authenticate"));
            mvc.perform(goGet(REPO, path).header("Authorization", adminBasic())).andExpect(status().isOk());
        }
    }
}
