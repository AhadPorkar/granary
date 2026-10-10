package dev.granary.server.pub;

import static dev.granary.server.pub.PubFixtures.archive;
import static dev.granary.server.pub.PubFixtures.repositoryUrl;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/**
 * How dart pub authenticates: a token stored with {@code dart pub token add} goes out as {@code Bearer}; a
 * refusal must carry {@code WWW-Authenticate: Bearer realm="pub", message="…"} for the client to print the
 * server's explanation under its own hint to run {@code dart pub token add}.
 */
class PubAuthIntegrationTest extends PubClientSupport {

    private static final String URL = repositoryUrl("pub-hosted");
    private static final List<String> TEST_USERS = List.of("pub-reader", "pub-developer");

    private String adminToken;

    @BeforeEach
    void createRepositories() {
        hosted("pub-hosted", "pub");
        group("pub-group", "pub", "pub-hosted");
        adminToken = adminToken();
    }

    @AfterEach
    void removeTestUsers() {
        for (String username : TEST_USERS) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }

    private String tokenOf(String username, String role) {
        users.create(username, username + "-password", username, null, Set.of(role));
        return tokens.issue(users.require(username), "pub", null).secret();
    }

    @Test
    void anonymousPublishingGetsThePubChallenge() throws Exception {
        mvc.perform(api(URL + "/api/packages/versions/new"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        "Bearer realm=\"pub\", message=\"Granary repository 'pub-hosted' needs an API token to publish to or change it.\""))
                .andExpect(jsonPath("$.error.code").value("MissingAuthentication"));
        mvc.perform(multipart(HttpMethod.POST, URI.create(URL + "/api/packages/versions/newUpload"))
                        .file(new MockMultipartFile("file", "package.tar.gz", "application/octet-stream", archive("x", "1.0.0"))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("realm=\"pub\"")));
        mvc.perform(api(URL + "/api/packages/versions/newUploadFinish?id=00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(URL + "/api/packages/x/versions/1.0.0/retract")).andExpect(status().isUnauthorized());
        mvc.perform(delete(URL + "/api/packages/x")).andExpect(status().isUnauthorized());
    }

    @Test
    void readersMayDownloadButNotPublish() throws Exception {
        publish("pub-hosted", archive("granary_demo", "1.0.0"), adminToken).andExpect(status().isOk());
        String reader = tokenOf("pub-reader", "reader");
        mvc.perform(withToken(api(URL + "/api/packages/granary_demo"), reader)).andExpect(status().isOk());
        mvc.perform(withToken(download(URL + "/packages/granary_demo/versions/1.0.0.tar.gz"), reader)).andExpect(status().isOk());
        mvc.perform(withToken(api(URL + "/api/packages/versions/new"), reader))
                .andExpect(status().isForbidden())
                .andExpect(header().string("WWW-Authenticate",
                        "Bearer realm=\"pub\", message=\"User 'pub-reader' may not publish to or change Granary repository 'pub-hosted'.\""))
                .andExpect(jsonPath("$.error.code").value("InsufficientPermissions"));
        mvc.perform(withToken(delete(URL + "/api/packages/granary_demo/versions/1.0.0"), reader)).andExpect(status().isForbidden());
    }

    @Test
    void developersMayPublish() throws Exception {
        String developer = tokenOf("pub-developer", "developer");
        publish("pub-hosted", archive("granary_demo", "1.0.0"), developer).andExpect(status().isOk());
    }

    @Test
    void anOutcomeIsOnlyShownToTheUploader() throws Exception {
        String developer = tokenOf("pub-developer", "developer");
        MvcResult upload = mvc.perform(withToken(multipart(HttpMethod.POST, URI.create(URL + "/api/packages/versions/newUpload"))
                        .file(new MockMultipartFile("file", "package.tar.gz", "application/octet-stream",
                                archive("granary_demo", "1.0.0"))), developer))
                .andExpect(status().isNoContent()).andReturn();
        String location = upload.getResponse().getHeader("Location");
        mvc.perform(withToken(api(location), adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value(containsString("unknown or has expired")));
        mvc.perform(withToken(api(location), developer)).andExpect(status().isOk());
    }

    @Test
    void withAnonymousReadOffDownloadsNeedTheToken() throws Exception {
        publish("pub-hosted", archive("granary_demo", "1.0.0"), adminToken).andExpect(status().isOk());
        settings.setAnonymousReadEnabled(false);

        mvc.perform(api(repositoryUrl("pub-group") + "/api/packages/granary_demo"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        "Bearer realm=\"pub\", message=\"Granary repository 'pub-group' needs an API token to read it.\""))
                .andExpect(jsonPath("$.error.code").value("MissingAuthentication"));
        mvc.perform(download(URL + "/packages/granary_demo/versions/1.0.0.tar.gz"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer realm=\"pub\"")));

        mvc.perform(withToken(api(repositoryUrl("pub-group") + "/api/packages/granary_demo"), adminToken))
                .andExpect(status().isOk());
        mvc.perform(withToken(download(repositoryUrl("pub-group") + "/packages/granary_demo/versions/1.0.0.tar.gz"), adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void anInvalidTokenIsRefusedBeforeTheRepositoryIsAsked() throws Exception {
        mvc.perform(withToken(api(URL + "/api/packages/granary_demo"), "gry_not-a-real-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(withToken(api(URL + "/api/packages/versions/new"), "gry_not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionTokenOfTheWebUiWorksAsBearerToo() throws Exception {
        mvc.perform(api(URL + "/api/packages/versions/new").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(URL + "/api/packages/versions/newUpload"));
    }
}
