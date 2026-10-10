package dev.granary.server.swift;

import static dev.granary.server.swift.SwiftFixtures.archive;
import static dev.granary.server.swift.SwiftFixtures.getJson;
import static dev.granary.server.swift.SwiftFixtures.getZip;
import static dev.granary.server.swift.SwiftFixtures.publish;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.server.IntegrationTest;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How SwiftPM authenticates: {@code swift package-registry login} stores a token that is then sent as
 * {@code Authorization: Bearer} (or Basic for {@code --username/--password}); refusals of anonymous callers carry no
 * {@code WWW-Authenticate} challenge, because SwiftPM on Linux never completes a request answered with one.
 */
class SwiftAuthIntegrationTest extends IntegrationTest {

    private static final String REPO = "swift-hosted";
    private static final String URL = "/repository/" + REPO;

    @BeforeEach
    void createRepository() {
        hosted(REPO, "swift");
    }

    private String token(String username) {
        return tokens.issue(users.require(username), "swift test", Instant.now().plus(1, ChronoUnit.DAYS)).secret();
    }

    @Test
    void anonymousPublishIs401WithoutAChallenge() throws Exception {
        mvc.perform(publish(URL + "/acme/demo/1.0.0", archive("x"), null))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(header().string("Content-Version", "1"))
                .andExpect(jsonPath("$.detail").value("authentication required for write access to repository 'swift-hosted'; "
                        + "log in with swift package-registry login"));
        mvc.perform(delete(URI.create(URL + "/acme/demo/1.0.0"))).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isNotFound());
    }

    @Test
    void tokensFromLoginAuthorisePublishingAsBearerOrBasic() throws Exception {
        users.create("swift-dev", "devpassword", "Dev", null, Set.of("developer"));
        String token = token("swift-dev");
        // swift package-registry login <url> --token <token>: POST <url> with the token as Bearer
        mvc.perform(post(URI.create(URL)).header("Authorization", "Bearer " + token).header("Accept", "*/*"))
                .andExpect(status().isOk());
        mvc.perform(publish(URL + "/acme/demo/1.0.0", archive("bearer"), null).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
        // --username/--password: Basic, the token as password
        mvc.perform(post(URI.create(URL)).header("Authorization", basic("swift-dev", token))).andExpect(status().isOk());
        mvc.perform(publish(URL + "/acme/demo/1.1.0", archive("basic"), null).header("Authorization", basic("swift-dev", token)))
                .andExpect(status().isCreated());
        mvc.perform(publish(URL + "/acme/demo/1.2.0", archive("password"), null).header("Authorization", basic("swift-dev", "devpassword")))
                .andExpect(status().isCreated());
        mvc.perform(delete(URI.create(URL + "/acme/demo/1.2.0")).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void readersCanLogInAndReadButNotPublish() throws Exception {
        users.create("swift-reader", "readerpass", "Reader", null, Set.of("reader"));
        String token = token("swift-reader");
        mvc.perform(post(URI.create(URL)).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(publish(URL + "/acme/demo/1.0.0", archive("x"), null).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(publish(URL + "/acme/demo/1.0.0", archive("x"), null).header("Authorization", adminBasic()))
                .andExpect(status().isCreated());
        mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(delete(URI.create(URL + "/acme/demo/1.0.0")).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void readsNeedCredentialsWhenAnonymousAccessIsOff() throws Exception {
        mvc.perform(publish(URL + "/acme/demo/1.0.0", archive("x"), null).header("Authorization", adminBasic()))
                .andExpect(status().isCreated());
        settings.setAnonymousReadEnabled(false);
        mvc.perform(getJson(URL + "/acme/demo"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.detail").value("authentication required for read access to repository 'swift-hosted'; "
                        + "log in with swift package-registry login"));
        mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip")).andExpect(status().isUnauthorized());
        mvc.perform(post(URI.create(URL))).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(getJson(URL + "/acme/demo").header("Authorization", "Bearer " + token(ADMIN))).andExpect(status().isOk());
        mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip").header("Authorization", adminBasic())).andExpect(status().isOk());
    }

    @Test
    void anonymousReadDoesNotLetAnonymousCallersLogIn() throws Exception {
        // reading is allowed, but a login without credentials must fail so SwiftPM does not store useless ones
        mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isNotFound());
        mvc.perform(post(URI.create(URL + "/login"))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("log in with a username and password or an API token"));
    }

    @Test
    void badCredentialsAreRejectedBeforeTheHandlerRuns() throws Exception {
        // the server's credential check answers unknown tokens itself (with a Basic challenge)
        mvc.perform(post(URI.create(URL)).header("Authorization", "Bearer gry_definitely_not_a_valid_token_value"))
                .andExpect(status().isUnauthorized());
        mvc.perform(publish(URL + "/acme/demo/1.0.0", archive("x"), null).header("Authorization", basic("admin", "nope")))
                .andExpect(status().isUnauthorized());
        mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isNotFound());
    }
}
