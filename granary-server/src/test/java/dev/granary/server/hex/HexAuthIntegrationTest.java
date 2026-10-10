package dev.granary.server.hex;

import static dev.granary.server.hex.HexFixtures.ERLANG;
import static dev.granary.server.hex.HexFixtures.release;
import static dev.granary.server.hex.HexFixtures.term;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.ApiToken;
import dev.granary.format.hex.Etf;
import dev.granary.server.IntegrationTest;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How mix authenticates: the API key travels as the bare value of {@code Authorization} (no scheme), for the
 * repository ({@code --auth-key}) and the API ({@code HEX_API_KEY}) alike; {@code mix hex.user whoami} asks
 * {@code api/users/me} and hex_core checks permissions with {@code api/auth}.
 */
class HexAuthIntegrationTest extends IntegrationTest {

    private static final String URL = "/repository/hex-hosted";

    @BeforeEach
    void setUp() {
        hosted("hex-hosted", "hex");
    }

    private String keyFor(String username) {
        return tokens.issue(users.require(username), "mix", null).secret();
    }

    private void publishAs(String authorization, int expected) throws Exception {
        var request = post(URL + "/api/packages/acme_lib/releases").queryParam("replace", "false")
                .header("Accept", ERLANG).contentType("application/octet-stream").content(release("acme_lib", "0.1.0"));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        mvc.perform(request).andExpect(status().is(expected));
    }

    @Test
    void anonymousReadsWorkButPublishingNeedsAKey() throws Exception {
        publishAs(null, 401);
        mvc.perform(get(URL + "/names")).andExpect(status().isOk());
        publishAs(keyFor(ADMIN), 201);
        mvc.perform(get(URL + "/packages/acme_lib")).andExpect(status().isOk());
        mvc.perform(get(URL + "/tarballs/acme_lib-0.1.0.tar")).andExpect(status().isOk());
    }

    @Test
    void withAnonymousReadOffTheRepositoryNeedsTheAuthKey() throws Exception {
        String key = keyFor(ADMIN);
        publishAs(key, 201);
        settings.setAnonymousReadEnabled(false);
        mvc.perform(get(URL + "/packages/acme_lib")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")));
        mvc.perform(get(URL + "/public_key")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/packages/acme_lib").header("Authorization", key)).andExpect(status().isOk());
        mvc.perform(get(URL + "/tarballs/acme_lib-0.1.0.tar").header("Authorization", key)).andExpect(status().isOk());
        mvc.perform(get(URL + "/names").header("Authorization", basic("anything", key))).andExpect(status().isOk());
        mvc.perform(get(URL + "/names").header("Authorization", "Bearer " + key)).andExpect(status().isOk());
        mvc.perform(get(URL + "/names").header("Authorization", "gry_notarealkeynotarealkey123456"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readersCanResolveButNotPublish() throws Exception {
        users.create("hex-reader", "readerpass", "Reader", null, Set.of("reader"));
        users.create("hex-developer", "developerpass", "Developer", null, Set.of("developer"));
        publishAs(keyFor("hex-reader"), 403);
        publishAs(keyFor("hex-developer"), 201);
        mvc.perform(get(URL + "/packages/acme_lib").header("Authorization", keyFor("hex-reader"))).andExpect(status().isOk());
        mvc.perform(delete(URL + "/api/packages/acme_lib/releases/0.1.0").header("Authorization", keyFor("hex-reader")))
                .andExpect(status().isForbidden());
    }

    @Test
    void whoamiAndPermissionChecks() throws Exception {
        users.create("hex-reader", "readerpass", "Reader", "reader@example.test", Set.of("reader"));
        String readerKey = keyFor("hex-reader");
        Map<String, Object> me = term(mvc.perform(get(URL + "/api/users/me").header("Authorization", readerKey)
                        .header("Accept", ERLANG))
                .andExpect(status().isOk()).andReturn());
        assertThat(me).containsEntry("username", "hex-reader").containsEntry("email", "reader@example.test");

        Map<String, Object> anonymous = term(mvc.perform(get(URL + "/api/users/me").header("Accept", ERLANG))
                .andExpect(status().isUnauthorized()).andReturn());
        assertThat(anonymous).containsEntry("status", 401L);

        mvc.perform(get(URL + "/api/auth").queryParam("domain", "api").queryParam("resource", "write")
                .header("Authorization", readerKey)).andExpect(status().isForbidden());
        mvc.perform(get(URL + "/api/auth").queryParam("domain", "api").queryParam("resource", "read")
                .header("Authorization", readerKey)).andExpect(status().isNoContent());
        mvc.perform(get(URL + "/api/auth").queryParam("domain", "api").queryParam("resource", "write")
                .header("Authorization", keyFor(ADMIN))).andExpect(status().isNoContent());
    }

    @Test
    void hexUserAuthMintsAKeyFromThePassword() throws Exception {
        Map<String, Object> body = Map.of("name", "laptop", "permissions", java.util.List.of(Map.of("domain", "api")));
        Map<String, Object> created = term(mvc.perform(post(URL + "/api/keys").header("Authorization", adminBasic())
                        .header("Accept", ERLANG).contentType(ERLANG).content(Etf.encode(body)))
                .andExpect(status().isCreated()).andReturn());
        String secret = (String) created.get("secret");
        assertThat(secret).startsWith("gry_");
        assertThat(tokens.list(users.require(ADMIN))).extracting(ApiToken::getName).contains("laptop");

        publishAs(secret, 201);
        mvc.perform(delete(URL + "/api/keys/laptop").header("Authorization", adminBasic())).andExpect(status().isNoContent());
        publishAs(secret, 401);
        mvc.perform(post(URL + "/api/keys").contentType(ERLANG).content(Etf.encode(body))).andExpect(status().isUnauthorized());
    }
}
