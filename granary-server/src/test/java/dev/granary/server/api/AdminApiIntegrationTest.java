package dev.granary.server.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.server.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The management API: repositories, users, roles, tokens, settings, search. */
class AdminApiIntegrationTest extends IntegrationTest {

    @AfterEach
    void cleanUsers() {
        users.list().stream().filter(u -> !u.isBuiltin()).forEach(u -> users.delete(u.getUsername()));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void repositoryCrudWithValidation() throws Exception {
        mvc.perform(get("/api/v1/formats").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$[?(@.id=='maven2')].displayName").value("Maven"));

        mvc.perform(json(post("/api/v1/repositories"), "{\"name\":\"maven-central\",\"format\":\"maven2\",\"type\":\"PROXY\",\"remoteUrl\":\"https://repo1.maven.org/maven2/\",\"remoteUsername\":\"u\",\"remotePassword\":\"p\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("http://localhost/repository/maven-central/"))
                .andExpect(jsonPath("$.hasRemotePassword").value(true))
                .andExpect(jsonPath("$.remotePassword").doesNotExist())
                .andExpect(jsonPath("$.upstream.reachable").value(true));
        mvc.perform(json(post("/api/v1/repositories"), "{\"name\":\"maven-central\",\"format\":\"maven2\",\"type\":\"HOSTED\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("repository.name.taken"));
        mvc.perform(json(post("/api/v1/repositories"), "{\"name\":\"bad name\",\"format\":\"maven2\",\"type\":\"HOSTED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("repository.name.invalid"));
        mvc.perform(json(post("/api/v1/repositories"), "{\"name\":\"x\",\"format\":\"cobol\",\"type\":\"HOSTED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("repository.format.unknown"));
        mvc.perform(json(post("/api/v1/repositories"), "{\"name\":\"x\",\"format\":\"maven2\",\"type\":\"PROXY\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("repository.remoteUrl.missing"));
        mvc.perform(json(post("/api/v1/repositories"), "{\"name\":\"x\",\"format\":\"maven2\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation"))
                .andExpect(jsonPath("$.details.type").exists());
        mvc.perform(json(post("/api/v1/repositories"), "{\"name\":\"g\",\"format\":\"maven2\",\"type\":\"GROUP\",\"members\":[\"ghost\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("repository.members.unknown"))
                .andExpect(jsonPath("$.details.member").value("ghost"));

        mvc.perform(json(post("/api/v1/repositories"), "{\"name\":\"maven-releases\",\"format\":\"maven2\",\"type\":\"HOSTED\",\"versionPolicy\":\"RELEASE\"}"))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/repositories"), "{\"name\":\"maven-public\",\"format\":\"maven2\",\"type\":\"GROUP\",\"members\":[\"maven-releases\",\"maven-central\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.members[1]").value("maven-central"));
        mvc.perform(get("/api/v1/repositories").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("maven-central"));

        // update keeps the password when the field is left blank
        mvc.perform(json(put("/api/v1/repositories/maven-central"), "{\"name\":\"maven-central\",\"format\":\"maven2\",\"type\":\"PROXY\",\"remoteUrl\":\"https://repo1.maven.org/maven2/\",\"remoteUsername\":\"u\",\"remotePassword\":\"\",\"description\":\"Central\",\"metadataMaxAgeMinutes\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasRemotePassword").value(true))
                .andExpect(jsonPath("$.description").value("Central"))
                .andExpect(jsonPath("$.metadataMaxAgeMinutes").value(5));
        mvc.perform(json(put("/api/v1/repositories/maven-central"), "{\"name\":\"maven-central\",\"format\":\"npm\",\"type\":\"PROXY\",\"remoteUrl\":\"https://x/\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("repository.format.immutable"));

        mvc.perform(delete("/api/v1/repositories/maven-releases").header("Authorization", adminBearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("repository.inUse"));
        mvc.perform(delete("/api/v1/repositories/maven-public").header("Authorization", adminBearer())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/repositories/maven-releases").header("Authorization", adminBearer())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/repositories/maven-releases").header("Authorization", adminBearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/repositories/maven-releases").header("Authorization", adminBearer())).andExpect(status().isNotFound());
    }

    @Test
    void usersRolesAndTokens() throws Exception {
        mvc.perform(json(post("/api/v1/roles"), "{\"name\":\"npm-publishers\",\"description\":\"d\",\"privileges\":[\"repository:write:npm-hosted\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.privileges[0]").value("repository:write:npm-hosted"));
        mvc.perform(json(post("/api/v1/roles"), "{\"name\":\"bad\",\"privileges\":[\"nope\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("role.privilege.invalid"));
        mvc.perform(get("/api/v1/privileges").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$[0]").value("system:admin"));

        mvc.perform(json(post("/api/v1/users"), "{\"username\":\"jane\",\"password\":\"password1\",\"displayName\":\"Jane\",\"roles\":[\"npm-publishers\",\"reader\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles.length()").value(2))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(json(post("/api/v1/users"), "{\"username\":\"jane\",\"password\":\"password1\"}"))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/v1/users"), "{\"username\":\"joe\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("user.password.tooShort"));
        mvc.perform(json(put("/api/v1/users/jane"), "{\"displayName\":\"Jane D\",\"enabled\":false,\"roles\":[\"reader\"]}"))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.roles[0]").value("reader"));
        mvc.perform(json(post("/api/v1/users/jane/password"), "{\"password\":\"password2\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/users").header("Authorization", adminBearer())).andExpect(jsonPath("$.length()").value(2));

        // admins cannot lock themselves out
        mvc.perform(json(put("/api/v1/users/admin"), "{\"enabled\":false}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("user.self.disable"));
        mvc.perform(delete("/api/v1/users/admin").header("Authorization", adminBearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("user.self.delete"));

        // tokens belong to the caller
        String created = mvc.perform(json(post("/api/v1/tokens"), "{\"name\":\"ci\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").value(org.hamcrest.Matchers.startsWith("gry_")))
                .andExpect(jsonPath("$.token.name").value("ci"))
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/tokens").header("Authorization", adminBearer())).andExpect(jsonPath("$[0].name").value("ci"))
                .andExpect(jsonPath("$[0].secret").doesNotExist());
        mvc.perform(get("/api/v1/tokens").header("Authorization", bearer("jane"))).andExpect(status().isUnauthorized()); // disabled user
        mvc.perform(delete("/api/v1/tokens/" + id).header("Authorization", adminBearer())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/tokens").header("Authorization", adminBearer())).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(delete("/api/v1/users/jane").header("Authorization", adminBearer())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/roles/npm-publishers").header("Authorization", adminBearer())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/roles/admin").header("Authorization", adminBearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("role.builtin"));
    }

    @Test
    void settingsStatusAndSearch() throws Exception {
        mvc.perform(get("/api/v1/system/status").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.defaultAdminPassword").value(true))
                .andExpect(jsonPath("$.blobStores[0].name").value("default"))
                .andExpect(jsonPath("$.jvm.version").isNotEmpty());
        mvc.perform(json(put("/api/v1/system/settings"), "{\"anonymousRead\":false,\"offlineMode\":true,\"baseUrl\":\"https://granary.example.org/\"}"))
                .andExpect(jsonPath("$.anonymousRead").value(false))
                .andExpect(jsonPath("$.offlineMode").value(true))
                .andExpect(jsonPath("$.baseUrl").value("https://granary.example.org"));
        mvc.perform(get("/api/v1/system/info")).andExpect(jsonPath("$.anonymousRead").value(false));

        hosted("maven-releases", "maven2");
        mvc.perform(get("/api/v1/repositories/maven-releases").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.url").value("https://granary.example.org/repository/maven-releases/"));
        mvc.perform(put("/repository/maven-releases/com/acme/demo/1.0/demo-1.0.jar").header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/search?q=dem").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].repository").value("maven-releases"));
        mvc.perform(get("/api/v1/search?q=zzz").header("Authorization", adminBearer())).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/repositories/maven-releases/assets?prefix=com/").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.items[0].path").value("com/acme/demo/1.0/demo-1.0.jar"))
                .andExpect(jsonPath("$.items[0].sha256").isNotEmpty());
    }

    @Test
    void malformedJsonAndUnknownRoutes() throws Exception {
        mvc.perform(json(post("/api/v1/repositories"), "{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.malformed"));
        mvc.perform(get("/api/v1/nope").header("Authorization", adminBearer())).andExpect(status().isNotFound());
    }
}
