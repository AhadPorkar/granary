package dev.granary.server.swift;

import static dev.granary.server.swift.SwiftFixtures.METADATA;
import static dev.granary.server.swift.SwiftFixtures.archive;
import static dev.granary.server.swift.SwiftFixtures.getJson;
import static dev.granary.server.swift.SwiftFixtures.getSwift;
import static dev.granary.server.swift.SwiftFixtures.getZip;
import static dev.granary.server.swift.SwiftFixtures.json;
import static dev.granary.server.swift.SwiftFixtures.publish;
import static dev.granary.server.swift.SwiftFixtures.repositoryUrl;
import static dev.granary.server.swift.SwiftFixtures.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.proxy.ProxyService;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A group of a hosted registry and a proxy of another registry: one registry URL for {@code swift package-registry set},
 * release lists merged with the hosted member first, every file of a release from the member that owns it.
 */
class SwiftGroupIntegrationTest extends IntegrationTest {

    private static final String GROUP = "swift-group";
    private static final String URL = "/repository/" + GROUP;
    private static final String BASE = repositoryUrl(GROUP);
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final byte[] HOSTED_ZIP = archive("acme.demo", "hosted", true);
    private static final byte[] UPSTREAM_ZIP_1 = archive("acme.demo", "upstream 1.0.0", false);
    private static final byte[] UPSTREAM_ZIP_2 = archive("acme.demo", "upstream 2.0.0", false);

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
    void createRepositories() throws Exception {
        upstream.resetAll();
        hosted("swift-hosted", "swift");
        proxyService.invalidate(proxy("swift-upstream", "swift", upstream.baseUrl() + "/"));
        group(GROUP, "swift", "swift-hosted", "swift-upstream");

        // the hosted member has 1.0.0 (the upstream has another 1.0.0) and 1.5.0; the upstream adds 2.0.0
        mvc.perform(publish("/repository/swift-hosted/acme/demo/1.0.0", HOSTED_ZIP, METADATA).header("Authorization", adminBasic()))
                .andExpect(status().isCreated());
        mvc.perform(publish("/repository/swift-hosted/acme/demo/1.5.0", archive("acme.demo", "hosted 1.5.0", false), null)
                .header("Authorization", adminBasic())).andExpect(status().isCreated());
        stubJson("/acme/demo", """
                {"releases":{"1.0.0":{"url":"%1$s/acme/demo/1.0.0"},"2.0.0":{"url":"%1$s/acme/demo/2.0.0"}}}"""
                .formatted(upstream.baseUrl()));
        stubRelease("1.0.0", UPSTREAM_ZIP_1);
        stubRelease("2.0.0", UPSTREAM_ZIP_2);
    }

