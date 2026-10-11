package dev.granary.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.FormatRegistry;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.UpstreamCredentials;
import dev.granary.server.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code upstream.credentialHosts} is a core setting: {@code GET /api/v1/formats} offers it for the proxy
 * repositories of every format that has them, and saving a repository refuses a value that is not a list of hosts.
 */
class UpstreamCredentialHostsApiIntegrationTest extends IntegrationTest {

    private static final String KEY = UpstreamCredentials.HOSTS_SETTING;

    @Autowired
    private FormatRegistry formats;

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String proxy(String name, String hosts) {
        return """
                {"name": "%s", "format": "helm", "type": "PROXY", "remoteUrl": "https://charts.example.com/",
                 "remoteUsername": "mirror", "remotePassword": "s3cret",
                 "formatSettings": {"%s": "%s"}}""".formatted(name, KEY, hosts);
    }

    @Test
    void everyFormatWithProxiesOffersTheSettingForProxiesOnly() throws Exception {
        String body = mvc.perform(get("/api/v1/formats").header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode formatsJson = JsonMapper.builder().build().readTree(body);

        List<String> problems = new ArrayList<>();
        int proxying = 0;
        for (JsonNode format : formatsJson) {
            String id = format.path("id").asString();
            FormatHandler handler = formats.find(id).orElseThrow();
            boolean hasProxies = handler.supportedTypes().contains(RepositoryType.PROXY);
            List<JsonNode> offered = new ArrayList<>();
            format.path("settings").forEach(setting -> {
                if (KEY.equals(setting.path("key").asString())) {
                    offered.add(setting);
                }
            });
            if (hasProxies) {
                proxying++;
                if (offered.size() != 1) {
                    problems.add(id + ": offers " + KEY + " " + offered.size() + " times");
                } else {
                    JsonNode setting = offered.getFirst();
                    if (!setting.path("label").asString().equals(UpstreamCredentials.HOSTS_DESCRIPTOR.label())) {
                        problems.add(id + ": unexpected label " + setting.path("label"));
                    }
                    List<String> types = new ArrayList<>();
                    setting.path("appliesTo").forEach(type -> types.add(type.asString()));
                    if (!types.equals(List.of("PROXY"))) {
                        problems.add(id + ": applies to " + types);
                    }
                }
            } else if (!offered.isEmpty()) {
                problems.add(id + ": has no proxies but offers " + KEY);
            }
        }
        assertThat(problems).isEmpty();
        assertThat(proxying).as("formats with proxy repositories").isGreaterThanOrEqualTo(20);
        assertThat(formats.find("vcpkg")).as("a hosted-only format to check the negative case against").isPresent();
    }

    @Test
    void aListOfHostsIsSavedAndReturned() throws Exception {
        mvc.perform(json(post("/api/v1/repositories"),
                        proxy("helm-mirror", "files.example.com, *.cdn.example.com:8443, [fd00::5], 10.0.0.5:8081")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.formatSettings['" + KEY + "']")
                        .value("files.example.com, *.cdn.example.com:8443, [fd00::5], 10.0.0.5:8081"));
        mvc.perform(get("/api/v1/repositories/helm-mirror").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.formatSettings['" + KEY + "']")
                        .value("files.example.com, *.cdn.example.com:8443, [fd00::5], 10.0.0.5:8081"));
    }

    @Test
    void valuesThatAreNotHostsAreRefusedWhenSaving() throws Exception {
        for (String invalid : List.of("https://files.example.com", "files.example.com/path", "user@files.example.com",
                "files.example.com:99999", "*.com", "files.*.example.com", "fd00::5", "exa mple.com")) {
            mvc.perform(json(post("/api/v1/repositories"), proxy("helm-invalid", invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("repository.upstream.credentialHosts.invalid"))
                    .andExpect(jsonPath("$.details.field").value(KEY));
        }
        mvc.perform(get("/api/v1/repositories/helm-invalid").header("Authorization", adminBearer()))
                .andExpect(status().isNotFound());

        mvc.perform(json(post("/api/v1/repositories"), proxy("helm-valid", "files.example.com")))
                .andExpect(status().isCreated());
        mvc.perform(json(put("/api/v1/repositories/helm-valid"), proxy("helm-valid", "ftp://files.example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.upstream.credentialHosts.invalid"));
        mvc.perform(get("/api/v1/repositories/helm-valid").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.formatSettings['" + KEY + "']").value("files.example.com"));
    }
}
