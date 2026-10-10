package dev.granary.server.helm;

import static dev.granary.server.helm.HelmFixtures.chart;
import static dev.granary.server.helm.HelmFixtures.index;
import static dev.granary.server.helm.HelmFixtures.multipartUpload;
import static dev.granary.server.helm.HelmFixtures.upload;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.server.IntegrationTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How Helm clients authenticate: {@code helm repo add --username --password} and {@code helm cm-push} send HTTP
 * Basic (an API token works as the password), CI scripts often send a Bearer token.
 */
class HelmAuthIntegrationTest extends IntegrationTest {

    private static final String URL = "/repository/helm-hosted";
    private static final List<String> TEST_USERS = List.of("helm-reader", "helm-dev");

    @BeforeEach
    void createRepository() {
        hosted("helm-hosted", "helm");
    }

    @AfterEach
    void removeTestUsers() {
        for (String username : TEST_USERS) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }

    private String tokenOf(String username) {
        return tokens.issue(users.require(username), "helm", null).secret();
    }

    @Test
    void anonymousUploadsAre401WithABasicChallengeAndNothingIsStored() throws Exception {
        mvc.perform(upload(URL, null, chart("demo", "1.0.0")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Basic")));
        mvc.perform(multipartUpload(URL, null, chart("demo", "1.0.0"), null)).andExpect(status().isUnauthorized());
        mvc.perform(delete(URL + "/api/charts/demo/1.0.0")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/charts/demo-1.0.0.tgz")).andExpect(status().isNotFound());
        mvc.perform(upload(URL, basic(ADMIN, "wrong-password"), chart("demo", "1.0.0"))).andExpect(status().isUnauthorized());
    }

    @Test
    void readersMayPullButNotPushOrDelete() throws Exception {
        users.create("helm-reader", "readerpassword", "Reader", null, Set.of("reader"));
        users.create("helm-dev", "developerpassword", "Developer", null, Set.of("developer"));
        String reader = basic("helm-reader", tokenOf("helm-reader"));
        String developer = basic("helm-dev", tokenOf("helm-dev"));

        mvc.perform(upload(URL, reader, chart("demo", "1.0.0"))).andExpect(status().isForbidden());
        mvc.perform(upload(URL, developer, chart("demo", "1.0.0"))).andExpect(status().isCreated());
        mvc.perform(index(URL).header("Authorization", reader)).andExpect(status().isOk());
        mvc.perform(get(URL + "/charts/demo-1.0.0.tgz").header("Authorization", reader)).andExpect(status().isOk());
        mvc.perform(delete(URL + "/api/charts/demo/1.0.0").header("Authorization", reader)).andExpect(status().isForbidden());
        mvc.perform(delete(URL + "/api/charts/demo/1.0.0").header("Authorization", developer)).andExpect(status().isOk());
    }

    @Test
    void passwordsTokensAsBasicPasswordAndBearerTokensAllWork() throws Exception {
        String token = tokenOf(ADMIN);
        mvc.perform(upload(URL, adminBasic(), chart("demo", "1.0.0"))).andExpect(status().isCreated());
        mvc.perform(upload(URL, basic(ADMIN, token), chart("demo", "1.0.1"))).andExpect(status().isCreated());
        mvc.perform(upload(URL, "Bearer " + token, chart("demo", "1.0.2"))).andExpect(status().isCreated());
        mvc.perform(upload(URL, basic(ADMIN, "gry_definitely_not_a_valid_token"), chart("demo", "1.0.3")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withoutAnonymousReadsEveryReadNeedsCredentialsExceptTheHealthCheck() throws Exception {
        mvc.perform(upload(URL, adminBasic(), chart("demo", "1.0.0"))).andExpect(status().isCreated());
        settings.setAnonymousReadEnabled(false);

        mvc.perform(index(URL)).andExpect(status().isUnauthorized()).andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(get(URL + "/charts/demo-1.0.0.tgz")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/api/charts")).andExpect(status().isUnauthorized());
        mvc.perform(index(URL).header("Authorization", basic(ADMIN, tokenOf(ADMIN)))).andExpect(status().isOk());
        mvc.perform(get(URL + "/charts/demo-1.0.0.tgz").header("Authorization", adminBasic())).andExpect(status().isOk());

        // load balancers and ChartMuseum clients probe /health without credentials
        mvc.perform(get(URL + "/health")).andExpect(status().isOk()).andExpect(jsonPath("$.healthy").value(true));
    }
}