    private static void stubJson(String path, String body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withHeader("Content-Version", "1").withBody(body)));
    }

    private static void stubRelease(String version, byte[] zip) {
        stubJson("/acme/demo/" + version, """
                {"id":"acme.demo","version":"%s","resources":[{"name":"source-archive","type":"application/zip","checksum":"%s"}],
                 "metadata":{"description":"upstream %s"},"publishedAt":"2024-01-31T12:00:00Z"}""".formatted(version, sha256(zip), version));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/acme/demo/" + version + ".zip")).willReturn(WireMock.aResponse()
                .withStatus(200).withHeader("Content-Type", "application/zip").withBody(zip)));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/acme/demo/" + version + "/Package.swift")).willReturn(WireMock.aResponse()
                .withStatus(200).withHeader("Content-Type", "text/x-swift").withHeader("Content-Version", "1")
                .withBody("// swift-tools-version:5.8\n// upstream " + version + "\n")));
    }

    @Test
    void releaseListsAreMergedWithTheHostedMemberFirst() throws Exception {
        MvcResult result = mvc.perform(getJson(URL + "/acme/demo"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Version", "1"))
                .andReturn();
        List<String> versions = json(result).path("releases").propertyNames().stream().toList();
        assertThat(versions).containsExactly("2.0.0", "1.5.0", "1.0.0");
        for (String v : versions) {
            assertThat(json(result).path("releases").path(v).path("url").stringValue()).isEqualTo(BASE + "/acme/demo/" + v);
        }
        // latest-version from the merged list; repository URLs from the latest release's metadata (an upstream one without any)
        assertThat(result.getResponse().getHeader("Link")).isEqualTo("<" + BASE + "/acme/demo/2.0.0>; rel=\"latest-version\"");
    }

    @Test
    void everyFileOfAReleaseComesFromTheMemberThatOwnsIt() throws Exception {
        // 1.0.0 exists in both members: the hosted one wins for metadata, manifest and archive alike
        mvc.perform(getJson(URL + "/acme/demo/1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources[0].checksum").value(sha256(HOSTED_ZIP)))
                .andExpect(jsonPath("$.metadata.description").value("A demo package"))
                .andExpect(header().string("Link", "<" + BASE + "/acme/demo/2.0.0>; rel=\"latest-version\", <" + BASE
                        + "/acme/demo/1.5.0>; rel=\"successor-version\""));
        mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip")).andExpect(status().isOk()).andExpect(content().bytes(HOSTED_ZIP));
        mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift")).andExpect(status().isOk())
                .andExpect(header().string("Link", "<" + BASE + "/acme/demo/1.0.0/Package.swift?swift-version=6>; rel=\"alternate\"; "
                        + "filename=\"Package@swift-6.swift\"; swift-tools-version=\"6.0\""));
        mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift?swift-version=6.0.0")).andExpect(status().isOk())
                .andExpect(content().string(SwiftFixtures.MANIFEST_6));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/acme/demo/1.0.0")));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/acme/demo/1.0.0.zip")));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/acme/demo/1.0.0/Package.swift")));

        // 2.0.0 only exists upstream
        mvc.perform(getJson(URL + "/acme/demo/2.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources[0].checksum").value(sha256(UPSTREAM_ZIP_2)))
                .andExpect(header().string("Link", "<" + BASE + "/acme/demo/2.0.0>; rel=\"latest-version\", <" + BASE
                        + "/acme/demo/1.5.0>; rel=\"predecessor-version\""));
        mvc.perform(getZip(URL + "/acme/demo/2.0.0.zip")).andExpect(status().isOk()).andExpect(content().bytes(UPSTREAM_ZIP_2));
        mvc.perform(getSwift(URL + "/acme/demo/2.0.0/Package.swift")).andExpect(status().isOk())
                .andExpect(content().string("// swift-tools-version:5.8\n// upstream 2.0.0\n"))
                .andExpect(header().doesNotExist("Link"));
        mvc.perform(getJson(URL + "/acme/demo/3.0.0")).andExpect(status().isNotFound());
    }

    @Test
    void identifiersAreMergedAcrossMembers() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/identifiers")).willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withHeader("Content-Version", "1")
                .withBody("{\"identifiers\":[\"ACME.demo\",\"mirror.demo\"]}")));
        mvc.perform(getJson(URL + "/identifiers?url=https://github.com/acme/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identifiers.length()").value(2))
                .andExpect(jsonPath("$.identifiers[0]").value("acme.demo"))
                .andExpect(jsonPath("$.identifiers[1]").value("mirror.demo"));
    }

    @Test
    void groupsAreReadOnlyAndNameTheHostedMember() throws Exception {
        mvc.perform(publish(URL + "/acme/demo/9.0.0", HOSTED_ZIP, null).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET, HEAD"))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.detail").value("repository 'swift-group' is a group and read-only; publish to its hosted member 'swift-hosted'"));
        mvc.perform(delete(URI.create(URL + "/acme/demo/1.0.0")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        // logging in through the group works: it only checks the credentials
        mvc.perform(post(URI.create(URL)).header("Authorization", adminBasic())).andExpect(status().isOk());
    }

    @Test
    void anUnreachableProxyMemberDoesNotHideHostedReleases() throws Exception {
        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));
        proxyService.invalidate(repositories.find("swift-upstream").orElseThrow());

        mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.releases['1.0.0']").exists())
                .andExpect(jsonPath("$.releases['1.5.0']").exists())
                .andExpect(jsonPath("$.releases['2.0.0']").doesNotExist());
        mvc.perform(getZip(URL + "/acme/demo/1.5.0.zip")).andExpect(status().isOk());
        // what only the broken member could have is a temporary 503
        mvc.perform(getZip(URL + "/acme/demo/2.0.0.zip")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void offlineGroupsServeHostedAndCachedReleases() throws Exception {
        mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isOk());
        mvc.perform(getZip(URL + "/acme/demo/2.0.0.zip")).andExpect(status().isOk());
        settings.setGlobalOffline(true);
        upstream.resetAll();

        mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.releases['2.0.0']").exists());
        mvc.perform(getZip(URL + "/acme/demo/2.0.0.zip")).andExpect(status().isOk()).andExpect(content().bytes(UPSTREAM_ZIP_2));
        mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip")).andExpect(status().isOk()).andExpect(content().bytes(HOSTED_ZIP));
        mvc.perform(getJson(URL + "/acme/other")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }
}
