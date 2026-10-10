package dev.granary.server.composer;

import static dev.granary.server.composer.ComposerFixtures.AUDIT_BODY;
import static dev.granary.server.composer.ComposerFixtures.NOTIFY_BODY;
import static dev.granary.server.composer.ComposerFixtures.archive;
import static dev.granary.server.composer.ComposerFixtures.composerJson;
import static dev.granary.server.composer.ComposerFixtures.sha1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.User;
import dev.granary.core.security.ApiTokenService;
import dev.granary.server.IntegrationTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/**
 * How Composer and curl authenticate: {@code http-basic} in auth.json (username and password or API token),
 * {@code bearer} in auth.json, and {@code curl -u} for uploads.
 */
class ComposerAuthIntegrationTest extends IntegrationTest {

    private static final String URL = "/repository/composer-hosted";
    /** Users outlive repositories in the shared test context, so every user a test creates is removed again. */
    private static final List<String> TEST_USERS = List.of("composer-reader", "composer-dev");

    private final byte[] lib = archive(composerJson("acme/lib", null, null));
    private final String distPath = URL + "/dist/acme/lib/1.0.0/" + sha1(lib) + ".zip";

    @BeforeEach
    void createRepository() {
        hosted("composer-hosted", "composer");
    }

    @AfterEach
    void removeTestUsers() {
        for (String username : TEST_USERS) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }

    @Test
    void anonymousWritesAre401WithABasicChallenge() throws Exception {
        mvc.perform(put(URL + "/packages/acme/lib/1.0.0").content(lib))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Basic")));
        mvc.perform(multipart(URL + "/packages").file(new MockMultipartFile("package", "lib.zip", "application/zip", lib)).param("version", "1.0.0"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(URL + "/packages/acme/lib/1.0.0")).andExpect(status().isUnauthorized());
        mvc.perform(delete(URL + "/packages/acme/lib")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/p2/acme/lib.json")).andExpect(status().isNotFound());
    }

    @Test
    void readersCannotPublishButDevelopersCan() throws Exception {
        users.create("composer-reader", "readerpass", "Reader", null, Set.of("reader"));
        users.create("composer-dev", "developerpass", "Developer", null, Set.of("developer"));
        mvc.perform(put(URL + "/packages/acme/lib/1.0.0").content(lib).header("Authorization", basic("composer-reader", "readerpass")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(put(URL + "/packages/acme/lib/1.0.0").content(lib).header("Authorization", basic("composer-dev", "developerpass")))
                .andExpect(status().isCreated());
        mvc.perform(get(URL + "/p2/acme/lib.json").header("Authorization", basic("composer-reader", "readerpass"))).andExpect(status().isOk());
        mvc.perform(get(distPath).header("Authorization", basic("composer-reader", "readerpass"))).andExpect(status().isOk());
        mvc.perform(delete(URL + "/packages/acme/lib/1.0.0").header("Authorization", basic("composer-reader", "readerpass")))
                .andExpect(status().isForbidden());
        mvc.perform(delete(URL + "/packages/acme/lib/1.0.0").header("Authorization", basic("composer-dev", "developerpass")))
                .andExpect(status().isNoContent());
    }

    @Test
    void apiTokensWorkAsHttpBasicPasswordAndAsBearerToken() throws Exception {
        User admin = users.require(ADMIN);
        ApiTokenService.IssuedToken issued = tokens.issue(admin, "composer", null);
        assertThat(issued.secret()).startsWith("gry_");
        // composer config http-basic.<host> admin <token>  (and curl -u admin:<token>)
        mvc.perform(put(URL + "/packages/acme/lib/1.0.0").content(lib).header("Authorization", basic(ADMIN, issued.secret())))
                .andExpect(status().isCreated());
        // composer config bearer.<host> <token>
        mvc.perform(put(URL + "/packages/acme/lib/1.1.0").content(archive(composerJson("acme/lib", null, null).replace("Demo", "Bearer")))
                        .header("Authorization", "Bearer " + issued.secret()))
                .andExpect(status().isCreated());
        mvc.perform(get(URL + "/p2/acme/lib.json").header("Authorization", "Bearer " + issued.secret())).andExpect(status().isOk());

        mvc.perform(put(URL + "/packages/acme/lib/1.2.0").content(lib).header("Authorization", basic(ADMIN, "gry_not_a_real_token")))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(URL + "/packages/acme/lib/1.2.0").content(lib).header("Authorization", "Bearer gry_not_a_real_token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(URL + "/packages/acme/lib/1.2.0").content(lib).header("Authorization", basic(ADMIN, "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readsNeedCredentialsWhenAnonymousAccessIsOff() throws Exception {
        mvc.perform(put(URL + "/packages/acme/lib/1.0.0").content(lib).header("Authorization", adminBasic())).andExpect(status().isCreated());
        settings.setAnonymousReadEnabled(false);

        // Composer asks for credentials when it sees the 401 and then retries with them
        for (String path : List.of("/packages.json", "/p2/acme/lib.json", "/p2/acme/lib~dev.json", "/search.json?q=acme", "/list.json")) {
            mvc.perform(get(URL + path)).andExpect(status().isUnauthorized()).andExpect(header().exists("WWW-Authenticate"));
        }
        mvc.perform(get(distPath)).andExpect(status().isUnauthorized());
        mvc.perform(post(URL + "/downloads").contentType(MediaType.APPLICATION_JSON).content(NOTIFY_BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post(URL + "/api/security-advisories").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(AUDIT_BODY))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(URL + "/packages.json").header("Authorization", adminBasic())).andExpect(status().isOk());
        mvc.perform(get(URL + "/p2/acme/lib.json").header("Authorization", adminBasic())).andExpect(status().isOk());
        mvc.perform(get(distPath).header("Authorization", adminBasic())).andExpect(status().isOk());
        mvc.perform(post(URL + "/downloads").contentType(MediaType.APPLICATION_JSON).content(NOTIFY_BODY).header("Authorization", adminBasic()))
                .andExpect(status().isCreated());
    }

    @Test
    void notificationsSearchAndAuditsAreReadsEvenWhenPosted() throws Exception {
        users.create("composer-reader", "readerpass", "Reader", null, Set.of("reader"));
        // anonymous read is on: an anonymous install may notify and audit
        mvc.perform(post(URL + "/downloads").contentType(MediaType.APPLICATION_JSON).content(NOTIFY_BODY)).andExpect(status().isCreated());
        mvc.perform(post(URL + "/api/security-advisories").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(AUDIT_BODY))
                .andExpect(status().isOk());
        // and so may a user without write access
        mvc.perform(post(URL + "/downloads").contentType(MediaType.APPLICATION_JSON).content(NOTIFY_BODY)
                        .header("Authorization", basic("composer-reader", "readerpass")))
                .andExpect(status().isCreated());
        mvc.perform(post(URL + "/api/security-advisories").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(AUDIT_BODY)
                        .header("Authorization", basic("composer-reader", "readerpass")))
                .andExpect(status().isOk());
    }
}
