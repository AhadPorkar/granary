package dev.granary.server.composer;

import static dev.granary.server.composer.ComposerFixtures.AUDIT_BODY;
import static dev.granary.server.composer.ComposerFixtures.REF_301;
import static dev.granary.server.composer.ComposerFixtures.REF_302;
import static dev.granary.server.composer.ComposerFixtures.USER_AGENT;
import static dev.granary.server.composer.ComposerFixtures.archive;
import static dev.granary.server.composer.ComposerFixtures.composerJson;
import static dev.granary.server.composer.ComposerFixtures.githubZipball;
import static dev.granary.server.composer.ComposerFixtures.nextSecond;
import static dev.granary.server.composer.ComposerFixtures.object;
import static dev.granary.server.composer.ComposerFixtures.repositoryUrl;
import static dev.granary.server.composer.ComposerFixtures.sha1;
import static dev.granary.server.composer.ComposerFixtures.str;
import static dev.granary.server.composer.ComposerFixtures.upstreamAcmeLib;
import static dev.granary.server.composer.ComposerFixtures.upstreamPsrLog;
import static dev.granary.server.composer.ComposerFixtures.upstreamRoot;
import static dev.granary.server.composer.ComposerFixtures.upstreamSearch;
import static dev.granary.server.composer.ComposerFixtures.versions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** One repository URL for everything: a group of a hosted repository and a proxy of a fake packagist.org. */
class ComposerGroupIntegrationTest extends IntegrationTest {

    private static final String GROUP = "composer-group";
    private static final String URL = "/repository/" + GROUP;
    private static final WireMockServer packagist = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer github = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private StorageService storage;

    private Repository hostedRepo;
    private Repository proxyRepo;
    private final byte[] lib = archive(composerJson("acme/lib", null, null));
    private final byte[] zip302 = githubZipball("psr/log", "3.0.2", REF_302);

    @BeforeAll
    static void startUpstreams() {
        packagist.start();
        github.start();
    }

    @AfterAll
    static void stopUpstreams() {
        packagist.stop();
        github.stop();
    }

