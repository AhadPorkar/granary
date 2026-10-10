package dev.granary.server.swift;

import static dev.granary.server.swift.SwiftFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.server.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** The management API as the web UI and the verify script use it: templates, format settings and connect snippets. */
class SwiftApiIntegrationTest extends IntegrationTest {

    private JsonNode getJson(String url) throws Exception {
        return MAPPER.readTree(mvc.perform(get(url).header("Authorization", adminBearer())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private static String str(JsonNode node, String field) {
        return node.path(field).asString();
    }

    @Test
    void standardRepositoriesComeFromTheTemplates() throws Exception {
        JsonNode created = MAPPER.readTree(mvc.perform(post("/api/v1/repositories/standard").header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(created.path("created").valueStream().map(JsonNode::asString)).contains("swift-hosted", "swift-group");

        mvc.perform(get("/api/v1/repositories/swift-hosted").header("Authorization", adminBearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("HOSTED"))
                .andExpect(jsonPath("$.format").value("swift"))
                .andExpect(jsonPath("$.allowRedeploy").value(false));
        mvc.perform(get("/api/v1/repositories/swift-group").header("Authorization", adminBearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("GROUP"))
                .andExpect(jsonPath("$.members[0]").value("swift-hosted"));
        mvc.perform(get("/repository/swift-group/")).andExpect(status().isOk()).andExpect(jsonPath("$.type").value("group"));
    }

    @Test
    void theFormatAdvertisesItsTypesSettingsAndTemplates() throws Exception {
        JsonNode swift = getJson("/api/v1/formats").valueStream().filter(f -> "swift".equals(str(f, "id"))).findFirst().orElseThrow();
        assertThat(str(swift, "displayName")).isEqualTo("Swift package registry");
        assertThat(swift.path("types").valueStream().map(JsonNode::asString)).containsExactlyInAnyOrder("HOSTED", "PROXY", "GROUP");
        assertThat(swift.path("templates").valueStream().map(JsonNode::asString)).containsExactly("swift-hosted", "swift-group");
        assertThat(swift.path("settings").valueStream().map(s -> str(s, "key")))
                .containsExactly("swift.requireSignature", "swift.maxArchiveSizeMb");
    }

    @Test
    void invalidSettingsAreRefusedWhenTheRepositoryIsCreated() throws Exception {
        for (Map.Entry<String, String> bad : Map.of("swift.requireSignature", "maybe", "swift.maxArchiveSizeMb", "-1").entrySet()) {
            String body = """
                    {"name":"swift-bad","format":"swift","type":"HOSTED","formatSettings":{"%s":"%s"}}
                    """.formatted(bad.getKey(), bad.getValue());
            mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("repository." + bad.getKey() + ".invalid"));
        }
        assertThat(repositories.find("swift-bad")).isEmpty();

        String good = """
                {"name":"swift-signed","format":"swift","type":"HOSTED",
                 "formatSettings":{"swift.requireSignature":"true","swift.maxArchiveSizeMb":"50"}}
                """;
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.formatSettings['swift.requireSignature']").value("true"));
    }

    @Test
    void connectSnippetsCoverSwiftPm() throws Exception {
        hosted("swift-hosted", "swift");
        JsonNode snippets = getJson("/api/v1/repositories/swift-hosted/connect");
        List<String> ids = snippets.valueStream().map(s -> str(s, "id")).toList();
        assertThat(ids).contains("swift-registry-set", "swift-registry-set-scope", "swift-registry-login", "swift-registry-login-basic",
                "registries-json", "netrc", "package-swift", "swift-package-resolve", "swift-replace-scm", "package-metadata-json",
                "swift-registry-publish", "curl-publish");
        Map<String, String> code = snippets.valueStream().collect(Collectors.toMap(s -> str(s, "id"), s -> str(s, "code")));
        assertThat(code.get("swift-registry-set")).contains("swift package-registry set http://localhost/repository/swift-hosted --global --allow-insecure-http");
        assertThat(code.get("swift-registry-login")).contains("swift package-registry login http://localhost/repository/swift-hosted --token <api-token>")
                .contains("HTTPS only");
        assertThat(code.get("swift-registry-publish")).contains("swift package-registry publish acme.demo 1.0.0 --url http://localhost/repository/swift-hosted");
        assertThat(code.get("package-swift")).contains(".package(id: \"acme.demo\", from: \"1.0.0\")");
        assertThat(code.get("registries-json")).contains("\"url\": \"http://localhost/repository/swift-hosted\"").contains("\"localhost\"");
        assertThat(code.get("swift-replace-scm")).contains("--replace-scm-with-registry");

        group("swift-group", "swift", "swift-hosted");
        JsonNode groupSnippets = getJson("/api/v1/repositories/swift-group/connect");
        String publish = groupSnippets.valueStream().filter(s -> "swift-registry-publish".equals(str(s, "id"))).findFirst()
                .map(s -> str(s, "code")).orElseThrow();
        assertThat(publish).contains("read-only").contains("<hosted repository URL>");
    }
}
