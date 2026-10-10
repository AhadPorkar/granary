package dev.granary.server.rubygems;

import static dev.granary.server.rubygems.RubygemsFixtures.gem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.ApiToken;
import dev.granary.core.model.Repository;
import dev.granary.server.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** How {@code gem} authenticates: {@code gem signin}, API keys sent bare, Basic in source URLs, and the refusals. */
class RubygemsAuthIntegrationTest extends IntegrationTest {

    private static final String REPO = "gems-hosted";
    private static final String URL = "/repository/" + REPO;
    private static final List<String> TEST_USERS = List.of("gem-reader", "gem-dev");

    private Repository repo;

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "rubygems");
    }

    @AfterEach
    void removeTestUsers() {
        for (String username : TEST_USERS) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }

    private static MockHttpServletRequestBuilder pushRequest(byte[] gem) {
        return post(URL + "/api/v1/gems").contentType(MediaType.APPLICATION_OCTET_STREAM).content(gem);
    }

    /** {@code gem signin}: POST with Basic credentials and the key's name and scopes as a form. */
    private static MockHttpServletRequestBuilder signin(String authorization) {
        return post(URL + "/api/v1/api_key").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("name", "laptop")
                .param("index_rubygems", "true")
                .param("push_rubygem", "true");
    }

    @Test
    void signinIssuesATokenThatGemPushSendsAsItIs() throws Exception {
        String token = mvc.perform(signin(adminBasic()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(token).startsWith("gry_");
        assertThat(tokens.list(users.require(ADMIN))).extracting(ApiToken::getName).contains("laptop");

        // gem push sends the key from ~/.gem/credentials as the whole Authorization header
        mvc.perform(pushRequest(gem("signed", "1.0")).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(content().string("Successfully registered gem: signed (1.0)"));

        // gem signin then updates the key's scopes with a PUT, which changes nothing here
        mvc.perform(put(URL + "/api/v1/api_key").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("api_key=" + token + "&push_rubygem=true"))
                .andExpect(status().isOk())
                .andExpect(content().string(token));
    }

    @Test
    void signinNeedsValidCredentials() throws Exception {
        mvc.perform(post(URL + "/api/v1/api_key").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("name=x"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")));
        mvc.perform(signin(basic(ADMIN, "wrong-password"))).andExpect(status().isUnauthorized());
        assertThat(tokens.list(users.require(ADMIN))).extracting(ApiToken::getName).doesNotContain("laptop");
    }

    @Test
    void signinCanBeTurnedOffPerRepository() throws Exception {
        repo.setFormatSettings(Map.of("rubygems.signin", "false"));
        repositories.update(REPO, repo);
        mvc.perform(signin(adminBasic())).andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("disabled")));
    }

    @Test
    void invalidSettingsAreRefused() {
        repo.setFormatSettings(Map.of("rubygems.maxGemSizeMb", "huge"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repositories.update(REPO, repo))
                .hasMessageContaining("rubygems.maxGemSizeMb");
    }

    @Test
    void pushNeedsWritePermission() throws Exception {
        users.create("gem-reader", "readerpassword", "Reader", null, Set.of("reader"));
        users.create("gem-dev", "devpassword", "Dev", null, Set.of("developer"));

        mvc.perform(pushRequest(gem("anon", "1.0"))).andExpect(status().isUnauthorized());
        mvc.perform(pushRequest(gem("reader", "1.0")).header("Authorization", basic("gem-reader", "readerpassword")))
                .andExpect(status().isForbidden());
        mvc.perform(pushRequest(gem("dev", "1.0")).header("Authorization", basic("gem-dev", "devpassword")))
                .andExpect(status().isOk());
        mvc.perform(pushRequest(gem("bad", "1.0")).header("Authorization", "gry_not_a_real_token"))
                .andExpect(status().isUnauthorized());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(URL + "/api/v1/gems/yank")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("gem_name=dev&version=1.0"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readsFollowTheAnonymousAccessSetting() throws Exception {
        mvc.perform(pushRequest(gem("acme_hello", "1.0")).header("Authorization", adminBasic())).andExpect(status().isOk());
        mvc.perform(get(URL + "/versions")).andExpect(status().isOk());

        settings.setAnonymousReadEnabled(false);
        mvc.perform(get(URL + "/versions")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/gems/acme_hello-1.0.gem")).andExpect(status().isUnauthorized());

        // Bundler and gem send the credentials of the source URL as Basic
        mvc.perform(get(URL + "/versions").header("Authorization", adminBasic())).andExpect(status().isOk());
        String token = tokens.issue(users.require(ADMIN), "bundler", null).secret();
        mvc.perform(get(URL + "/info/acme_hello").header("Authorization", basic(ADMIN, token))).andExpect(status().isOk());
        mvc.perform(get(URL + "/gems/acme_hello-1.0.gem").header("Authorization", token)).andExpect(status().isOk());
    }
}
