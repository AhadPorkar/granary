package dev.granary.server.terraform;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static dev.granary.server.UpstreamCredentialFixtures.PASSWORD;
import static dev.granary.server.UpstreamCredentialFixtures.USER;
import static dev.granary.server.UpstreamCredentialFixtures.authorisedRequests;
import static dev.granary.server.UpstreamCredentialFixtures.hostOf;
import static dev.granary.server.UpstreamCredentialFixtures.proxyWithCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withoutCredentials;
import static dev.granary.server.terraform.TerraformFixtures.archiveName;
import static dev.granary.server.terraform.TerraformFixtures.moduleTgz;
import static dev.granary.server.terraform.TerraformFixtures.providerZip;
import static dev.granary.server.terraform.TerraformFixtures.sha256;
import static dev.granary.server.terraform.TerraformFixtures.shaSums;
import static dev.granary.server.terraform.TerraformFixtures.signature;
import static dev.granary.server.terraform.TerraformFixtures.upstreamDownload;
import static dev.granary.server.terraform.TerraformFixtures.upstreamModuleVersions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.proxy.ProxyService;
import dev.granary.server.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A private registry whose provider releases live on another host (releases.hashicorp.com, GitHub) and whose module
 * archives are downloaded from yet another: the registry API gets the proxy's credentials, the download hosts do
 * not, unless they are listed in {@code upstream.credentialHosts}.
 */
class TerraformUpstreamCredentialsIntegrationTest extends IntegrationTest {

    private static final String ZIP = archiveName("null", "3.2.4", "linux", "amd64");
    private static final String SUMS_FILE = "terraform-provider-null_3.2.4_SHA256SUMS";
    private static final byte[] NULL_ZIP = providerZip("private-3.2.4");
    private static final byte[] CONSUL_TGZ = moduleTgz("private-consul");
    private static final WireMockServer registry = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer releases = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private ProxyService proxyService;

    @BeforeAll
    static void start() {
        registry.start();
        releases.start();
    }

    @AfterAll
    static void stop() {
        registry.stop();
        releases.stop();
    }

    @BeforeEach
    void stubServers() {
        registry.resetAll();
        releases.resetAll();
        registry.stubFor(WireMock.any(WireMock.anyUrl()).atPriority(10).willReturn(WireMock.unauthorized()));
        registryHas("/.well-known/terraform.json", "{\"modules.v1\":\"/v1/modules/\",\"providers.v1\":\"/v1/providers/\"}");
        registryHas("/v1/providers/hashicorp/null/3.2.4/download/linux/amd64",
                upstreamDownload(releases.baseUrl() + "/releases", "null", "3.2.4", "linux", "amd64", sha256(NULL_ZIP)));
        registryHas("/v1/modules/hashicorp/consul/aws/versions", upstreamModuleVersions("hashicorp", "consul", "aws", "0.11.0"));
        registry.stubFor(get(urlEqualTo("/v1/modules/hashicorp/consul/aws/0.11.0/download")).atPriority(1)
                .withBasicAuth(USER, PASSWORD)
                .willReturn(aResponse().withStatus(204).withHeader("X-Terraform-Get",
                        releases.baseUrl() + "/archives/terraform-aws-consul/v0.11.0.tar.gz")));
        releases.stubFor(get(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + ZIP))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/zip").withBody(NULL_ZIP)));
        releases.stubFor(get(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + SUMS_FILE))
                .willReturn(aResponse().withStatus(200).withBody(shaSums(ZIP, NULL_ZIP))));
        releases.stubFor(get(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + SUMS_FILE + ".sig"))
                .willReturn(aResponse().withStatus(200).withBody(signature())));
        releases.stubFor(get(urlPathEqualTo("/archives/terraform-aws-consul/v0.11.0.tar.gz"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-gzip").withBody(CONSUL_TGZ)));
    }

    private static void registryHas(String path, String json) {
        registry.stubFor(get(urlEqualTo(path)).atPriority(1).withBasicAuth(USER, PASSWORD).willReturn(okJson(json)));
    }

    private void createProxy(String name, String trustedHosts) {
        proxyService.invalidate(repositories.create(proxyWithCredentials(name, "terraform", registry.baseUrl() + "/", trustedHosts)));
    }

    private byte[] fetch(String repo, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/repository/" + repo + "/" + path).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private void downloadEverything(String repo) throws Exception {
        fetch(repo, "v1/providers/hashicorp/null/3.2.4/download/linux/amd64");
        assertThat(fetch(repo, "files/hashicorp/null/3.2.4/" + ZIP)).isEqualTo(NULL_ZIP);
        fetch(repo, "files/hashicorp/null/3.2.4/" + SUMS_FILE);
        fetch(repo, "files/hashicorp/null/3.2.4/" + SUMS_FILE + ".sig");
        fetch(repo, "v1/modules/hashicorp/consul/aws/versions");
        mvc.perform(MockMvcRequestBuilders.get("/repository/" + repo + "/v1/modules/hashicorp/consul/aws/0.11.0/download"))
                .andExpect(status().isNoContent());
        assertThat(fetch(repo, "files/modules/hashicorp/consul/aws/0.11.0.tgz")).isEqualTo(CONSUL_TGZ);
    }

    @Test
    void releaseAndArchiveHostsNeverSeeTheRegistrysCredentials() throws Exception {
        createProxy("terraform-private", null);
        downloadEverything("terraform-private");

        registry.verify(1, withCredentials(getRequestedFor(urlEqualTo("/.well-known/terraform.json"))));
        registry.verify(1, withCredentials(getRequestedFor(urlEqualTo("/v1/providers/hashicorp/null/3.2.4/download/linux/amd64"))));
        registry.verify(1, withCredentials(getRequestedFor(urlEqualTo("/v1/modules/hashicorp/consul/aws/0.11.0/download"))));
        releases.verify(1, withoutCredentials(getRequestedFor(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + ZIP))));
        releases.verify(1, withoutCredentials(getRequestedFor(urlPathEqualTo("/archives/terraform-aws-consul/v0.11.0.tar.gz"))));
        assertThat(releases.getAllServeEvents()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(authorisedRequests(releases)).isZero();
    }

    @Test
    void aReleaseHostListedInTheSettingReceivesThem() throws Exception {
        createProxy("terraform-trusting", hostOf(releases));
        downloadEverything("terraform-trusting");

        releases.verify(1, withCredentials(getRequestedFor(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + ZIP))));
        releases.verify(1, withCredentials(getRequestedFor(urlPathEqualTo("/archives/terraform-aws-consul/v0.11.0.tar.gz"))));
    }
}
