package dev.granary.server.pub;

import static dev.granary.server.pub.PubFixtures.str;
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
class PubApiIntegrationTest extends IntegrationTest {

    private JsonNode getJson(String url) throws Exception {
        return PubFixtures.MAPPER.readTree(mvc.perform(get(url).header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    @Test
    void standardRepositoriesComeFromTheTemplates() throws Exception {
        JsonNode created = PubFixtures.MAPPER.readTree(mvc.perform(post("/api/v1/repositories/standard")
                        .header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(created.path("created").valueStream().map(JsonNode::asString)).contains("pub-dev", "pub-hosted", "pub-group");

        mvc.perform(get("/api/v1/repositories/pub-dev").header("Authorization", adminBearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PROXY"))
                .andExpect(jsonPath("$.format").value("pub"))
                .andExpect(jsonPath("$.remoteUrl").value("https://pub.dev/"));
        mvc.perform(get("/api/v1/repositories/pub-hosted").header("Authorization", adminBearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("HOSTED"))
                .andExpect(jsonPath("$.allowRedeploy").value(false));
        mvc.perform(get("/api/v1/repositories/pub-group").header("Authorization", adminBearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("GROUP"))
                .andExpect(jsonPath("$.members[0]").value("pub-hosted"))
                .andExpect(jsonPath("$.members[1]").value("pub-dev"));
        mvc.perform(get("/repository/pub-group/")).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage").value("export PUB_HOSTED_URL=http://localhost/repository/pub-group"));
    }

    @Test
    void theFormatAdvertisesItsSettingsAndTemplates() throws Exception {
        JsonNode pub = getJson("/api/v1/formats").valueStream().filter(f -> "pub".equals(str(f, "id"))).findFirst().orElseThrow();
        assertThat(str(pub, "displayName")).isEqualTo("pub (Dart / Flutter)");
        assertThat(pub.path("types").valueStream().map(JsonNode::asString)).containsExactlyInAnyOrder("HOSTED", "PROXY", "GROUP");
        assertThat(pub.path("templates").valueStream().map(JsonNode::asString)).containsExactly("pub-dev", "pub-hosted", "pub-group");
        assertThat(pub.path("settings").valueStream().map(s -> str(s, "key"))).containsExactly("pub.maxArchiveSizeMb", "pub.advisories");
        assertThat(str(pub.path("settings").get(0), "defaultValue")).isEqualTo("100");
        assertThat(str(pub.path("settings").get(1), "defaultValue")).isEqualTo("true");
    }

    @Test
    void invalidSettingsAreRefusedWhenTheRepositoryIsCreated() throws Exception {
        String bad = """
                {"name":"pub-bad","format":"pub","type":"HOSTED","formatSettings":{"pub.maxArchiveSizeMb":"lots"}}
                """;
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.pub.maxArchiveSizeMb.invalid"));
        String badFlag = """
                {"name":"pub-bad","format":"pub","type":"PROXY","remoteUrl":"https://pub.dev/","formatSettings":{"pub.advisories":"perhaps"}}
                """;
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON).content(badFlag))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.pub.advisories.invalid"));
        assertThat(repositories.find("pub-bad")).isEmpty();

        String good = """
                {"name":"pub-big","format":"pub","type":"HOSTED","formatSettings":{"pub.maxArchiveSizeMb":"500"}}
                """;
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.formatSettings['pub.maxArchiveSizeMb']").value("500"));
    }

    @Test
    void connectSnippetsCoverDartPubAndFlutterPub() throws Exception {
        hosted("pub-hosted", "pub");
        JsonNode snippets = getJson("/api/v1/repositories/pub-hosted/connect");
        List<String> ids = snippets.valueStream().map(s -> str(s, "id")).toList();
        assertThat(ids).containsExactly("pub-hosted-url", "pub-token", "pubspec-hosted", "pubspec-publish-to", "dart-pub-publish",
                "flutter-pub", "pub-ci", "dockerfile");
        String all = String.join("\n", snippets.valueStream().map(s -> str(s, "code")).toList());
        assertThat(all).contains("export PUB_HOSTED_URL=http://localhost/repository/pub-hosted")
                .contains("dart pub token add http://localhost/repository/pub-hosted")
                .contains("hosted: http://localhost/repository/pub-hosted")
                .contains("publish_to: http://localhost/repository/pub-hosted")
                .contains("dart pub publish")
                .contains("flutter pub get")
                .contains("FLUTTER_STORAGE_BASE_URL")
                .contains("--env-var GRANARY_PUB_TOKEN")
                .contains("<api-token>");
        JsonNode publishTo = snippets.valueStream().filter(s -> "pubspec-publish-to".equals(str(s, "id"))).findFirst().orElseThrow();
        assertThat(str(publishTo, "filename")).isEqualTo("pubspec.yaml");
        assertThat(str(publishTo, "language")).isEqualTo("yaml");

        // read-only repositories carry no publishing snippets
        group("pub-group", "pub", "pub-hosted");
        List<String> groupIds = getJson("/api/v1/repositories/pub-group/connect").valueStream().map(s -> str(s, "id")).toList();
        assertThat(groupIds).contains("pub-hosted-url", "pub-token", "pubspec-hosted", "flutter-pub", "pub-ci", "dockerfile")
                .doesNotContain("pubspec-publish-to", "dart-pub-publish");
    }

    @Test
    void connectSnippetsWarnAboutPlainHttpOnOtherHosts() throws Exception {
        hosted("pub-hosted", "pub");
        settings.setBaseUrl("http://granary.internal:8081");
        String all = String.join("\n", getJson("/api/v1/repositories/pub-hosted/connect").valueStream().map(s -> str(s, "code")).toList());
        assertThat(all).contains("dart pub only stores tokens for https:// URLs");
    }
}
