package dev.granary.server.pypi;

import static dev.granary.server.pypi.PypiFixtures.object;
import static dev.granary.server.pypi.PypiFixtures.str;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The management API as the web UI and the verify script use it: templates, format settings and connect snippets. */
class PypiApiIntegrationTest extends IntegrationTest {

    @Test
    void standardRepositoriesComeFromTheTemplates() throws Exception {
        ObjectNode created = object(mvc.perform(post("/api/v1/repositories/standard").header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(created.path("created").valueStream().map(JsonNode::asString)).contains("pypi-proxy", "pypi-hosted", "pypi-group");
        assertThat(created.path("templates").valueStream().map(JsonNode::asString)).contains("pypi-proxy", "pypi-hosted", "pypi-group");

        mvc.perform(get("/api/v1/repositories/pypi-proxy").header("Authorization", adminBearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PROXY"))
                .andExpect(jsonPath("$.format").value("pypi"))
                .andExpect(jsonPath("$.remoteUrl").value("https://pypi.org/"));
        mvc.perform(get("/api/v1/repositories/pypi-group").header("Authorization", adminBearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("GROUP"))
                .andExpect(jsonPath("$.members[0]").value("pypi-hosted"))
                .andExpect(jsonPath("$.members[1]").value("pypi-proxy"));
        mvc.perform(get("/api/v1/repositories/pypi-hosted").header("Authorization", adminBearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("HOSTED"))
                .andExpect(jsonPath("$.allowRedeploy").value(false));
        mvc.perform(get("/repository/pypi-group/")).andExpect(status().isOk());
    }

    @Test
    void theFormatAdvertisesItsSettingsAndTemplates() throws Exception {
        ArrayNode formats = (ArrayNode) PypiFixtures.MAPPER.readTree(mvc.perform(get("/api/v1/formats").header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        JsonNode pypi = formats.valueStream().filter(f -> "pypi".equals(str(f, "id"))).findFirst().orElseThrow();
        assertThat(str(pypi, "displayName")).isEqualTo("PyPI");
        assertThat(pypi.path("types").valueStream().map(JsonNode::asString)).containsExactlyInAnyOrder("HOSTED", "PROXY", "GROUP");
        assertThat(pypi.path("templates").valueStream().map(JsonNode::asString)).containsExactly("pypi-proxy", "pypi-hosted", "pypi-group");
        JsonNode setting = pypi.path("settings").get(0);
        assertThat(str(setting, "key")).isEqualTo("pypi.filesUrl");
        assertThat(str(setting, "defaultValue")).isEqualTo("https://files.pythonhosted.org/");
    }

    @Test
    void aBadFilesHostIsRefusedWhenTheRepositoryIsCreated() throws Exception {
        String body = """
                {"name":"pypi-bad","format":"pypi","type":"PROXY","remoteUrl":"https://pypi.org/",
                 "formatSettings":{"pypi.filesUrl":"files.pythonhosted.org/"}}
                """;
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.pypi.filesUrl.invalid"));
        assertThat(repositories.find("pypi-bad")).isEmpty();

        String mirror = """
                {"name":"pypi-mirror","format":"pypi","type":"PROXY","remoteUrl":"https://mirror.example/pypi/",
                 "formatSettings":{"pypi.filesUrl":"https://mirror.example/pypi/"}}
                """;
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON).content(mirror))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.formatSettings['pypi.filesUrl']").value("https://mirror.example/pypi/"));
    }

    @Test
    void connectSnippetsCoverEveryClient() throws Exception {
        hosted("pypi-hosted", "pypi");
        ArrayNode snippets = (ArrayNode) PypiFixtures.MAPPER.readTree(mvc.perform(get("/api/v1/repositories/pypi-hosted/connect")
                        .header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        List<String> ids = snippets.valueStream().map(s -> str(s, "id")).toList();
        assertThat(ids).contains("pip-conf", "pip-install", "uv-env", "uv-pyproject", "poetry", "pipenv", "pdm", "dockerfile",
                "twine-pypirc", "twine-upload", "uv-publish", "hatch-publish", "flit-publish", "poetry-publish");
        String all = String.join("\n", snippets.valueStream().map(s -> str(s, "code")).toList());
        assertThat(all).contains("http://localhost/repository/pypi-hosted/simple")
                .contains("twine upload -r granary dist/*")
                .contains("username = __token__");

        // read-only repositories carry no upload snippets
        proxy("pypi-proxy", "pypi", "https://pypi.org/");
        ArrayNode proxySnippets = (ArrayNode) PypiFixtures.MAPPER.readTree(mvc.perform(get("/api/v1/repositories/pypi-proxy/connect")
                        .header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(proxySnippets.valueStream().map(s -> str(s, "id"))).contains("pip-conf").doesNotContain("twine-upload");
    }
}
