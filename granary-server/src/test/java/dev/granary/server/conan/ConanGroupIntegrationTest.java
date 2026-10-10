package dev.granary.server.conan;

import static dev.granary.server.conan.ConanFixtures.PID;
import static dev.granary.server.conan.ConanFixtures.PREV;
import static dev.granary.server.conan.ConanFixtures.PREV2;
import static dev.granary.server.conan.ConanFixtures.RREV;
import static dev.granary.server.conan.ConanFixtures.RREV2;
import static dev.granary.server.conan.ConanFixtures.latestJson;
import static dev.granary.server.conan.ConanFixtures.packageFiles;
import static dev.granary.server.conan.ConanFixtures.packageRevision;
import static dev.granary.server.conan.ConanFixtures.recipe;
import static dev.granary.server.conan.ConanFixtures.recipeFiles;
import static dev.granary.server.conan.ConanFixtures.revision;
import static dev.granary.server.conan.ConanFixtures.revisionsJson;
import static dev.granary.server.conan.ConanFixtures.tgz;
import static dev.granary.server.conan.ConanFixtures.upload;
import static dev.granary.server.conan.ConanFixtures.utf8;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.server.IntegrationTest;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** One remote for everything: a group of a hosted repository and a ConanCenter proxy, hosted first. */
class ConanGroupIntegrationTest extends IntegrationTest {

    private static final String GROUP = "/repository/conan-group";
    private static final String HOSTED = "/repository/conan-hosted";
    private static final String ZLIB = recipe("zlib", "1.3.1");
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private ProxyService proxyService;

