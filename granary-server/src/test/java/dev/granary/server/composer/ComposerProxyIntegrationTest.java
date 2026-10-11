package dev.granary.server.composer;

import static dev.granary.server.composer.ComposerFixtures.AUDIT_BODY;
import static dev.granary.server.composer.ComposerFixtures.REF_200;
import static dev.granary.server.composer.ComposerFixtures.REF_301;
import static dev.granary.server.composer.ComposerFixtures.REF_302;
import static dev.granary.server.composer.ComposerFixtures.USER_AGENT;
import static dev.granary.server.composer.ComposerFixtures.archive;
import static dev.granary.server.composer.ComposerFixtures.composerJson;
import static dev.granary.server.composer.ComposerFixtures.githubZipball;
import static dev.granary.server.composer.ComposerFixtures.nextSecond;
import static dev.granary.server.composer.ComposerFixtures.object;
import static dev.granary.server.composer.ComposerFixtures.repositoryUrl;
import static dev.granary.server.composer.ComposerFixtures.str;
import static dev.granary.server.composer.ComposerFixtures.upstreamPsrLog;
import static dev.granary.server.composer.ComposerFixtures.upstreamPsrLogDev;
import static dev.granary.server.composer.ComposerFixtures.upstreamRoot;
import static dev.granary.server.composer.ComposerFixtures.upstreamSearch;
import static dev.granary.server.composer.ComposerFixtures.versions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A proxy in front of a fake packagist.org whose dist archives live on a fake GitHub: caching, link rewriting,
 * GitHub's zipball redirect, and the offline-first fallbacks.
 */
class ComposerProxyIntegrationTest extends IntegrationTest {

    private static final String REPO = "packagist";
    private static final String URL = "/repository/" + REPO;
    private static final WireMockServer packagist = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer github = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final String LAST_MODIFIED = "Wed, 11 Sep 2024 13:20:00 GMT";

    @Autowired
    private StorageService storage;

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private ComponentDao components;

