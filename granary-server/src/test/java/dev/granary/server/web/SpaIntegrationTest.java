package dev.granary.server.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.server.IntegrationTest;
import org.junit.jupiter.api.Test;

/** Client-side routes must survive a browser refresh; API and repository paths must not be swallowed. */
class SpaIntegrationTest extends IntegrationTest {

    @Test
    void servesIndexForClientRoutes() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<html")));
        mvc.perform(get("/repositories/maven-central")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<html")));
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }

    @Test
    void doesNotMaskRealEndpoints() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isUnauthorized());
        mvc.perform(get("/repository/nope/x")).andExpect(status().isNotFound());
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
    }
}
