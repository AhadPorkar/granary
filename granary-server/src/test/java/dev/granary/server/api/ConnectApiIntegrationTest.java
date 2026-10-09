package dev.granary.server.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.server.IntegrationTest;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** {@code GET /api/v1/repositories/{name}/connect}: the server renders the client configuration of a repository. */
class ConnectApiIntegrationTest extends IntegrationTest {

    @Test
    void mavenSnippetsCarryTheRepositoryUrlAndTheCallersUsername() throws Exception {
        hosted("maven-releases", "maven2");
        mvc.perform(get("/api/v1/repositories/maven-releases/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("maven-settings"))
                .andExpect(jsonPath("$[0].language").value("xml"))
                .andExpect(jsonPath("$[0].filename").value("~/.m2/settings.xml"))
                .andExpect(jsonPath("$[0].code").value(Matchers.containsString("<url>http://localhost/repository/maven-releases</url>")))
                .andExpect(jsonPath("$[0].code").value(Matchers.containsString("<username>admin</username>")))
                .andExpect(jsonPath("$[0].code").value(Matchers.containsString("<password><api-token></password>")))
                .andExpect(jsonPath("$[?(@.id=='maven-deploy')].filename").doesNotExist())
                .andExpect(jsonPath("$[?(@.id=='gradle-kotlin')].code").value(Matchers.hasItem(Matchers.containsString("pluginManagement"))))
                .andExpect(jsonPath("$.length()").value(Matchers.greaterThan(10)));
    }

    @Test
    void theConfiguredBaseUrlWinsOverTheRequestHost() throws Exception {
        hosted("npm-hosted", "npm");
        settings.setBaseUrl("https://packages.example.com");
        mvc.perform(get("/api/v1/repositories/npm-hosted/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("npm-registry"))
                .andExpect(jsonPath("$[0].code").value("npm config set registry https://packages.example.com/repository/npm-hosted/"))
                .andExpect(jsonPath("$[1].code").value(Matchers.containsString("//packages.example.com/repository/npm-hosted/:_authToken=<api-token>")));
    }

    @Test
    void readAccessIsRequired() throws Exception {
        hosted("a", "nuget");
        hosted("b", "nuget");
        mvc.perform(post("/api/v1/roles").header("Authorization", adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a-readers\",\"privileges\":[\"repository:read:a\"]}"))
                .andExpect(status().isCreated());
        users.create("scoped", "password1", null, null, Set.of("a-readers"));

        mvc.perform(get("/api/v1/repositories/a/connect")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/repositories/a/connect").header("Authorization", bearer("scoped")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("nuget-source"))
                .andExpect(jsonPath("$[0].code").value(Matchers.containsString("-u scoped -p <api-token>")));
        mvc.perform(get("/api/v1/repositories/b/connect").header("Authorization", bearer("scoped")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
        mvc.perform(get("/api/v1/repositories/missing/connect").header("Authorization", adminBearer()))
                .andExpect(status().isNotFound());
    }
}
