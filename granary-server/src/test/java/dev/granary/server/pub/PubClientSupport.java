package dev.granary.server.pub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.server.IntegrationTest;
import java.net.URI;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Requests exactly as {@code dart pub} sends them (recorded from Dart pub 3.13.5): the API requests carry
 * {@code Accept: application/vnd.pub.v2+json}, archive downloads do not; a token added with
 * {@code dart pub token add} is sent as {@code Authorization: Bearer}.
 */
abstract class PubClientSupport extends IntegrationTest {

    /** An API request of the pub client (listing, version, advisories, the publishing steps). */
    static MockHttpServletRequestBuilder api(String url) {
        return get(URI.create(url)).header("User-Agent", PubFixtures.USER_AGENT).header("Accept", PubFixtures.ACCEPT)
                .header("Accept-Encoding", PubFixtures.ACCEPT_ENCODING);
    }

    /** An archive download of the pub client: no Accept header. */
    static MockHttpServletRequestBuilder download(String url) {
        return get(URI.create(url)).header("User-Agent", PubFixtures.USER_AGENT)
                .header("Accept-Encoding", PubFixtures.ACCEPT_ENCODING);
    }

    static <B extends AbstractMockHttpServletRequestBuilder<B>> B withToken(B request, String token) {
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    String adminToken() {
        return tokens.issue(users.require(ADMIN), "pub-" + java.util.UUID.randomUUID(), null).secret();
    }

    /**
     * {@code dart pub publish}: asks where to upload, uploads the archive as the multipart field {@code file}
     * (filename {@code package.tar.gz}, {@code application/octet-stream}), follows the {@code Location} of the
     * 204 and returns the answer of that last request, which carries the outcome.
     */
    ResultActions publish(String repo, byte[] archive, String token) throws Exception {
        String base = PubFixtures.repositoryUrl(repo);
        MvcResult start = mvc.perform(withToken(api(base + "/api/packages/versions/new"), token))
                .andExpect(status().isOk()).andReturn();
        String uploadUrl = PubFixtures.str(PubFixtures.json(start), "url");
        assertThat(uploadUrl).isEqualTo(base + "/api/packages/versions/newUpload");
        assertThat(PubFixtures.json(start).path("fields").isObject()).isTrue();

        MvcResult upload = mvc.perform(withToken(multipart(HttpMethod.POST, URI.create(uploadUrl))
                        .file(new MockMultipartFile("file", "package.tar.gz", "application/octet-stream", archive))
                        .header("User-Agent", PubFixtures.USER_AGENT)
                        .header("Accept-Encoding", PubFixtures.ACCEPT_ENCODING), token))
                .andExpect(status().isNoContent()).andReturn();
        String location = upload.getResponse().getHeader("Location");
        assertThat(location).startsWith(base + "/api/packages/versions/newUploadFinish?id=");
        return mvc.perform(withToken(api(location), token));
    }
}
