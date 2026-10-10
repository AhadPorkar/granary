package dev.granary.server.terraform;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static dev.granary.server.terraform.TerraformFixtures.HASHICORP_KEY_ID;
import static dev.granary.server.terraform.TerraformFixtures.archiveName;
import static dev.granary.server.terraform.TerraformFixtures.moduleTgz;
import static dev.granary.server.terraform.TerraformFixtures.object;
import static dev.granary.server.terraform.TerraformFixtures.providerZip;
import static dev.granary.server.terraform.TerraformFixtures.repositoryUrl;
import static dev.granary.server.terraform.TerraformFixtures.sha256;
import static dev.granary.server.terraform.TerraformFixtures.shaSums;
import static dev.granary.server.terraform.TerraformFixtures.signature;
import static dev.granary.server.terraform.TerraformFixtures.str;
import static dev.granary.server.terraform.TerraformFixtures.upstreamDownload;
import static dev.granary.server.terraform.TerraformFixtures.upstreamModuleVersions;
import static dev.granary.server.terraform.TerraformFixtures.upstreamVersions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.node.ObjectNode;

/**
 * A proxy in front of a WireMock fake of registry.terraform.io whose release files live on a second
 * "host" (another path of the same WireMock), the way HashiCorp's do on releases.hashicorp.com.
 */
class TerraformProxyIntegrationTest extends IntegrationTest {

    private static final String REPO = "terraform-registry";
    private static final String BASE = repositoryUrl(REPO);
    private static final String ZIP = archiveName("null", "3.2.4", "linux", "amd64");
    private static final String SUMS_FILE = "terraform-provider-null_3.2.4_SHA256SUMS";
    private static final byte[] NULL_ZIP = providerZip("upstream-3.2.4");
    private static final String SUMS = shaSums(ZIP, NULL_ZIP);
    private static final byte[] CONSUL_TGZ = moduleTgz("consul-0.11.0");

    private static WireMockServer upstream;
    private static String upstreamBase;
    private static String releases;

    @Autowired
    private StorageService storage;

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private ComponentDao components;

    private Repository repo;

    @BeforeAll
    static void startUpstream() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        upstreamBase = upstream.baseUrl();
        releases = upstreamBase + "/releases";
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void setUp() {
        upstream.resetAll();
        stubHealthyUpstream();
        repo = proxy(REPO, "terraform", upstreamBase + "/");
        proxyService.invalidate(repo);
    }

