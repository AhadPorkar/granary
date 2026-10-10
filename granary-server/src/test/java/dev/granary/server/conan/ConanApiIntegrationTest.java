package dev.granary.server.conan;

import static dev.granary.server.conan.ConanFixtures.MAPPER;
import static dev.granary.server.conan.ConanFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.server.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** The management API as the web UI and the verify script use it: templates, format settings and connect snippets. */
class ConanApiIntegrationTest extends IntegrationTest {

    @Test
    void standardRepositoriesComeFromTheTemplates() throws Exception {
        JsonNode created = json(mvc.perform(post("/api/v1/repositories/standard").header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(created.path("created").valueStream().map(JsonNode::asString)).contains("conancenter", "conan-hosted", "conan-group");

        mvc.perform(get("/api/v1/repositories/conancenter").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.type").value("PROXY"))
                .andExpect(jsonPath("$.format").value("conan"))
                .andExpect(jsonPath("$.remoteUrl").value("https://center2.conan.io"));
        mvc.perform(get("/api/v1/repositories/conan-hosted").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.type").value("HOSTED"))
                .andExpect(jsonPath("$.allowRedeploy").value(false));
        mvc.perform(get("/api/v1/repositories/conan-group").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.type").value("GROUP"))
                .andExpect(jsonPath("$.members[0]").value("conan-hosted"))
                .andExpect(jsonPath("$.members[1]").value("conancenter"));
        mvc.perform(get("/repository/conan-group/v1/ping")).andExpect(status().isOk());
    }

    @Test
    void theFormatAdvertisesItsSettingsAndTemplates() throws Exception {
        JsonNode formats = json(mvc.perform(get("/api/v1/formats").header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn());
        JsonNode conan = formats.valueStream().filter(f -> "conan".equals(f.path("id").asString())).findFirst().orElseThrow();
        assertThat(conan.path("displayName").asString()).isEqualTo("Conan (C/C++)");
        assertThat(conan.path("types").valueStream().map(JsonNode::asString)).containsExactlyInAnyOrder("HOSTED", "PROXY", "GROUP");
        assertThat(conan.path("templates").valueStream().map(JsonNode::asString))
                .containsExactly("conancenter", "conan-hosted", "conan-group");
        assertThat(conan.path("settings").valueStream().map(s -> s.path("key").asString()))
                .containsExactly("conan.tokenTtlHours", "conan.upstreamAuth");
    }

    @Test
    void invalidSettingsAreRefusedWhenTheRepositoryIsCreated() throws Exception {
        String bad = """
                {"name":"conan-bad","format":"conan","type":"HOSTED","formatSettings":{"conan.tokenTtlHours":"forever"}}
                """;
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer())
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.conan.tokenTtl"));
        String badAuth = """
                {"name":"conan-bad","format":"conan","type":"PROXY","remoteUrl":"https://center2.conan.io",
                 "formatSettings":{"conan.upstreamAuth":"kerberos"}}
                """;
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer())
                        .contentType(MediaType.APPLICATION_JSON).content(badAuth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.conan.upstreamAuth"));
        assertThat(repositories.find("conan-bad")).isEmpty();

        String good = """
                {"name":"conan-ci","format":"conan","type":"HOSTED","formatSettings":{"conan.tokenTtlHours":"8"}}
                """;
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer())
                        .contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.formatSettings['conan.tokenTtlHours']").value("8"));
    }

    @Test
    void connectSnippetsCoverTheClient() throws Exception {
        hosted("conan-hosted", "conan");
        proxy("conancenter", "conan", "https://center2.conan.io");
        group("conan-group", "conan", "conan-hosted", "conancenter");

        JsonNode snippets = json(mvc.perform(get("/api/v1/repositories/conan-group/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn());
        List<String> ids = snippets.valueStream().map(s -> s.path("id").asString()).toList();
        assertThat(ids).containsExactly("remote-add", "login", "env", "install", "upload", "search", "tls", "conan1");
        String all = String.join("\n", snippets.valueStream().map(s -> s.path("code").asString()).toList());
        assertThat(all).contains("conan remote add granary http://localhost/repository/conan-group --index 0")
                .contains("conan remote remove conancenter")
                .contains("conan remote login granary admin -p <api-token>")
                .contains("CONAN_LOGIN_USERNAME_GRANARY")
                .contains("conan upload \"*\" -r granary-hosted --confirm")
                .contains("http://localhost/repository/conan-hosted");

        JsonNode proxySnippets = json(mvc.perform(get("/api/v1/repositories/conancenter/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn());
        String proxyAll = MAPPER.writeValueAsString(proxySnippets);
        assertThat(proxyAll).contains("read-only").doesNotContain("conan upload \\\"*\\\" -r granary --confirm");
    }
}
