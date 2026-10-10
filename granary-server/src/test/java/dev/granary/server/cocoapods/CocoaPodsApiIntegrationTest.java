package dev.granary.server.cocoapods;

import static dev.granary.server.cocoapods.CocoaPodsFixtures.json;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.str;
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

/** The management API as the web UI and the verify script use it: templates, the format setting and connect snippets. */
class CocoaPodsApiIntegrationTest extends IntegrationTest {

    @Test
    void standardRepositoriesComeFromTheTemplates() throws Exception {
        JsonNode created = json(mvc.perform(post("/api/v1/repositories/standard").header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(created.path("created").valueStream().map(JsonNode::asString))
                .contains("cocoapods-cdn", "cocoapods-hosted", "cocoapods-group");

        mvc.perform(get("/api/v1/repositories/cocoapods-cdn").header("Authorization", adminBearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PROXY"))
                .andExpect(jsonPath("$.format").value("cocoapods"))
                .andExpect(jsonPath("$.remoteUrl").value("https://cdn.cocoapods.org/"));
        mvc.perform(get("/api/v1/repositories/cocoapods-group").header("Authorization", adminBearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("GROUP"))
                .andExpect(jsonPath("$.members[0]").value("cocoapods-hosted"))
                .andExpect(jsonPath("$.members[1]").value("cocoapods-cdn"));
        mvc.perform(get("/api/v1/repositories/cocoapods-hosted").header("Authorization", adminBearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("HOSTED"));
        mvc.perform(get("/repository/cocoapods-group/")).andExpect(status().isOk());
    }

    @Test
    void theFormatAdvertisesItsSettingAndTemplates() throws Exception {
        JsonNode formats = json(mvc.perform(get("/api/v1/formats").header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn());
        JsonNode cocoapods = formats.valueStream().filter(f -> "cocoapods".equals(str(f, "id"))).findFirst().orElseThrow();
        assertThat(str(cocoapods, "displayName")).isEqualTo("CocoaPods");
        assertThat(cocoapods.path("types").valueStream().map(JsonNode::asString)).containsExactlyInAnyOrder("HOSTED", "PROXY", "GROUP");
        assertThat(cocoapods.path("templates").valueStream().map(JsonNode::asString))
                .containsExactly("cocoapods-cdn", "cocoapods-hosted", "cocoapods-group");
        JsonNode setting = cocoapods.path("settings").get(0);
        assertThat(str(setting, "key")).isEqualTo("cocoapods.rewriteHttpSources");
        assertThat(str(setting, "type")).isEqualTo("BOOLEAN");
        assertThat(str(setting, "defaultValue")).isEqualTo("true");
    }

    @Test
    void aNonBooleanSettingIsRefused() throws Exception {
        String body = """
                {"name":"cocoapods-bad","format":"cocoapods","type":"PROXY","remoteUrl":"https://cdn.cocoapods.org/",
                 "formatSettings":{"cocoapods.rewriteHttpSources":"sometimes"}}
                """;
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.cocoapods.setting.invalid"));
        assertThat(repositories.find("cocoapods-bad")).isEmpty();

        String direct = body.replace("cocoapods-bad", "cocoapods-direct").replace("sometimes", "false");
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON).content(direct))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.formatSettings['cocoapods.rewriteHttpSources']").value("false"));
    }

    @Test
    void connectSnippetsCoverPodAndPublishing() throws Exception {
        hosted("cocoapods-hosted", "cocoapods");
        JsonNode snippets = json(mvc.perform(get("/api/v1/repositories/cocoapods-hosted/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn());
        List<String> ids = snippets.valueStream().map(s -> str(s, "id")).toList();
        assertThat(ids).containsExactly("podfile", "pod-repo-add-cdn", "pod-install", "pod-search", "netrc", "pod-env",
                "pod-publish", "pod-publish-archive", "pod-delete", "pod-trunk");
        String all = String.join("\n", snippets.valueStream().map(s -> str(s, "code")).toList());
        assertThat(all).contains("source 'http://localhost/repository/cocoapods-hosted/'")
                .contains("pod repo add-cdn granary http://localhost/repository/cocoapods-hosted/")
                .contains("machine localhost")
                .contains("http://localhost/repository/cocoapods-hosted/Specs/MyPod/1.0.0/MyPod.podspec.json");

        group("cocoapods-group", "cocoapods", "cocoapods-hosted");
        JsonNode groupSnippets = json(mvc.perform(get("/api/v1/repositories/cocoapods-group/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn());
        assertThat(groupSnippets.valueStream().map(s -> str(s, "id"))).contains("podfile", "netrc").doesNotContain("pod-publish");
    }
}