    private static void stubHealthyUpstream() {
        upstream.stubFor(get(urlEqualTo("/.well-known/terraform.json"))
                .willReturn(okJson("{\"modules.v1\":\"/v1/modules/\",\"providers.v1\":\"/v1/providers/\"}")));
        upstream.stubFor(get(urlEqualTo("/v1/providers/hashicorp/null/versions"))
                .willReturn(okJson(upstreamVersions("hashicorp", "null", "3.2.4:linux_amd64,darwin_arm64", "3.2.3:linux_amd64"))));
        upstream.stubFor(get(urlEqualTo("/v1/providers/hashicorp/null/3.2.4/download/linux/amd64"))
                .willReturn(okJson(upstreamDownload(releases, "null", "3.2.4", "linux", "amd64", sha256(NULL_ZIP)))));
        upstream.stubFor(get(urlEqualTo("/v1/providers/hashicorp/null/3.2.4/download/darwin/arm64"))
                .willReturn(okJson(upstreamDownload(releases, "null", "3.2.4", "darwin", "arm64", "ab".repeat(32)))));
        upstream.stubFor(get(urlEqualTo("/v1/providers/hashicorp/null"))
                .willReturn(okJson("{\"id\":\"hashicorp/null\",\"namespace\":\"hashicorp\",\"name\":\"null\",\"version\":\"3.2.4\",\"source\":\"https://github.com/hashicorp/terraform-provider-null\"}")));
        upstream.stubFor(get(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + ZIP))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/zip").withBody(NULL_ZIP)));
        upstream.stubFor(get(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + SUMS_FILE))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody(SUMS)));
        upstream.stubFor(get(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + SUMS_FILE + ".sig"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(signature())));
        upstream.stubFor(get(urlEqualTo("/v1/modules/hashicorp/consul/aws/versions"))
                .willReturn(okJson(upstreamModuleVersions("hashicorp", "consul", "aws", "0.10.0", "0.11.0"))));
        upstream.stubFor(get(urlEqualTo("/v1/modules/hashicorp/consul/aws/0.11.0/download"))
                .willReturn(aResponse().withStatus(204)
                        .withHeader("X-Terraform-Get", upstreamBase + "/archives/hashicorp/terraform-aws-consul/tarball/v0.11.0//*?archive=tar.gz")));
        upstream.stubFor(get(urlEqualTo("/archives/hashicorp/terraform-aws-consul/tarball/v0.11.0"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-gzip").withBody(CONSUL_TGZ)));
        upstream.stubFor(get(urlEqualTo("/v1/modules/acme/git/aws/versions"))
                .willReturn(okJson(upstreamModuleVersions("acme", "git", "aws", "1.0.0"))));
        upstream.stubFor(get(urlEqualTo("/v1/modules/acme/git/aws/1.0.0/download"))
                .willReturn(aResponse().withStatus(204).withHeader("X-Terraform-Get", "git::https://github.com/acme/terraform-aws-git.git?ref=v1.0.0")));
        upstream.stubFor(get(urlEqualTo("/v1/modules/hashicorp/consul/aws"))
                .willReturn(okJson("{\"id\":\"hashicorp/consul/aws/0.11.0\",\"namespace\":\"hashicorp\",\"name\":\"consul\",\"provider\":\"aws\",\"version\":\"0.11.0\"}")));
    }

    private static void breakUpstream() {
        upstream.resetAll();
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(500).withBody("upstream is on fire")));
    }

    /** GET of a repository-relative path, named to stay clear of WireMock's static {@code get}. */
    private ResultActions fetch(String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/repository/" + REPO + "/" + path).accept(MediaType.APPLICATION_JSON));
    }

    @Test
    void discoveryDocumentNamesTheProxyAndIsNeverFetchedForItself() throws Exception {
        fetch(".well-known/terraform.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['providers.v1']").value(BASE + "/v1/providers/"))
                .andExpect(jsonPath("$['modules.v1']").value(BASE + "/v1/modules/"))
                .andExpect(content().string(not(containsString(upstreamBase))));
        upstream.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void versionsAreResolvedThroughUpstreamDiscoveryAndCached() throws Exception {
        for (int i = 0; i < 2; i++) {
            fetch("v1/providers/hashicorp/null/versions")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("hashicorp/null"))
                    .andExpect(jsonPath("$.versions", hasSize(2)))
                    .andExpect(jsonPath("$.versions[0].platforms", hasSize(2)));
        }
        // discovery once, versions once (fresh metadata is served from the cache), both with the CLI's Accept header
        upstream.verify(1, getRequestedFor(urlEqualTo("/.well-known/terraform.json")).withHeader("Accept", equalTo("application/json")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/v1/providers/hashicorp/null/versions")).withHeader("Accept", equalTo("application/json")));
        assertThat(storage.find(repo, "v1/providers/hashicorp/null/versions")).hasValueSatisfying(c -> assertThat(c.asset().isMetadata()).isTrue());
        fetch("v1/providers/hashicorp/null").andExpect(status().isOk()).andExpect(jsonPath("$.source").value(containsString("github.com")));
    }

    @Test
    void downloadDocumentIsRewrittenAndFilesAreFetchedOnceFromTheirOriginalUrls() throws Exception {
        ObjectNode download = object(fetch("v1/providers/hashicorp/null/3.2.4/download/linux/amd64")
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(upstreamBase))))
                .andReturn());
        assertThat(str(download, "filename")).isEqualTo(ZIP);
        assertThat(str(download, "download_url")).isEqualTo(BASE + "/files/hashicorp/null/3.2.4/" + ZIP);
        assertThat(str(download, "shasums_url")).isEqualTo(BASE + "/files/hashicorp/null/3.2.4/" + SUMS_FILE);
        assertThat(str(download, "shasums_signature_url")).isEqualTo(BASE + "/files/hashicorp/null/3.2.4/" + SUMS_FILE + ".sig");
        assertThat(str(download, "shasum")).isEqualTo(sha256(NULL_ZIP));
        assertThat(str(download.path("signing_keys").path("gpg_public_keys").get(0), "key_id")).isEqualTo(HASHICORP_KEY_ID);
        // the raw upstream document is what is cached; the rewrite happens on the way out
        assertThat(storage.find(repo, "v1/providers/hashicorp/null/3.2.4/download/linux/amd64").orElseThrow().readUtf8()).contains(releases);

        for (int i = 0; i < 2; i++) {
            MvcResult zip = mvc.perform(MockMvcRequestBuilders.get("/repository/" + REPO + "/files/hashicorp/null/3.2.4/" + ZIP))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/zip"))
                    .andExpect(header().string("ETag", "\"" + sha256(NULL_ZIP) + "\""))
                    .andReturn();
            assertThat(zip.getResponse().getContentAsByteArray()).isEqualTo(NULL_ZIP);
        }
        upstream.verify(1, getRequestedFor(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + ZIP)));
        assertThat(storage.find(repo, "files/hashicorp/null/3.2.4/" + ZIP)).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isFalse();
            assertThat(c.asset().getDownloadCount()).isEqualTo(2);
        });
        fetch("files/hashicorp/null/3.2.4/" + SUMS_FILE).andExpect(status().isOk()).andExpect(content().string(SUMS));
        assertThat(fetch("files/hashicorp/null/3.2.4/" + SUMS_FILE + ".sig").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(signature());
        fetch("files/hashicorp/null/3.2.4/never-mentioned.zip").andExpect(status().isNotFound());

        // what the proxy cached shows up in the component browser like an upload would
        var component = components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "hashicorp", "null", "3.2.4");
        assertThat(component).isPresent();
        assertThat(storage.find(repo, "files/hashicorp/null/3.2.4/" + ZIP).orElseThrow().asset().getComponentId())
                .isEqualTo(component.get().getId());
        assertThat(storage.find(repo, "files/hashicorp/null/3.2.4/" + SUMS_FILE).orElseThrow().asset().getComponentId())
                .isEqualTo(component.get().getId());
        // documents stay outside components
        assertThat(storage.find(repo, "v1/providers/hashicorp/null/3.2.4/download/linux/amd64").orElseThrow().asset().getComponentId()).isNull();
    }

    @Test
    void archivesCanBeFetchedBeforeTheirDownloadDocument() throws Exception {
        // terraform providers mirror, or a curl, may ask for the file first: the conventional name tells the platform
        assertThat(fetch("files/hashicorp/null/3.2.4/" + ZIP).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(NULL_ZIP);
        upstream.verify(1, getRequestedFor(urlEqualTo("/v1/providers/hashicorp/null/3.2.4/download/linux/amd64")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + ZIP)));
    }

    @Test
    void networkMirrorIsBuiltFromTheUpstreamProviderProtocol() throws Exception {
        fetch("mirror/" + upstream.baseUrl().replaceFirst("^http://", "") + "/hashicorp/null/index.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions['3.2.3']").exists())
                .andExpect(jsonPath("$.versions['3.2.4']").exists());
        // the Granary host itself is accepted as the mirrored hostname too
        fetch("mirror/localhost/hashicorp/null/index.json").andExpect(status().isOk());
        fetch("mirror/registry.opentofu.org/hashicorp/null/index.json").andExpect(status().isNotFound());

        fetch("mirror/localhost/hashicorp/null/3.2.4.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archives.linux_amd64.url").value(BASE + "/files/hashicorp/null/3.2.4/" + ZIP))
                .andExpect(jsonPath("$.archives.linux_amd64.hashes[0]").value("zh:" + sha256(NULL_ZIP)))
                .andExpect(jsonPath("$.archives.darwin_arm64.url").value(BASE + "/files/hashicorp/null/3.2.4/" + archiveName("null", "3.2.4", "darwin", "arm64")))
                .andExpect(jsonPath("$.archives.darwin_arm64.hashes[0]").value("zh:" + "ab".repeat(32)));
        upstream.verify(1, getRequestedFor(urlEqualTo("/v1/providers/hashicorp/null/3.2.4/download/linux/amd64")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/v1/providers/hashicorp/null/3.2.4/download/darwin/arm64")));
        fetch("mirror/localhost/hashicorp/null/9.9.9.json").andExpect(status().isNotFound());
        // the mirror download then goes through the normal file path
        assertThat(fetch("files/hashicorp/null/3.2.4/" + ZIP).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(NULL_ZIP);
    }

    @Test
    void moduleDownloadsAreRewrittenToGranaryWhenTheyAreArchivesAndPassedThroughOtherwise() throws Exception {
        fetch("v1/modules/hashicorp/consul/aws/versions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules[0].versions", hasSize(2)))
                .andExpect(jsonPath("$.modules[0].versions[1].root").exists()); // passed through untouched
        for (int i = 0; i < 2; i++) {
            fetch("v1/modules/hashicorp/consul/aws/0.11.0/download")
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("X-Terraform-Get", BASE + "/files/modules/hashicorp/consul/aws/0.11.0.tgz//*"));
        }
        upstream.verify(1, getRequestedFor(urlEqualTo("/v1/modules/hashicorp/consul/aws/0.11.0/download")));
        fetch("v1/modules/hashicorp/consul/aws/download")
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Terraform-Get", BASE + "/files/modules/hashicorp/consul/aws/0.11.0.tgz//*"));
        for (int i = 0; i < 2; i++) {
            MvcResult archive = fetch("files/modules/hashicorp/consul/aws/0.11.0.tgz").andExpect(status().isOk()).andReturn();
            assertThat(archive.getResponse().getContentAsByteArray()).isEqualTo(CONSUL_TGZ);
        }
        upstream.verify(1, getRequestedFor(urlEqualTo("/archives/hashicorp/terraform-aws-consul/tarball/v0.11.0")));
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "hashicorp", "consul/aws", "0.11.0")).isPresent();
        fetch("files/modules/hashicorp/consul/aws/0.11.0.zip").andExpect(status().isNotFound());
        fetch("v1/modules/hashicorp/consul/aws").andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("aws"));

        fetch("v1/modules/acme/git/aws/1.0.0/download")
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Terraform-Get", "git::https://github.com/acme/terraform-aws-git.git?ref=v1.0.0"));
        fetch("files/modules/acme/git/aws/1.0.0.tgz").andExpect(status().isNotFound());
    }

    @Test
    void everythingCachedIsStillServedWhenUpstreamBreaks() throws Exception {
        Repository impatient = new Repository();
        impatient.setName("terraform-stale");
        impatient.setFormat("terraform");
        impatient.setType(RepositoryType.PROXY);
        impatient.setRemoteUrl(upstreamBase + "/");
        impatient.setMetadataMaxAgeMinutes(0); // re-validate every document on every request
        repositories.create(impatient);
        String url = "/repository/terraform-stale/";

        mvc.perform(MockMvcRequestBuilders.get(url + "v1/providers/hashicorp/null/versions")).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(url + "v1/providers/hashicorp/null/3.2.4/download/linux/amd64")).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(url + "files/hashicorp/null/3.2.4/" + ZIP)).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(url + "files/hashicorp/null/3.2.4/" + SUMS_FILE)).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(url + "v1/modules/hashicorp/consul/aws/0.11.0/download")).andExpect(status().isNoContent());
        mvc.perform(MockMvcRequestBuilders.get(url + "files/modules/hashicorp/consul/aws/0.11.0.tgz")).andExpect(status().isOk());

        breakUpstream();

        mvc.perform(MockMvcRequestBuilders.get(url + "v1/providers/hashicorp/null/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions", hasSize(2)));
        upstream.verify(1, getRequestedFor(urlEqualTo("/v1/providers/hashicorp/null/versions"))); // it did try
        mvc.perform(MockMvcRequestBuilders.get(url + "v1/providers/hashicorp/null/3.2.4/download/linux/amd64"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.download_url").value(repositoryUrl("terraform-stale") + "/files/hashicorp/null/3.2.4/" + ZIP));
        assertThat(mvc.perform(MockMvcRequestBuilders.get(url + "files/hashicorp/null/3.2.4/" + ZIP)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(NULL_ZIP);
        mvc.perform(MockMvcRequestBuilders.get(url + "files/hashicorp/null/3.2.4/" + SUMS_FILE)).andExpect(status().isOk());
        upstream.verify(0, getRequestedFor(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + ZIP)));
        mvc.perform(MockMvcRequestBuilders.get(url + "v1/modules/hashicorp/consul/aws/0.11.0/download"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Terraform-Get", containsString("/files/modules/hashicorp/consul/aws/0.11.0.tgz")));
        mvc.perform(MockMvcRequestBuilders.get(url + "files/modules/hashicorp/consul/aws/0.11.0.tgz")).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(url + "mirror/localhost/hashicorp/null/index.json")).andExpect(status().isOk());

        // what was never cached is a temporary upstream failure, not "does not exist"
        mvc.perform(MockMvcRequestBuilders.get(url + "v1/providers/hashicorp/random/versions"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(MockMvcRequestBuilders.get(url + "v1/modules/hashicorp/vault/aws/1.0.0/download"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(MockMvcRequestBuilders.get(url + ".well-known/terraform.json")).andExpect(status().isOk());
    }

    @Test
    void offlineModeNeverTouchesUpstream() throws Exception {
        fetch("v1/providers/hashicorp/null/versions").andExpect(status().isOk());
        fetch("v1/providers/hashicorp/null/3.2.4/download/linux/amd64").andExpect(status().isOk());
        fetch("files/hashicorp/null/3.2.4/" + ZIP).andExpect(status().isOk());
        fetch("v1/modules/hashicorp/consul/aws/0.11.0/download").andExpect(status().isNoContent());
        fetch("files/modules/hashicorp/consul/aws/0.11.0.tgz").andExpect(status().isOk());
        upstream.resetAll();
        settings.setGlobalOffline(true);

        fetch("v1/providers/hashicorp/null/versions").andExpect(status().isOk()).andExpect(jsonPath("$.versions", hasSize(2)));
        fetch("v1/providers/hashicorp/null/3.2.4/download/linux/amd64").andExpect(status().isOk());
        fetch("files/hashicorp/null/3.2.4/" + ZIP).andExpect(status().isOk());
        fetch("v1/modules/hashicorp/consul/aws/0.11.0/download").andExpect(status().isNoContent());
        fetch("files/modules/hashicorp/consul/aws/0.11.0.tgz").andExpect(status().isOk());
        fetch("mirror/localhost/hashicorp/null/3.2.4.json").andExpect(status().isOk())
                .andExpect(jsonPath("$.archives.linux_amd64.url").exists())
                .andExpect(jsonPath("$.archives.darwin_arm64").doesNotExist()); // never cached, so not offered
        fetch("v1/providers/hashicorp/random/versions")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        fetch("v1/providers/hashicorp/null/3.2.4/download/darwin/arm64")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        fetch("v1/modules/hashicorp/vault/aws/1.0.0/download").andExpect(status().isServiceUnavailable());
        upstream.verify(0, getRequestedFor(anyUrl()));

        settings.setGlobalOffline(false);
        stubHealthyUpstream();
        repositories.setOffline(REPO, true);
        fetch("files/hashicorp/null/3.2.4/" + SUMS_FILE).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        repositories.setOffline(REPO, false);
        fetch("files/hashicorp/null/3.2.4/" + SUMS_FILE).andExpect(status().isOk());
    }

    @Test
    void upstream404IsRememberedByTheNegativeCache() throws Exception {
        upstream.stubFor(get(urlEqualTo("/v1/providers/hashicorp/nope/versions"))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json").withBody("{\"errors\":[\"Not Found\"]}")));
        upstream.stubFor(get(urlEqualTo("/v1/modules/acme/nope/aws/1.0.0/download")).willReturn(aResponse().withStatus(404)));
        for (int i = 0; i < 3; i++) {
            fetch("v1/providers/hashicorp/nope/versions")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("content.notFound"));
            fetch("v1/modules/acme/nope/aws/1.0.0/download").andExpect(status().isNotFound());
        }
        upstream.verify(1, getRequestedFor(urlEqualTo("/v1/providers/hashicorp/nope/versions")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/v1/modules/acme/nope/aws/1.0.0/download")));
        fetch("mirror/localhost/hashicorp/nope/index.json").andExpect(status().isNotFound());
        fetch("files/hashicorp/nope/1.0.0/random.bin").andExpect(status().isNotFound());
    }

    @Test
    void anUnreachableUpstreamWithNothingCachedIs503AndDiscoveryStillWorks() throws Exception {
        Repository dead = new Repository();
        dead.setName("terraform-dead");
        dead.setFormat("terraform");
        dead.setType(RepositoryType.PROXY);
        dead.setRemoteUrl("http://127.0.0.1:1/");
        repositories.create(dead);
        mvc.perform(MockMvcRequestBuilders.get("/repository/terraform-dead/v1/providers/hashicorp/null/versions"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(MockMvcRequestBuilders.get("/repository/terraform-dead/v1/modules/hashicorp/consul/aws/0.11.0/download"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(MockMvcRequestBuilders.get("/repository/terraform-dead/.well-known/terraform.json")).andExpect(status().isOk());
    }

    @Test
    void proxiesAreReadOnly() throws Exception {
        mvc.perform(put("/repository/" + REPO + "/v1/providers/hashicorp/null/3.2.4/linux/amd64/" + ZIP)
                        .header("Authorization", adminBasic()).content(NULL_ZIP))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"))
                .andExpect(jsonPath("$.message").value(containsString("read-only")));
        mvc.perform(put("/repository/" + REPO + "/v1/modules/acme/demo/local/1.0.0.tgz").header("Authorization", adminBasic()).content(CONSUL_TGZ))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/repository/" + REPO + "/v1/providers/hashicorp/null/3.2.4").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        upstream.verify(0, getRequestedFor(anyUrl()));
    }
}
