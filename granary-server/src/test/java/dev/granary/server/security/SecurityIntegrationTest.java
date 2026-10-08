package dev.granary.server.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.security.ApiTokenService;
import dev.granary.server.IntegrationTest;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Who may do what, through every credential shape package managers and browsers use. */
class SecurityIntegrationTest extends IntegrationTest {

    static final String JAR = "com/acme/demo/1.0/demo-1.0.jar";

    @AfterEach
    void cleanUsers() {
        users.list().stream().filter(u -> !u.isBuiltin()).forEach(u -> users.delete(u.getUsername()));
    }

    @Test
    void anonymousReadFollowsTheGlobalSetting() throws Exception {
        hosted("maven-releases", "maven2");
        mvc.perform(put("/repository/maven-releases/" + JAR).header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isCreated());

        mvc.perform(get("/repository/maven-releases/" + JAR)).andExpect(status().isOk());

        settings.setAnonymousReadEnabled(false);
        mvc.perform(get("/repository/maven-releases/" + JAR))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic realm=\"Granary\"")))
                .andExpect(jsonPath("$.code").value("auth.required"));
        mvc.perform(get("/repository/maven-releases/" + JAR).header("Authorization", adminBasic())).andExpect(status().isOk());
    }

    @Test
    void writesAlwaysNeedCredentialsAndTheRightPrivilege() throws Exception {
        hosted("maven-releases", "maven2");
        users.create("reader", "password1", null, null, Set.of("reader"));
        users.create("dev", "password1", null, null, Set.of("developer"));

        mvc.perform(put("/repository/maven-releases/" + JAR).content("x"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(put("/repository/maven-releases/" + JAR).header("Authorization", basic("reader", "password1")).content("x"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
        mvc.perform(put("/repository/maven-releases/" + JAR).header("Authorization", basic("dev", "password1")).content("x"))
                .andExpect(status().isCreated());
    }

    @Test
    void perRepositoryPrivilegesAreScoped() throws Exception {
        hosted("a", "maven2");
        hosted("b", "maven2");
        settings.setAnonymousReadEnabled(false);
        var roles = mvc.perform(post("/api/v1/roles").header("Authorization", adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a-writers\",\"privileges\":[\"repository:write:a\"]}"))
                .andExpect(status().isCreated());
        users.create("scoped", "password1", null, null, Set.of("a-writers"));

        mvc.perform(put("/repository/a/" + JAR).header("Authorization", basic("scoped", "password1")).content("x"))
                .andExpect(status().isCreated());
        mvc.perform(get("/repository/a/" + JAR).header("Authorization", basic("scoped", "password1")))
                .andExpect(status().isOk());
        mvc.perform(put("/repository/b/" + JAR).header("Authorization", basic("scoped", "password1")).content("x"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/repository/b/" + JAR).header("Authorization", basic("scoped", "password1")))
                .andExpect(status().isForbidden());
        // the repository list only shows what the user may read
        mvc.perform(get("/api/v1/repositories").header("Authorization", bearer("scoped")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("a"));
        mvc.perform(get("/api/v1/repositories/b").header("Authorization", bearer("scoped"))).andExpect(status().isForbidden());
    }

    @Test
    void apiTokensWorkAsBearerAsBasicPasswordAndAsNuGetApiKey() throws Exception {
        hosted("maven-releases", "maven2");
        ApiTokenService.IssuedToken token = tokens.issue(users.require("admin"), "ci", null);

        mvc.perform(put("/repository/maven-releases/com/acme/x/1/x-1.jar").header("Authorization", "Bearer " + token.secret()).content("x"))
                .andExpect(status().isCreated());
        mvc.perform(put("/repository/maven-releases/com/acme/x/2/x-2.jar").header("Authorization", basic("whatever", token.secret())).content("x"))
                .andExpect(status().isCreated());
        mvc.perform(put("/repository/maven-releases/com/acme/x/3/x-3.jar").header("X-NuGet-ApiKey", token.secret()).content("x"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token.secret()))
                .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void badCredentialsAreRejectedImmediately() throws Exception {
        hosted("maven-releases", "maven2");
        mvc.perform(get("/repository/maven-releases/" + JAR).header("Authorization", basic("admin", "wrong")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid username or password"));
        mvc.perform(get("/repository/maven-releases/" + JAR).header("Authorization", "Bearer gry_" + "x".repeat(40)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid or expired API token"));
        mvc.perform(get("/repository/maven-releases/" + JAR).header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid or expired session"));
        mvc.perform(get("/repository/maven-releases/" + JAR).header("Authorization", "Basic %%%"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/repository/maven-releases/" + JAR).header("Authorization", "Digest abc"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/repository/maven-releases/" + JAR).header("X-NuGet-ApiKey", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginIssuesASessionTokenAndApiNeedsIt() throws Exception {
        mvc.perform(get("/api/v1/repositories")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/system/info")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Granary"))
                .andExpect(jsonPath("$.formats").isArray());

        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("admin"))
                .andExpect(jsonPath("$.user.roles[0]").value("admin"))
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.privileges[0]").value("system:admin"));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.invalid"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation"));
    }

    @Test
    void apiTokenCannotBeUsedToLogInToTheWebUi() throws Exception {
        ApiTokenService.IssuedToken token = tokens.issue(users.require("admin"), "ci", null);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + token.secret() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointsRequirePrivileges() throws Exception {
        users.create("reader", "password1", null, null, Set.of("reader"));
        mvc.perform(get("/api/v1/users").header("Authorization", bearer("reader"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/system/status").header("Authorization", bearer("reader"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/repositories").header("Authorization", bearer("reader"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"format\":\"maven2\",\"type\":\"HOSTED\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("auth.forbidden"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").header("Authorization", adminBearer())).andExpect(status().isOk());
    }

    @Test
    void passwordChangeNeedsTheCurrentPassword() throws Exception {
        users.create("pw", "password1", null, null, Set.of("reader"));
        mvc.perform(post("/api/v1/auth/password").header("Authorization", bearer("pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"password2\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("auth.password.wrong"));
        mvc.perform(post("/api/v1/auth/password").header("Authorization", bearer("pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password1\",\"newPassword\":\"password2\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", basic("pw", "password2"))).andExpect(status().isOk());
    }
}