    private Repository repo;
    private final byte[] zip302 = githubZipball("psr/log", "3.0.2", REF_302);
    private final byte[] zip301 = githubZipball("psr/log", "3.0.1", REF_301);

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
    void createRepository() {
        packagist.resetAll();
        github.resetAll();
        repo = createProxy(REPO, null, Map.of());
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/packages.json"))
                .willReturn(WireMock.okJson(upstreamRoot(packagist.baseUrl()))));
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/p2/psr/log.json"))
                .willReturn(WireMock.okJson(upstreamPsrLog(github.baseUrl())).withHeader("Last-Modified", LAST_MODIFIED)));
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/p2/psr/log~dev.json"))
                .willReturn(WireMock.okJson(upstreamPsrLogDev(github.baseUrl()))));
        packagist.stubFor(WireMock.get(WireMock.urlPathMatching("/p2/acme/.*"))
                .willReturn(WireMock.aResponse().withStatus(404).withHeader("Content-Type", "text/html").withBody("<h1>404</h1>")));
        stubZipball(REF_302, zip302);
        stubZipball(REF_301, zip301);
    }

    /** api.github.com answers a zipball request with a redirect to codeload.github.com, which serves the archive. */
    private static void stubZipball(String reference, byte[] zip) {
        github.stubFor(WireMock.get(WireMock.urlPathEqualTo("/repos/php-fig/log/zipball/" + reference))
                .willReturn(WireMock.aResponse().withStatus(302)
                        .withHeader("Location", github.baseUrl() + "/php-fig/log/legacy.zip/" + reference)));
        github.stubFor(WireMock.get(WireMock.urlPathEqualTo("/php-fig/log/legacy.zip/" + reference))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/zip")
                        .withHeader("Content-Disposition", "attachment; filename=php-fig-log-" + reference.substring(0, 7) + ".zip")
                        .withBody(zip)));
    }

    private Repository createProxy(String name, Integer metadataMaxAgeMinutes, Map<String, String> settings) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat("composer");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(packagist.baseUrl() + "/");
        r.setMetadataMaxAgeMinutes(metadataMaxAgeMinutes);
        r.setFormatSettings(settings);
        Repository created = repositories.create(r);
        proxyService.invalidate(created);
        return created;
    }

    private ResultActions composerGet(String url) throws Exception {
        return mvc.perform(get(url).header("User-Agent", USER_AGENT).accept(MediaType.ALL));
    }

    private static String distPath(String version, String reference) {
        return "/dist/psr/log/" + version + "/" + reference + ".zip";
    }

    @Test
    void theRootDocumentPointsEverythingAtGranary() throws Exception {
        ObjectNode root = object(composerGet(URL + "/packages.json").andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn());
        String base = repositoryUrl(REPO);
        assertThat(str(root, "metadata-url")).isEqualTo(base + "/p2/%package%.json");
        assertThat(str(root, "notify-batch")).isEqualTo(base + "/downloads");
        assertThat(str(root, "search")).isEqualTo(base + "/search.json?q=%query%&type=%type%");
        assertThat(str(root, "list")).isEqualTo(base + "/list.json");
        assertThat(str(root, "providers-api")).isEqualTo(base + "/providers/%package%.json");
        assertThat(str(root, "security-advisories", "api-url")).isEqualTo(base + "/api/security-advisories");
        assertThat(root.path("security-advisories").path("metadata").booleanValue()).isFalse();
        assertThat(str(root, "warning")).isEqualTo("Support for Composer 1 has been shutdown");
        assertThat(str(root, "warning-versions")).isEqualTo("<1.999");
        // Composer 1 fields, change feeds and filter lists would send clients past Granary
        for (String field : new String[] {"providers-url", "provider-includes", "providers-lazy-url", "metadata-changes-url",
                "available-package-patterns", "available-packages", "filter", "providers"}) {
            assertThat(root.has(field)).as(field).isFalse();
        }
        assertThat(root.toString()).doesNotContain(packagist.baseUrl());

        composerGet(URL + "/packages.json").andExpect(status().isOk());
        composerGet(URL + "/").andExpect(status().isOk()).andExpect(jsonPath("$.metadata-url").value(base + "/p2/%package%.json"));
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/packages.json")));
    }

    @Test
    void metadataIsCachedAndDistUrlsPointAtTheProxy() throws Exception {
        ObjectNode p2 = object(composerGet(URL + "/p2/psr/log.json").andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().exists("ETag"))
                .andReturn());
        assertThat(str(p2, "minified")).isEqualTo("composer/2.0");
        Map<String, ObjectNode> entries = versions(p2, "psr/log");
        assertThat(entries.keySet()).containsExactly("3.0.2", "3.0.1", "2.0.0");
        ObjectNode latest = entries.get("3.0.2");
        assertThat(str(latest, "dist", "url")).isEqualTo(repositoryUrl(REPO) + distPath("3.0.2", REF_302));
        assertThat(str(latest, "dist", "reference")).isEqualTo(REF_302);
        assertThat(str(latest, "dist", "type")).isEqualTo("zip");
        assertThat(latest.path("dist").has("mirrors")).isFalse();
        assertThat(latest.has("notification-url")).isFalse();
        // git sources stay where they are; Composer prefers the dist
        assertThat(str(latest, "source", "url")).isEqualTo("https://github.com/php-fig/log.git");
        assertThat(str(entries.get("3.0.1"), "description")).isEqualTo("Common interface for logging libraries");
        assertThat(str(entries.get("3.0.1"), "dist", "url")).isEqualTo(repositoryUrl(REPO) + distPath("3.0.1", REF_301));
        assertThat(entries.get("2.0.0").has("extra")).isFalse();
        assertThat(str(entries.get("2.0.0"), "autoload", "psr-4", "Psr\\Log\\")).isEqualTo("src/");
        assertThat(p2.path("security-advisories").get(0).path("advisoryId").asString()).isEqualTo("PKSA-test-0001");
        assertThat(p2.toString()).doesNotContain(github.baseUrl());

        // the upstream document is what is cached; the rewrite happens on the way out
        assertThat(storage.find(repo, "p2/psr/log.json").orElseThrow().readUtf8()).contains(github.baseUrl() + "/repos/php-fig/log/zipball/");
        assertThat(storage.find(repo, "p2/psr/log.json").orElseThrow().asset().isMetadata()).isTrue();
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/p2/psr/log.json"))
                .withHeader("Accept", WireMock.equalTo("application/json")));

        // fresh metadata is served from the cache, and the branches have a document of their own; Last-Modified is
        // when Granary's copy changed, sent once that second is over
        nextSecond();
        String lastModified = composerGet(URL + "/p2/psr/log.json").andExpect(status().isOk())
                .andExpect(header().exists("Last-Modified")).andReturn().getResponse().getHeader("Last-Modified");
        assertThat(lastModified).isNotEqualTo(LAST_MODIFIED);
        mvc.perform(get(URL + "/p2/psr/log.json").header("If-Modified-Since", lastModified)).andExpect(status().isNotModified());
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/p2/psr/log.json")));
        ObjectNode dev = object(composerGet(URL + "/p2/psr/log~dev.json").andExpect(status().isOk()).andReturn());
        assertThat(str(versions(dev, "psr/log").get("dev-master"), "dist", "url")).isEqualTo(repositoryUrl(REPO) + distPath("dev-master", REF_302));
    }

    @Test
    void metadataIsRevalidatedAfterTheMaximumAge() throws Exception {
        createProxy("packagist-eager", 0, Map.of());
        String url = "/repository/packagist-eager/p2/psr/log.json";
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/p2/psr/log.json"))
                .withHeader("If-Modified-Since", WireMock.equalTo(LAST_MODIFIED))
                .atPriority(1)
                .willReturn(WireMock.aResponse().withStatus(304)));

        ObjectNode first = object(composerGet(url).andExpect(status().isOk()).andReturn());
        ObjectNode second = object(composerGet(url).andExpect(status().isOk()).andReturn());
        assertThat(second).isEqualTo(first);
        packagist.verify(2, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/p2/psr/log.json")));
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/p2/psr/log.json"))
                .withHeader("If-Modified-Since", WireMock.equalTo(LAST_MODIFIED)));
    }

    @Test
    void distArchivesFollowGithubsRedirectAndAreFetchedOnce() throws Exception {
        composerGet(URL + "/p2/psr/log.json").andExpect(status().isOk());
        for (int i = 0; i < 2; i++) {
            assertThat(composerGet(URL + distPath("3.0.2", REF_302))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("application/zip"))
                    .andExpect(header().longValue("Content-Length", zip302.length))
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + REF_302 + ".zip\""))
                    .andExpect(header().exists("ETag"))
                    .andReturn().getResponse().getContentAsByteArray()).isEqualTo(zip302);
        }
        github.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/repos/php-fig/log/zipball/" + REF_302))
                .withHeader("User-Agent", WireMock.matching(".+")));
        github.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/php-fig/log/legacy.zip/" + REF_302)));
        assertThat(storage.find(repo, "dist/psr/log/3.0.2/" + REF_302 + ".zip")).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isFalse();
            assertThat(c.asset().getDownloadCount()).isEqualTo(2);
        });
        var component = components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "psr", "log", "3.0.2");
        assertThat(component).isPresent();
        assertThat(storage.find(repo, "dist/psr/log/3.0.2/" + REF_302 + ".zip"))
                .hasValueSatisfying(c -> assertThat(c.asset().getComponentId()).isEqualTo(component.get().getId()));
        assertThat(storage.find(repo, "p2/psr/log.json")).hasValueSatisfying(c -> assertThat(c.asset().getComponentId()).isNull());
    }

    @Test
    void anArchiveServedWithAnUnusableContentTypeIsStillServedAsAZip() throws Exception {
        // a forge (or a proxy in front of it) that labels the archive with something that is not a media type
        github.stubFor(WireMock.get(WireMock.urlPathEqualTo("/php-fig/log/legacy.zip/" + REF_301)).atPriority(1)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "zip").withBody(zip301)));
        for (int i = 0; i < 2; i++) {
            assertThat(composerGet(URL + distPath("3.0.1", REF_301))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("application/zip"))
                    .andExpect(header().longValue("Content-Length", zip301.length))
                    .andReturn().getResponse().getContentAsByteArray()).isEqualTo(zip301);
        }
        mvc.perform(head(URL + distPath("3.0.1", REF_301))).andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"));
        github.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/php-fig/log/legacy.zip/" + REF_301)));
    }

    @Test
    void aBranchWhoseNameCannotBeAUrlPathKeepsItsOriginalDistUrl() throws Exception {
        String ref = "cccccccccccccccccccccccccccccccccccccccc";
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/p2/psr/log~dev.json")).atPriority(1)
                .willReturn(WireMock.okJson("{\"minified\":\"composer/2.0\",\"packages\":{\"psr/log\":["
                        + "{\"name\":\"psr/log\",\"version\":\"dev-fix;quote\",\"version_normalized\":\"dev-fix;quote\","
                        + "\"dist\":{\"url\":\"" + github.baseUrl() + "/repos/php-fig/log/zipball/" + ref + "\",\"type\":\"zip\","
                        + "\"shasum\":\"\",\"reference\":\"" + ref + "\"}},"
                        + "{\"version\":\"dev-master\",\"version_normalized\":\"dev-master\","
                        + "\"dist\":{\"url\":\"" + github.baseUrl() + "/repos/php-fig/log/zipball/" + REF_302 + "\",\"type\":\"zip\","
                        + "\"shasum\":\"\",\"reference\":\"" + REF_302 + "\"}}]}}")));
        Map<String, ObjectNode> entries = versions(object(composerGet(URL + "/p2/psr/log~dev.json").andExpect(status().isOk()).andReturn()), "psr/log");
        // a semicolon would make the Granary URL unreachable (the HTTP firewall rejects path parameters)
        assertThat(str(entries.get("dev-fix;quote"), "dist", "url")).isEqualTo(github.baseUrl() + "/repos/php-fig/log/zipball/" + ref);
        assertThat(str(entries.get("dev-master"), "dist", "url")).isEqualTo(repositoryUrl(REPO) + distPath("dev-master", REF_302));
    }

    @Test
    void anArchiveCanBeFetchedBeforeItsMetadataWasEverAskedFor() throws Exception {
        // composer install from a lock file goes straight to the dist URL
        assertThat(composerGet(URL + distPath("3.0.1", REF_301)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(zip301);
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/p2/psr/log.json")));
        // a branch archive is found through the ~dev document
        assertThat(composerGet(URL + distPath("dev-master", REF_302)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(zip302);
    }

    @Test
    void archivesTheMetadataDoesNotNameAreNotFound() throws Exception {
        composerGet(URL + distPath("3.0.2", "0000000000000000000000000000000000000000")).andExpect(status().isNotFound());
        composerGet(URL + distPath("9.9.9", REF_302)).andExpect(status().isNotFound());
        composerGet(URL + "/dist/acme/nope/1.0.0/" + REF_302 + ".zip").andExpect(status().isNotFound());
        github.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
        // an archive GitHub no longer has is a 404 that is remembered
        github.stubFor(WireMock.get(WireMock.urlPathEqualTo("/repos/php-fig/log/zipball/" + REF_200))
                .willReturn(WireMock.aResponse().withStatus(404)));
        composerGet(URL + distPath("2.0.0", REF_200)).andExpect(status().isNotFound());
        composerGet(URL + distPath("2.0.0", REF_200)).andExpect(status().isNotFound());
        github.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/repos/php-fig/log/zipball/" + REF_200)));
    }

    @Test
    void staleCacheIsServedWhenUpstreamBreaks() throws Exception {
        createProxy("packagist-stale", 0, Map.of());
        String url = "/repository/packagist-stale";
        composerGet(url + "/packages.json").andExpect(status().isOk());
        composerGet(url + "/p2/psr/log.json").andExpect(status().isOk());
        composerGet(url + distPath("3.0.2", REF_302)).andExpect(status().isOk());

        packagist.resetAll();
        github.resetAll();
        packagist.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));
        github.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));

        composerGet(url + "/packages.json").andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata-url").value(repositoryUrl("packagist-stale") + "/p2/%package%.json"));
        ObjectNode p2 = object(composerGet(url + "/p2/psr/log.json").andExpect(status().isOk()).andReturn());
        assertThat(versions(p2, "psr/log")).hasSize(3);
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/p2/psr/log.json"))); // it did try
        assertThat(composerGet(url + distPath("3.0.2", REF_302)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(zip302);
        github.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));

        // something never cached while upstream is broken is a temporary 503 Composer retries, not a 404 it believes
        composerGet(url + "/p2/monolog/monolog.json").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        // an archive whose host is failing is a 503 as well, naming the host
        composerGet(url + distPath("3.0.1", REF_301)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"))
                .andExpect(jsonPath("$.message").value(containsString(github.baseUrl())));
    }

    @Test
    void offlineModeNeverTouchesUpstream() throws Exception {
        composerGet(URL + "/packages.json").andExpect(status().isOk());
        composerGet(URL + "/p2/psr/log.json").andExpect(status().isOk());
        composerGet(URL + distPath("3.0.2", REF_302)).andExpect(status().isOk());

        repositories.setOffline(REPO, true);
        packagist.resetRequests();
        github.resetRequests();

        composerGet(URL + "/packages.json").andExpect(status().isOk());
        composerGet(URL + "/p2/psr/log.json").andExpect(status().isOk());
        composerGet(URL + distPath("3.0.2", REF_302)).andExpect(status().isOk());
        composerGet(URL + "/p2/monolog/monolog.json").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        // the metadata names 3.0.1, but its archive was never downloaded
        composerGet(URL + distPath("3.0.1", REF_301)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        packagist.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
        github.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));

        repositories.setOffline(REPO, false);
        composerGet(URL + distPath("3.0.1", REF_301)).andExpect(status().isOk());

        // the global switch behaves the same way
        settings.setGlobalOffline(true);
        composerGet(URL + distPath("3.0.1", REF_301)).andExpect(status().isOk());
        composerGet(URL + "/p2/symfony/console.json").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
    }

    @Test
    void anUpstream404IsRememberedAndStillKnownOffline() throws Exception {
        for (int i = 0; i < 3; i++) {
            composerGet(URL + "/p2/acme/nope.json").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("content.notFound"));
        }
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/p2/acme/nope.json")));

        // offline, "upstream does not have it" is still an answer: an empty document instead of a 503
        repositories.setOffline(REPO, true);
        composerGet(URL + "/p2/acme/nope.json").andExpect(status().isOk())
                .andExpect(jsonPath("$.packages").isEmpty());
        // and the branches of a package upstream does not have do not exist either
        composerGet(URL + "/p2/acme/nope~dev.json").andExpect(status().isOk())
                .andExpect(jsonPath("$.packages").isEmpty());
        composerGet(URL + "/p2/acme/never-asked.json").andExpect(status().isServiceUnavailable());
    }

    @Test
    void distRewriteCanBeTurnedOff() throws Exception {
        createProxy("packagist-direct", null, Map.of("composer.distRewrite", "false"));
        ObjectNode p2 = object(composerGet("/repository/packagist-direct/p2/psr/log.json").andExpect(status().isOk()).andReturn());
        ObjectNode latest = versions(p2, "psr/log").get("3.0.2");
        assertThat(str(latest, "dist", "url")).isEqualTo(github.baseUrl() + "/repos/php-fig/log/zipball/" + REF_302);
        assertThat(latest.path("dist").has("mirrors")).isTrue();

        // an invalid value for the setting is refused when the repository is saved
        Repository bad = new Repository();
        bad.setName("packagist-bad");
        bad.setFormat("composer");
        bad.setType(RepositoryType.PROXY);
        bad.setRemoteUrl(packagist.baseUrl() + "/");
        bad.setFormatSettings(Map.of("composer.distRewrite", "sometimes"));
        assertThatThrownBy(() -> repositories.create(bad))
                .hasMessageContaining("composer.distRewrite");
    }

    @Test
    void searchListAndProvidersArePassedThroughAndCached() throws Exception {
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/search.json"))
                .willReturn(WireMock.okJson(upstreamSearch("psr/log", "monolog/monolog"))));
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/packages/list.json"))
                .willReturn(WireMock.okJson("{\"packageNames\":[\"psr/log\",\"psr/cache\"]}")));
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/providers/psr/log-implementation.json"))
                .willReturn(WireMock.okJson("{\"providers\":[{\"name\":\"monolog/monolog\",\"description\":\"Logging\",\"type\":\"library\"}]}")));

        for (int i = 0; i < 2; i++) {
            composerGet(URL + "/search.json?q=log&type=").andExpect(status().isOk())
                    .andExpect(jsonPath("$.results[0].name").value("psr/log"))
                    .andExpect(jsonPath("$.results[1].name").value("monolog/monolog"))
                    .andExpect(jsonPath("$.total").value(2));
        }
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/search.json"))
                .withQueryParam("q", WireMock.equalTo("log")));
        composerGet(URL + "/search.json?q=log&type=library").andExpect(status().isOk());
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/search.json"))
                .withQueryParam("type", WireMock.equalTo("library")));

        composerGet(URL + "/list.json?vendor=psr").andExpect(status().isOk())
                .andExpect(jsonPath("$.packageNames[0]").value("psr/cache"));
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/packages/list.json"))
                .withQueryParam("vendor", WireMock.equalTo("psr")));
        composerGet(URL + "/providers/psr/log-implementation.json").andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].name").value("monolog/monolog"));

        // offline, the answers given before are given again
        repositories.setOffline(REPO, true);
        composerGet(URL + "/search.json?q=log&type=").andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        composerGet(URL + "/search.json?q=never&type=").andExpect(status().isServiceUnavailable());
    }

    @Test
    void securityAdvisoriesComeFromTheUpstreamApi() throws Exception {
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/security-advisories/"))
                .willReturn(WireMock.okJson("{\"advisories\":{\"psr/log\":[{\"advisoryId\":\"PKSA-1\",\"packageName\":\"psr/log\","
                        + "\"affectedVersions\":\">=1.0,<1.1\",\"title\":\"Test advisory\",\"cve\":null}]}}")));

        // composer audit posts the installed package names as a PHP form array
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(URL + "/api/security-advisories").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content(AUDIT_BODY).header("User-Agent", USER_AGENT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.advisories['psr/log'][0].advisoryId").value("PKSA-1"))
                    .andExpect(jsonPath("$.advisories['acme/lib']").isEmpty());
        }
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/security-advisories/"))
                .withQueryParam("packages[]", WireMock.havingExactly("psr/log", "acme/lib")));
        // the GET form works too
        mvc.perform(get(URL + "/api/security-advisories").param("packages[]", "psr/log")).andExpect(status().isOk())
                .andExpect(jsonPath("$.advisories['psr/log'][0].title").value("Test advisory"));

        repositories.setOffline(REPO, true);
        mvc.perform(post(URL + "/api/security-advisories").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(AUDIT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisories['psr/log'][0].advisoryId").value("PKSA-1"));
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/security-advisories/")));
    }

    @Test
    void credentialsOnlyGoToTheUpstreamItself() throws Exception {
        Repository r = new Repository();
        r.setName("packagist-private");
        r.setFormat("composer");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(packagist.baseUrl() + "/");
        r.setRemoteUsername("mirror");
        r.setRemotePassword("s3cret");
        proxyService.invalidate(repositories.create(r));

        composerGet("/repository/packagist-private/p2/psr/log.json").andExpect(status().isOk());
        composerGet("/repository/packagist-private" + distPath("3.0.2", REF_302)).andExpect(status().isOk());
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/p2/psr/log.json"))
                .withHeader("Authorization", WireMock.equalTo(basic("mirror", "s3cret"))));
        github.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/repos/php-fig/log/zipball/" + REF_302))
                .withoutHeader("Authorization"));
    }

    @Test
    void anArchiveHostListedInTheSettingReceivesTheCredentials() throws Exception {
        Repository r = new Repository();
        r.setName("packagist-trusting");
        r.setFormat("composer");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(packagist.baseUrl() + "/");
        r.setRemoteUsername("mirror");
        r.setRemotePassword("s3cret");
        r.setFormatSettings(Map.of("upstream.credentialHosts", "localhost:" + github.port()));
        proxyService.invalidate(repositories.create(r));

        composerGet("/repository/packagist-trusting/p2/psr/log.json").andExpect(status().isOk());
        composerGet("/repository/packagist-trusting" + distPath("3.0.2", REF_302)).andExpect(status().isOk());
        github.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/repos/php-fig/log/zipball/" + REF_302))
                .withHeader("Authorization", WireMock.equalTo(basic("mirror", "s3cret"))));
    }

    @Test
    void archivesOnTheUpstreamItselfAreFetchedWithItsCredentials() throws Exception {
        // another Granary or a private Packagist: the archives live next to the metadata
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/p2/acme/lib.json")).atPriority(1)
                .willReturn(WireMock.okJson("{\"minified\":\"composer/2.0\",\"packages\":{\"acme/lib\":[{\"name\":\"acme/lib\","
                        + "\"version\":\"1.0.0\",\"version_normalized\":\"1.0.0.0\",\"dist\":{\"type\":\"zip\",\"url\":\""
                        + packagist.baseUrl() + "/dist/acme/lib/1.0.0/abc.zip\",\"reference\":\"abc\",\"shasum\":\"abc\"}}]}}")));
        byte[] lib = archive(composerJson("acme/lib", null, null));
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/dist/acme/lib/1.0.0/abc.zip"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/zip").withBody(lib)));
        Repository r = new Repository();
        r.setName("granary-upstream");
        r.setFormat("composer");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(packagist.baseUrl() + "/");
        r.setRemoteToken("gry_upstream_token");
        proxyService.invalidate(repositories.create(r));

        ObjectNode p2 = object(composerGet("/repository/granary-upstream/p2/acme/lib.json").andExpect(status().isOk()).andReturn());
        String dist = str(versions(p2, "acme/lib").get("1.0.0"), "dist", "url");
        assertThat(dist).isEqualTo(repositoryUrl("granary-upstream") + "/dist/acme/lib/1.0.0/abc.zip");
        assertThat(mvc.perform(get(URI.create(dist))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(lib);
        packagist.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/dist/acme/lib/1.0.0/abc.zip"))
                .withHeader("Authorization", WireMock.equalTo("Bearer gry_upstream_token")));
    }

    @Test
    void aBrokenUpstreamDocumentIsNotAServerError() throws Exception {
        packagist.stubFor(WireMock.get(WireMock.urlPathEqualTo("/p2/broken/pkg.json"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody("<html>maintenance</html>")));
        int code = composerGet(URL + "/p2/broken/pkg.json").andReturn().getResponse().getStatus();
        assertThat(code).isIn(404, 503);
        composerGet(URL + "/p2/psr/log.json").andExpect(status().isOk());
    }

    @Test
    void writesAreRefused() throws Exception {
        mvc.perform(put(URL + "/packages/acme/lib/1.0.0").header("Authorization", adminBasic()).content(archive(composerJson("acme/lib", null, null))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"))
                .andExpect(jsonPath("$.message").value(containsString("proxy repository")));
        mvc.perform(delete(URL + "/packages/psr/log/3.0.2").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"));
        packagist.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void installNotificationsStayInGranary() throws Exception {
        mvc.perform(post(URL + "/downloads").contentType(MediaType.APPLICATION_JSON).content(ComposerFixtures.NOTIFY_BODY))
                .andExpect(status().isCreated());
        packagist.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
        JsonNode root = object(composerGet(URL + "/packages.json").andReturn());
        assertThat(str(root, "notify-batch")).startsWith(repositoryUrl(REPO));
    }
}
