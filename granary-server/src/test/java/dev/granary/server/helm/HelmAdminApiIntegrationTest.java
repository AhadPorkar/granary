package dev.granary.server.helm;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.server.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** The Helm format as the admin UI sees it: the format list, standard repositories, settings and connect snippets. */
class HelmAdminApiIntegrationTest extends IntegrationTest {

    private String createBody(String type, String settingsJson) {
        String remote = "PROXY".equals(type) ? ",\"remoteUrl\":\"https://charts.example.com/stable\"" : "";
        return "{\"name\":\"helm-" + type.toLowerCase() + "-x\",\"format\":\"helm\",\"type\":\"" + type + "\"" + remote
                + ",\"formatSettings\":" + settingsJson + "}";
    }

    @Test
    void theFormatIsListedWithItsSettingsAndTemplates() throws Exception {
        mvc.perform(get("/api/v1/formats").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'helm')].displayName").value("Helm charts"))
                .andExpect(jsonPath("$[?(@.id == 'helm')].templates[*]").value(hasItem("helm-hosted")))
                .andExpect(jsonPath("$[?(@.id == 'helm')].templates[*]").value(hasItem("helm-group")))
                .andExpect(jsonPath("$[?(@.id == 'helm')].settings[*].key").value(hasItem("helm.relativeUrls")))
                .andExpect(jsonPath("$[?(@.id == 'helm')].settings[*].key").value(hasItem("helm.maxChartSizeMb")))
                .andExpect(jsonPath("$[?(@.id == 'helm')].types[*]").value(hasItem("PROXY")));
    }

    @Test
    void standardRepositoriesIncludeTheHelmHostedRepositoryAndItsGroup() throws Exception {
        mvc.perform(post("/api/v1/repositories/standard").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(hasItem("helm-hosted")))
                .andExpect(jsonPath("$.created").value(hasItem("helm-group")));
        mvc.perform(get("/api/v1/repositories/helm-group").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.type").value("GROUP"))
                .andExpect(jsonPath("$.format").value("helm"))
                .andExpect(jsonPath("$.members[0]").value("helm-hosted"));
        mvc.perform(get("/repository/helm-group/index.yaml")).andExpect(status().isOk());
    }

    @Test
    void connectSnippetsCoverTheClients() throws Exception {
        hosted("helm-hosted", "helm");
        group("helm-group", "helm", "helm-hosted");
        mvc.perform(get("/api/v1/repositories/helm-hosted/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem("helm-repo-add")))
                .andExpect(jsonPath("$[*].id").value(hasItem("helm-cm-push")))
                .andExpect(jsonPath("$[*].id").value(hasItem("helm-push-curl")))
                .andExpect(jsonPath("$[*].id").value(hasItem("flux-helmrepository")))
                .andExpect(jsonPath("$[*].id").value(hasItem("argocd-repository")))
                .andExpect(jsonPath("$[?(@.id == 'helm-repo-add')].code").value(hasItem(
                        containsString("helm repo add granary http://localhost/repository/helm-hosted"))));
        mvc.perform(get("/api/v1/repositories/helm-group/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem("helm-repo-add")))
                .andExpect(jsonPath("$[*].id").value(not(hasItem("helm-cm-push"))));
    }

    @Test
    void invalidSettingsAreRefusedWhenTheRepositoryIsSaved() throws Exception {
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("HOSTED", "{\"helm.maxChartSizeMb\":\"-1\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.helm.maxChartSizeMb.invalid"));
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("PROXY", "{\"helm.relativeUrls\":\"maybe\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.helm.relativeUrls.invalid"));
        mvc.perform(post("/api/v1/repositories").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("HOSTED", "{\"helm.maxChartSizeMb\":\"50\",\"helm.relativeUrls\":\"true\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.formatSettings['helm.maxChartSizeMb']").value("50"));
    }
}
