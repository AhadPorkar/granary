package dev.granary.server.cocoapods;

import static dev.granary.server.cocoapods.CocoaPodsFixtures.gitPodspec;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.podspecPath;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.pushWithArchive;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.putPodspec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/**
 * How pod and curl authenticate: pod's CDN source sends HTTP Basic from {@code ~/.netrc} (a password or an API
 * token as the password), curl sends {@code -u user:token}.
 */
class CocoaPodsAuthIntegrationTest extends IntegrationTest {

    private static final String REPO = "cocoapods-hosted";
    private static final String URL = "/repository/" + REPO;

    /** Users outlive repositories in the shared test context, so every user a test creates is removed again. */
    private static final List<String> TEST_USERS = List.of("pods-reader", "pods-dev");

    @BeforeEach
    void createRepository() {
        hosted(REPO, "cocoapods");
    }

    @AfterEach
    void removeTestUsers() {
        for (String username : TEST_USERS) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }

    @Test
    void anonymousPushesGetABasicChallenge() throws Exception {
        mvc.perform(putPodspec(REPO, "Anon", "1.0", gitPodspec("Anon", "1.0")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Basic")));
        mvc.perform(pushWithArchive(REPO, gitPodspec("Anon", "1.0"), null, null)).andExpect(status().isUnauthorized());
        mvc.perform(delete(URL + "/Specs/Anon/1.0")).andExpect(status().isUnauthorized());
        // reading stays anonymous by default
        mvc.perform(get(URL + "/all_pods.txt")).andExpect(status().isOk());
        mvc.perform(get(URL + "/" + podspecPath("Anon", "1.0"))).andExpect(status().isNotFound());
    }

    @Test
    void readersCannotPushButDevelopersCan() throws Exception {
        users.create("pods-reader", "readerpass", "Reader", null, Set.of("reader"));
        users.create("pods-dev", "developerpass", "Developer", null, Set.of("developer"));
        mvc.perform(putPodspec(REPO, "Team", "1.0", gitPodspec("Team", "1.0")).header("Authorization", basic("pods-reader", "readerpass")))
                .andExpect(status().isForbidden());
        mvc.perform(putPodspec(REPO, "Team", "1.0", gitPodspec("Team", "1.0")).header("Authorization", basic("pods-dev", "developerpass")))
                .andExpect(status().isCreated());
        mvc.perform(get(URL + "/" + podspecPath("Team", "1.0")).header("Authorization", basic("pods-reader", "readerpass")))
                .andExpect(status().isOk());
        mvc.perform(delete(URL + "/Specs/Team/1.0").header("Authorization", basic("pods-reader", "readerpass")))
                .andExpect(status().isForbidden());
        mvc.perform(delete(URL + "/Specs/Team/1.0").header("Authorization", basic("pods-dev", "developerpass")))
                .andExpect(status().isNoContent());
    }

    @Test
    void anApiTokenIsThePasswordInNetrc() throws Exception {
        User admin = users.require(ADMIN);
        ApiTokenService.IssuedToken issued = tokens.issue(admin, "pod", null);
        assertThat(issued.secret()).startsWith("gry_");
        mvc.perform(putPodspec(REPO, "Tok", "1.0", gitPodspec("Tok", "1.0")).header("Authorization", basic(ADMIN, issued.secret())))
                .andExpect(status().isCreated());
        mvc.perform(putPodspec(REPO, "Tok", "1.1", gitPodspec("Tok", "1.1")).header("Authorization", basic(ADMIN, "gry_wrong")))
                .andExpect(status().isUnauthorized());

        // with anonymous reads off, pod needs the .netrc entry for every index file and podspec
        settings.setAnonymousReadEnabled(false);
        mvc.perform(get(URL + "/CocoaPods-version.yml")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Basic")));
        mvc.perform(get(URL + "/all_pods_versions_0_0_0.txt")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/" + podspecPath("Tok", "1.0"))).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/CocoaPods-version.yml").header("Authorization", basic(ADMIN, issued.secret()))).andExpect(status().isOk());
        mvc.perform(get(URL + "/" + podspecPath("Tok", "1.0")).header("Authorization", basic(ADMIN, issued.secret())))
                .andExpect(status().isOk());
    }
}
