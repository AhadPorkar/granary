package dev.granary.server.terraform;

import static dev.granary.server.terraform.TerraformFixtures.repositoryUrl;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.server.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Terraform discovers a registry host through {@code https://<host>/.well-known/terraform.json}; the
 * server routes that root path to the repository flagged {@code terraform.rootRepository}, else the first
 * Terraform group, else the first Terraform repository.
 */
class TerraformDiscoveryIntegrationTest extends IntegrationTest {

    /**
     * MockMvc records a servlet forward instead of executing it, so the root route is asserted through
     * the forwarded URL and the document itself through the repository path it forwards to.
     */
    @Test
    void rootDiscoveryPrefersTheFlaggedRepositoryThenAGroupThenAnything() throws Exception {
        mvc.perform(get("/.well-known/terraform.json").accept(MediaType.APPLICATION_JSON)).andExpect(status().isNotFound());

        hosted("terraform-hosted", "terraform");
        mvc.perform(get("/.well-known/terraform.json").accept(MediaType.APPLICATION_JSON))
                .andExpect(forwardedUrl("/repository/terraform-hosted/.well-known/terraform.json"));
        mvc.perform(get("/repository/terraform-hosted/.well-known/terraform.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$['providers.v1']").value(repositoryUrl("terraform-hosted") + "/v1/providers/"));

        proxy("terraform-registry", "terraform", "https://registry.terraform.io/");
        group("terraform-group", "terraform", "terraform-hosted", "terraform-registry");
        mvc.perform(get("/.well-known/terraform.json"))
                .andExpect(forwardedUrl("/repository/terraform-group/.well-known/terraform.json"));
        mvc.perform(get("/repository/terraform-group/.well-known/terraform.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['providers.v1']").value(repositoryUrl("terraform-group") + "/v1/providers/"))
                .andExpect(jsonPath("$['modules.v1']").value(repositoryUrl("terraform-group") + "/v1/modules/"));

        Repository flagged = new Repository();
        flagged.setName("terraform-root");
        flagged.setFormat("terraform");
        flagged.setType(RepositoryType.HOSTED);
        flagged.setFormatSettings(Map.of("terraform.rootRepository", "true"));
        repositories.create(flagged);
        mvc.perform(get("/.well-known/terraform.json"))
                .andExpect(forwardedUrl("/repository/terraform-root/.well-known/terraform.json"));
        mvc.perform(head("/.well-known/terraform.json"))
                .andExpect(forwardedUrl("/repository/terraform-root/.well-known/terraform.json"));
        mvc.perform(head("/repository/terraform-root/.well-known/terraform.json"))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(content().string(""));
        // the route only claims the exact path and only reads
        mvc.perform(post("/.well-known/terraform.json").header("Authorization", adminBasic()))
                .andExpect(forwardedUrl(null))
                .andExpect(status().isNotFound());
        mvc.perform(get("/.well-known/openid-configuration")).andExpect(forwardedUrl(null)).andExpect(status().isNotFound());
        mvc.perform(get("/.well-known/terraform.json/extra")).andExpect(forwardedUrl(null)).andExpect(status().isNotFound());
    }

    @Test
    void standardTemplatesCreateTheRegistryHostInOneCall() throws Exception {
        mvc.perform(post("/api/v1/repositories/standard").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates").value(org.hamcrest.Matchers.hasItems(
                        "terraform-registry", "opentofu-registry", "terraform-hosted", "terraform-group")));
        mvc.perform(get("/api/v1/repositories/terraform-group").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("GROUP"))
                .andExpect(jsonPath("$.members[0]").value("terraform-hosted"))
                .andExpect(jsonPath("$.members[1]").value("terraform-registry"))
                .andExpect(jsonPath("$.formatSettings['terraform.rootRepository']").value("true"));
        mvc.perform(get("/api/v1/repositories/opentofu-registry").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remoteUrl").value("https://registry.opentofu.org/"));
        mvc.perform(get("/.well-known/terraform.json"))
                .andExpect(forwardedUrl("/repository/terraform-group/.well-known/terraform.json"));
        mvc.perform(get("/repository/terraform-group/.well-known/terraform.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['providers.v1']").value(repositoryUrl("terraform-group") + "/v1/providers/"));
        mvc.perform(get("/api/v1/formats").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='terraform')].displayName").value("Terraform / OpenTofu"))
                .andExpect(jsonPath("$[?(@.id=='terraform')].settings[*].key").value(org.hamcrest.Matchers.hasItems(
                        "terraform.rootRepository", "terraform.providerProtocols")));
    }

    @Test
    void discoveryHonoursTheAnonymousReadSettingTheWayTheCliExpects() throws Exception {
        hosted("terraform-hosted", "terraform");
        settings.setAnonymousReadEnabled(false);
        // the root path is public, the forward lands on the repository where the privilege check happens
        mvc.perform(get("/.well-known/terraform.json").accept(MediaType.APPLICATION_JSON))
                .andExpect(forwardedUrl("/repository/terraform-hosted/.well-known/terraform.json"));
        // terraform sends the host's credentials with the discovery request, so a 401 challenge is what it needs
        mvc.perform(get("/repository/terraform-hosted/.well-known/terraform.json").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        String token = tokens.issue(users.require(ADMIN), "terraform", null).secret();
        mvc.perform(get("/repository/terraform-hosted/.well-known/terraform.json").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['providers.v1']").value(repositoryUrl("terraform-hosted") + "/v1/providers/"));
    }
}