    @BeforeEach
    void createRepositories() throws Exception {
        packagist.resetAll();
        github.resetAll();
        // members listed proxy first: hosted members still take precedence
        hostedRepo = hosted("composer-hosted", "composer");
        Repository proxy = new Repository();
        proxy.setName("packagist");
        proxy.setFormat("composer");
        proxy.setType(RepositoryType.PROXY);
        proxy.setRemoteUrl(packagist.baseUrl() + "/");
        proxyRepo = repositories.create(proxy);
        proxyService.invalidate(proxyRepo);
        group(GROUP, "composer", "packagist", "composer-hosted");

        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/packages.json")).willReturn(WireMock.okJson(upstreamRoot(packagist.baseUrl()))));
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/p2/psr/log.json")).willReturn(WireMock.okJson(upstreamPsrLog(github.baseUrl()))));
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/p2/acme/lib.json")).willReturn(WireMock.okJson(upstreamAcmeLib(github.baseUrl()))));
        packagist.stubFor(WireMock.get(WireMock.urlPathMatching("/p2/.*")).atPriority(10)
                .willReturn(WireMock.aResponse().withStatus(404)));
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/search.json")).willReturn(WireMock.okJson(upstreamSearch("acme/lib", "acme/acme-client"))));
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/packages/list.json")).willReturn(WireMock.okJson("{\"packageNames\":[\"acme/acme-client\",\"psr/log\"]}")));
        github.stubFor(WireMock.get(WireMock.urlPathEqualTo("/repos/php-fig/log/zipball/" + REF_302))
                .willReturn(WireMock.aResponse().withStatus(302).withHeader("Location", github.baseUrl() + "/legacy/" + REF_302)));
        github.stubFor(WireMock.get(WireMock.urlPathEqualTo("/legacy/" + REF_302))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/zip").withBody(zip302)));
        github.stubFor(WireMock.get(WireMock.urlPathEqualTo("/repos/acme/lib/zipball/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/zip")
                        .withBody(githubZipball("acme/lib", "0.9.0", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))));

        mvc.perform(put("/repository/composer-hosted/packages/acme/lib/1.0.0").header("Authorization", adminBasic()).content(lib))
                .andExpect(status().isCreated());
    }

    private ResultActions composerGet(String url) throws Exception {
        return mvc.perform(get(url).header("User-Agent", USER_AGENT).accept(MediaType.ALL));
    }

    @Test
    void theRootDocumentIsTheGroups() throws Exception {
        ObjectNode root = object(composerGet(URL + "/packages.json").andExpect(status().isOk()).andReturn());
        String base = repositoryUrl(GROUP);
        assertThat(str(root, "metadata-url")).isEqualTo(base + "/p2/%package%.json");
        assertThat(str(root, "notify-batch")).isEqualTo(base + "/downloads");
        assertThat(str(root, "search")).isEqualTo(base + "/search.json?q=%query%&type=%type%");
        assertThat(str(root, "list")).isEqualTo(base + "/list.json");
        assertThat(str(root, "security-advisories", "api-url")).isEqualTo(base + "/api/security-advisories");
        // a group cannot list everything packagist has, so it lists nothing and Composer asks for every package
        assertThat(root.has("available-packages")).isFalse();
        assertThat(root.has("providers-url")).isFalse();
    }

    @Test
    void versionsAreMergedAndHostedReleasesWin() throws Exception {
        ObjectNode p2 = object(composerGet(URL + "/p2/acme/lib.json").andExpect(status().isOk())
                .andExpect(header().exists("ETag")).andReturn());
        assertThat(str(p2, "minified")).isEqualTo("composer/2.0");
        Map<String, ObjectNode> entries = versions(p2, "acme/lib");
        assertThat(entries.keySet()).containsExactly("1.0.0", "0.9.0");
        // the private 1.0.0 shadows the public one with the same version
        assertThat(str(entries.get("1.0.0"), "description")).isEqualTo("Demo library acme/lib");
        assertThat(str(entries.get("1.0.0"), "dist", "reference")).isEqualTo(sha1(lib));
        // every archive is downloaded through the group
        assertThat(str(entries.get("1.0.0"), "dist", "url")).isEqualTo(repositoryUrl(GROUP) + "/dist/acme/lib/1.0.0/" + sha1(lib) + ".zip");
        assertThat(str(entries.get("0.9.0"), "dist", "url"))
                .isEqualTo(repositoryUrl(GROUP) + "/dist/acme/lib/0.9.0/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.zip");
        assertThat(str(entries.get("0.9.0"), "description")).isEqualTo("public release");

        // a package only the proxy knows comes through untouched apart from its dist URLs
        ObjectNode psr = object(composerGet(URL + "/p2/psr/log.json").andExpect(status().isOk()).andReturn());
        assertThat(versions(psr, "psr/log").keySet()).containsExactly("3.0.2", "3.0.1", "2.0.0");
        assertThat(str(versions(psr, "psr/log").get("3.0.1"), "dist", "url")).isEqualTo(repositoryUrl(GROUP) + "/dist/psr/log/3.0.1/" + REF_301 + ".zip");
        assertThat(psr.path("security-advisories").get(0).path("advisoryId").asString()).isEqualTo("PKSA-test-0001");

        // a package nobody has is a plain 404
        composerGet(URL + "/p2/acme/nothing.json").andExpect(status().isNotFound());
        composerGet(URL + "/p2/acme/lib~dev.json").andExpect(status().isNotFound());
    }

    @Test
    void archivesComeFromTheMemberThatOwnsTheVersion() throws Exception {
        ObjectNode p2 = object(composerGet(URL + "/p2/acme/lib.json").andReturn());
        Map<String, ObjectNode> entries = versions(p2, "acme/lib");
        assertThat(mvc.perform(get(URI.create(str(entries.get("1.0.0"), "dist", "url")))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(lib);
        mvc.perform(get(URI.create(str(entries.get("0.9.0"), "dist", "url")))).andExpect(status().isOk());
        // the impostor's archive was never asked for
        github.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/repos/acme/lib/zipball/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")));
        // the proxied archive is cached in the proxy, where every group containing it finds it again
        assertThat(storage.find(proxyRepo, "dist/acme/lib/0.9.0/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.zip")).isPresent();
        assertThat(storage.find(hostedRepo, "dist/acme/lib/0.9.0/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.zip")).isEmpty();

        MvcResult zip = composerGet(URL + "/dist/psr/log/3.0.2/" + REF_302 + ".zip").andExpect(status().isOk()).andReturn();
        assertThat(zip.getResponse().getContentAsByteArray()).isEqualTo(zip302);
        composerGet(URL + "/dist/psr/log/3.0.2/" + REF_302 + ".zip").andExpect(status().isOk());
        github.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/repos/php-fig/log/zipball/" + REF_302)));
        composerGet(URL + "/dist/psr/log/3.0.2/0000000000000000000000000000000000000000.zip").andExpect(status().isNotFound());
    }

    @Test
    void searchAndListMergeTheMembers() throws Exception {
        ObjectNode search = object(composerGet(URL + "/search.json?q=acme&type=").andExpect(status().isOk()).andReturn());
        // hosted matches first; the upstream's entry for the same name is not repeated
        assertThat(search.path("results").valueStream().map(r -> r.path("name").asString())).containsExactly("acme/lib", "acme/acme-client");
        assertThat(str(search.path("results").get(0), "url")).isEqualTo(repositoryUrl(GROUP) + "/p2/acme/lib.json");
        mvc.perform(get(URL + "/list.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.packageNames.length()").value(3))
                .andExpect(jsonPath("$.packageNames[0]").value("acme/acme-client"))
                .andExpect(jsonPath("$.packageNames[1]").value("acme/lib"));
    }

    @Test
    void advisoriesAndNotificationsWorkThroughTheGroup() throws Exception {
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/security-advisories/"))
                .willReturn(WireMock.okJson("{\"advisories\":{\"psr/log\":[{\"advisoryId\":\"PKSA-2\",\"packageName\":\"psr/log\"}]}}")));
        mvc.perform(post(URL + "/api/security-advisories").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(AUDIT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisories['psr/log'][0].advisoryId").value("PKSA-2"));
        mvc.perform(post(URL + "/downloads").contentType(MediaType.APPLICATION_JSON).content(ComposerFixtures.NOTIFY_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void writesToTheGroupNameTheHostedMember() throws Exception {
        mvc.perform(put(URL + "/packages/acme/lib/2.0.0").header("Authorization", adminBasic()).content(lib))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"))
                .andExpect(jsonPath("$.message", containsString("use its hosted member 'composer-hosted'")));
        mvc.perform(multipart(URL + "/packages").file(new MockMultipartFile("package", "lib.zip", "application/zip", lib))
                        .param("version", "2.0.0").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(URL + "/packages/acme/lib/1.0.0").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.message", containsString("composer-hosted")));
        mvc.perform(put(URL + "/packages/acme/lib/2.0.0").content(lib)).andExpect(status().isUnauthorized());
        composerGet("/repository/composer-hosted/p2/acme/lib.json").andExpect(status().isOk())
                .andExpect(jsonPath("$.packages['acme/lib'].length()").value(1));
    }

    @Test
    void offlineTheGroupServesWhatIsCachedAndEverythingHosted() throws Exception {
        composerGet(URL + "/packages.json").andExpect(status().isOk());
        composerGet(URL + "/p2/psr/log.json").andExpect(status().isOk());
        composerGet(URL + "/dist/psr/log/3.0.2/" + REF_302 + ".zip").andExpect(status().isOk());
        // packagist was asked about the private package once and said it does not know it
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/p2/acme/private.json")).willReturn(WireMock.aResponse().withStatus(404)));
        mvc.perform(put("/repository/composer-hosted/packages/acme/private/1.0.0").header("Authorization", adminBasic())
                .content(archive(composerJson("acme/private", null, null)))).andExpect(status().isCreated());
        composerGet(URL + "/p2/acme/private.json").andExpect(status().isOk());

        settings.setGlobalOffline(true);
        packagist.resetRequests();
        github.resetRequests();

        composerGet(URL + "/packages.json").andExpect(status().isOk());
        assertThat(versions(object(composerGet(URL + "/p2/psr/log.json").andExpect(status().isOk()).andReturn()), "psr/log")).hasSize(3);
        composerGet(URL + "/dist/psr/log/3.0.2/" + REF_302 + ".zip").andExpect(status().isOk());
        // hosted packages need no upstream, also those published while offline
        mvc.perform(put("/repository/composer-hosted/packages/acme/offline/1.0.0").header("Authorization", adminBasic())
                .content(archive(composerJson("acme/offline", null, null)))).andExpect(status().isCreated());
        composerGet(URL + "/p2/acme/offline.json").andExpect(status().isOk())
                .andExpect(jsonPath("$.packages['acme/offline'][0].version").value("1.0.0"));
        ObjectNode merged = object(composerGet(URL + "/p2/acme/lib.json").andExpect(status().isOk()).andReturn());
        assertThat(versions(merged, "acme/lib").keySet()).contains("1.0.0");
        mvc.perform(get(URI.create(str(versions(merged, "acme/lib").get("1.0.0"), "dist", "url")))).andExpect(status().isOk());
        // composer show -a also asks for the branches; upstream's earlier 404 answers for them
        composerGet(URL + "/p2/acme/private~dev.json").andExpect(status().isOk()).andExpect(jsonPath("$.packages").isEmpty());

        // what was never cached is a 503 Composer reports, not a silent "no such package"
        composerGet(URL + "/p2/monolog/monolog.json").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        composerGet(URL + "/dist/psr/log/3.0.1/" + REF_301 + ".zip").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        packagist.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
        github.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void anUpstreamChangeIsNotHiddenBehindANewerPrivateRelease() throws Exception {
        // the upstream document as Granary first caches it, with packagist's Last-Modified from long ago
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/p2/acme/lib.json")).atPriority(1)
                .willReturn(WireMock.okJson(upstreamAcmeLib(github.baseUrl())).withHeader("Last-Modified", "Wed, 11 Sep 2024 13:20:00 GMT")));
        composerGet(URL + "/p2/acme/lib.json").andExpect(status().isOk());

        // upstream releases 0.9.5 while Granary's cached copy is still fresh
        Thread.sleep(1100);
        String released = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
        String ref = "dddddddddddddddddddddddddddddddddddddddd";
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/p2/acme/lib.json")).atPriority(1)
                .willReturn(WireMock.okJson("{\"minified\":\"composer/2.0\",\"packages\":{\"acme/lib\":["
                        + "{\"name\":\"acme/lib\",\"description\":\"public release\",\"version\":\"0.9.5\",\"version_normalized\":\"0.9.5.0\","
                        + "\"dist\":{\"url\":\"" + github.baseUrl() + "/repos/acme/lib/zipball/" + ref + "\",\"type\":\"zip\",\"shasum\":\"\","
                        + "\"reference\":\"" + ref + "\"},\"type\":\"library\"},"
                        + "{\"version\":\"0.9.0\",\"version_normalized\":\"0.9.0.0\","
                        + "\"dist\":{\"url\":\"" + github.baseUrl() + "/repos/acme/lib/zipball/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\","
                        + "\"type\":\"zip\",\"shasum\":\"\",\"reference\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}}]}}")
                        .withHeader("Last-Modified", released)));
        // a private release a moment later makes the merged document newer than the upstream change
        Thread.sleep(1100);
        mvc.perform(put("/repository/composer-hosted/packages/acme/lib/1.1.0").header("Authorization", adminBasic())
                .content(archive(composerJson("acme/lib", null, null).replace("Demo", "Next")))).andExpect(status().isCreated());
        nextSecond();
        MvcResult seen = composerGet(URL + "/p2/acme/lib.json").andExpect(status().isOk()).andReturn();
        assertThat(versions(object(seen), "acme/lib").keySet()).containsExactly("1.1.0", "1.0.0", "0.9.0");
        String lastModified = seen.getResponse().getHeader("Last-Modified");
        assertThat(lastModified).isNotNull();

        // once the cached copy is due, Granary picks 0.9.5 up, and Composer's next update (If-Modified-Since with
        // the Last-Modified it kept) must get the new document instead of a 304
        Asset cached = storage.find(proxyRepo, "p2/acme/lib.json").orElseThrow().asset();
        cached.setLastVerifiedAt(Instant.EPOCH);
        storage.save(cached);
        MvcResult next = mvc.perform(get(URL + "/p2/acme/lib.json").header("If-Modified-Since", lastModified))
                .andExpect(status().isOk()).andReturn();
        assertThat(versions(object(next), "acme/lib").keySet()).containsExactly("1.1.0", "1.0.0", "0.9.5", "0.9.0");
        assertThat(next.getResponse().getHeader("Last-Modified")).isNotEqualTo(lastModified);
        // and with nothing new, the conditional request is answered with 304 again
        nextSecond();
        String current = composerGet(URL + "/p2/acme/lib.json").andExpect(status().isOk()).andReturn().getResponse().getHeader("Last-Modified");
        assertThat(current).isNotNull().isNotEqualTo(lastModified);
        mvc.perform(get(URL + "/p2/acme/lib.json").header("If-Modified-Since", current)).andExpect(status().isNotModified());
    }

    @Test
    void nestedGroupsAndConditionalRequestsWork() throws Exception {
        group("composer-all", "composer", GROUP);
        ObjectNode p2 = object(composerGet("/repository/composer-all/p2/acme/lib.json").andExpect(status().isOk()).andReturn());
        String dist = str(versions(p2, "acme/lib").get("1.0.0"), "dist", "url");
        assertThat(dist).isEqualTo(repositoryUrl("composer-all") + "/dist/acme/lib/1.0.0/" + sha1(lib) + ".zip");
        mvc.perform(get(URI.create(dist))).andExpect(status().isOk());

        nextSecond();
        MvcResult first = composerGet(URL + "/p2/acme/lib.json").andExpect(status().isOk()).andReturn();
        String lastModified = first.getResponse().getHeader("Last-Modified");
        assertThat(lastModified).isNotNull();
        mvc.perform(get(URL + "/p2/acme/lib.json").header("If-Modified-Since", lastModified)).andExpect(status().isNotModified());
        mvc.perform(get(URL + "/p2/acme/lib.json").header("If-None-Match", first.getResponse().getHeader("ETag")))
                .andExpect(status().isNotModified());

        // a new private release changes the merged document
        Thread.sleep(1100);
        mvc.perform(put("/repository/composer-hosted/packages/acme/lib/1.1.0").header("Authorization", adminBasic())
                .content(archive(composerJson("acme/lib", null, null).replace("Demo", "Next")))).andExpect(status().isCreated());
        ObjectNode after = object(mvc.perform(get(URL + "/p2/acme/lib.json").header("If-Modified-Since", lastModified))
                .andExpect(status().isOk()).andReturn());
        assertThat(versions(after, "acme/lib").keySet()).containsExactly("1.1.0", "1.0.0", "0.9.0");
        JsonNode root = object(composerGet("/repository/composer-all/packages.json").andReturn());
        assertThat(str(root, "metadata-url")).isEqualTo(repositoryUrl("composer-all") + "/p2/%package%.json");
    }
}