    @BeforeAll
    static void startUpstream() {
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void createRepositories() {
        upstream.resetAll();
        hosted("conan-hosted", "conan");
        Repository proxy = proxy("conancenter", "conan", upstream.baseUrl());
        proxyService.invalidate(proxy);
        // the proxy is listed first: hosted content must still win
        group("conan-group", "conan", "conancenter", "conan-hosted");
    }

    @Test
    void revisionListsAreMergedWithHostedEntriesFirst() throws Exception {
        publish(ZLIB, RREV2, PID, PREV, "hosted");
        stubJson(ZLIB + "/revisions", revisionsJson(RREV2, "2020-01-01T00:00:00.000+0000", RREV, "2019-01-01T00:00:00.000+0000"));
        stubJson(ZLIB + "/latest", latestJson(RREV, "2019-01-01T00:00:00.000+0000"));

        mvc.perform(get(GROUP + ZLIB + "/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions.length()").value(2))
                .andExpect(jsonPath("$.revisions[0].revision").value(RREV2))
                // the hosted member's time for the revision both know, not the upstream's
                .andExpect(jsonPath("$.revisions[0].time").value(Matchers.not("2020-01-01T00:00:00.000+0000")))
                .andExpect(jsonPath("$.revisions[1].revision").value(RREV));
        mvc.perform(get(GROUP + ZLIB + "/latest")).andExpect(jsonPath("$.revision").value(RREV2));
    }

    @Test
    void latestIsTheNewestAcrossMembers() throws Exception {
        publish(ZLIB, RREV, PID, PREV, "hosted");
        stubJson(ZLIB + "/latest", latestJson(RREV2, "2099-01-01T00:00:00.000+0000"));
        mvc.perform(get(GROUP + ZLIB + "/latest")).andExpect(jsonPath("$.revision").value(RREV2));
        stubJson(revision(ZLIB, RREV) + "/packages/" + PID + "/latest", latestJson(PREV2, "2099-01-01T00:00:00.000+0000"));
        mvc.perform(get(GROUP + revision(ZLIB, RREV) + "/packages/" + PID + "/latest")).andExpect(jsonPath("$.revision").value(PREV2));
    }

    @Test
    void filesComeFromTheMemberThatHasTheRevisionHostedFirst() throws Exception {
        Map<String, byte[]> hostedFiles = recipeFiles("hosted");
        publish(ZLIB, RREV, PID, PREV, "hosted");
        byte[] upstreamExport = tgz("upstream");
        stubBytes(revision(ZLIB, RREV) + "/files/conan_export.tgz", tgz("must not be used"));
        stubBytes(revision(ZLIB, RREV2) + "/files/conan_export.tgz", upstreamExport);
        stubJson(revision(ZLIB, RREV2) + "/files", "{\"files\":{\"conan_export.tgz\":{},\"conanfile.py\":{},\"conanmanifest.txt\":{}}}");

        mvc.perform(get(GROUP + revision(ZLIB, RREV) + "/files/conan_export.tgz"))
                .andExpect(status().isOk()).andExpect(content().bytes(hostedFiles.get("conan_export.tgz")));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo(revision(ZLIB, RREV) + "/files/conan_export.tgz")));
        mvc.perform(get(GROUP + revision(ZLIB, RREV2) + "/files"))
                .andExpect(jsonPath("$.files['conan_export.tgz']").isMap());
        mvc.perform(get(GROUP + revision(ZLIB, RREV2) + "/files/conan_export.tgz"))
                .andExpect(status().isOk()).andExpect(content().bytes(upstreamExport));
        mvc.perform(get(GROUP + packageRevision(ZLIB, RREV, PID, PREV) + "/files/conan_package.tgz"))
                .andExpect(content().bytes(packageFiles("hosted").get("conan_package.tgz")));
        mvc.perform(get(GROUP + revision(ZLIB, "0123456789abcdef0123456789abcdef") + "/files")).andExpect(status().isNotFound());
    }

    @Test
    void searchesAreMerged() throws Exception {
        publish(recipe("acme", "1.0"), RREV, PID, PREV, "acme");
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/conans/search"))
                .willReturn(WireMock.okJson("{\"results\":[\"zlib/1.3.1@_/_\",\"acme/1.0@_/_\"]}")));
        mvc.perform(get(GROUP + "/v2/conans/search").param("q", "*"))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0]").value("acme/1.0"))
                .andExpect(jsonPath("$.results[1]").value("zlib/1.3.1"));

        publish(ZLIB, RREV, PID, PREV, "hosted");
        stubJson(revision(ZLIB, RREV) + "/search",
                "{\"" + PID + "\":{\"settings\":{\"os\":\"Windows\"}},\"aaaa\":{\"settings\":{\"os\":\"Macos\"}}}");
        mvc.perform(get(GROUP + revision(ZLIB, RREV) + "/search"))
                .andExpect(jsonPath("$['" + PID + "'].settings.os").value("Linux"))
                .andExpect(jsonPath("$.aaaa.settings.os").value("Macos"));
    }

    @Test
    void groupsServeHostedContentWhileTheUpstreamIsDown() throws Exception {
        publish(ZLIB, RREV, PID, PREV, "hosted");
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));
        mvc.perform(get(GROUP + ZLIB + "/latest")).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(RREV));
        mvc.perform(get(GROUP + revision(ZLIB, RREV) + "/files/conanfile.py")).andExpect(status().isOk());
        mvc.perform(get(GROUP + recipe("openssl", "3.3.2") + "/latest"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void writesAreRefusedAndNameTheHostedMember() throws Exception {
        // the client prints the message of a 403 ("Permission denied for user: 'admin': 403: ...") and stops
        mvc.perform(upload(GROUP + revision(ZLIB, RREV) + "/files/conanfile.py", utf8("x"), adminBasic()))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(jsonPath("$.code").value("repository.readOnly"))
                .andExpect(jsonPath("$.errors[0].status").value(403))
                .andExpect(jsonPath("$.errors[0].message").value(Matchers.containsString("upload to its hosted member 'conan-hosted'")));
        // the checksum-deploy probe the client sends first gets the same answer
        mvc.perform(put(GROUP + revision(ZLIB, RREV) + "/files/conanfile.py").header("Authorization", adminBasic())
                        .header("X-Checksum-Deploy", "true").header("X-Checksum-Sha1", ConanFixtures.sha1(utf8("x"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete(GROUP + revision(ZLIB, RREV)).header("Authorization", adminBasic()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].message").value(Matchers.containsString("hosted member 'conan-hosted'")));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void pingAndLoginWorkThroughTheGroup() throws Exception {
        mvc.perform(get(GROUP + "/v1/ping")).andExpect(status().isOk())
                .andExpect(header().exists("X-Conan-Server-Capabilities"));
        mvc.perform(get(GROUP + "/v2/users/authenticate").header("Authorization", adminBasic()))
                .andExpect(status().isOk()).andExpect(content().string(Matchers.startsWith("gry_")));
    }

    private void publish(String recipe, String rrev, String pid, String prev, String seed) throws Exception {
        for (Map.Entry<String, byte[]> file : recipeFiles(seed).entrySet()) {
            mvc.perform(upload(HOSTED + revision(recipe, rrev) + "/files/" + file.getKey(), file.getValue(), adminBasic()))
                    .andExpect(status().is2xxSuccessful());
        }
        for (Map.Entry<String, byte[]> file : packageFiles(seed).entrySet()) {
            mvc.perform(upload(HOSTED + packageRevision(recipe, rrev, pid, prev) + "/files/" + file.getKey(), file.getValue(),
                    adminBasic())).andExpect(status().is2xxSuccessful());
        }
    }

    private static void stubJson(String path, String body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).willReturn(WireMock.okJson(body)));
    }

    private static void stubBytes(String path, byte[] body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/octet-stream").withBody(body)));
    }
}
