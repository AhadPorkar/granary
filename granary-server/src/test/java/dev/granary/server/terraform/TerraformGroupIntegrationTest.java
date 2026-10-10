package dev.granary.server.terraform;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static dev.granary.server.terraform.TerraformFixtures.HASHICORP_KEY_ID;
import static dev.granary.server.terraform.TerraformFixtures.archiveName;
import static dev.granary.server.terraform.TerraformFixtures.gpgKey;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.proxy.ProxyService;
import dev.granary.server.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.node.ObjectNode;

/** One registry URL for everything: a group of a hosted repository and a proxy of a fake registry.terraform.io. */
class TerraformGroupIntegrationTest extends IntegrationTest {

    private static final String GROUP = "terraform-group";
    private static final String HOSTED = "terraform-hosted";
    private static final String PROXY = "terraform-registry";
    private static final String BASE = repositoryUrl(GROUP);
    private static final String LINUX_ZIP = archiveName("null", "3.2.4", "linux", "amd64");
    private static final String DARWIN_ZIP = archiveName("null", "3.2.4", "darwin", "arm64");
    private static final byte[] HOSTED_ZIP = providerZip("hosted");
    private static final byte[] UPSTREAM_LINUX = providerZip("upstream-linux");
    private static final byte[] UPSTREAM_DARWIN = providerZip("upstream-darwin");
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
    void createRepositories() throws Exception {
        upstream.resetAll();
        hosted(HOSTED, "terraform");
        proxyService.invalidate(proxy(PROXY, "terraform", upstream.baseUrl() + "/"));
        group(GROUP, "terraform", HOSTED, PROXY);
        String releases = upstream.baseUrl() + "/releases";
        upstream.stubFor(get(urlEqualTo("/.well-known/terraform.json")).willReturn(okJson("{\"providers.v1\":\"/v1/providers/\",\"modules.v1\":\"/v1/modules/\"}")));
        upstream.stubFor(get(urlEqualTo("/v1/providers/hashicorp/null/versions"))
                .willReturn(okJson(upstreamVersions("hashicorp", "null", "3.2.4:linux_amd64,darwin_arm64", "3.2.3:linux_amd64"))));
        upstream.stubFor(get(urlEqualTo("/v1/providers/hashicorp/null/3.2.4/download/linux/amd64"))
                .willReturn(okJson(upstreamDownload(releases, "null", "3.2.4", "linux", "amd64", sha256(UPSTREAM_LINUX)))));
        upstream.stubFor(get(urlEqualTo("/v1/providers/hashicorp/null/3.2.4/download/darwin/arm64"))
                .willReturn(okJson(upstreamDownload(releases, "null", "3.2.4", "darwin", "arm64", sha256(UPSTREAM_DARWIN)))));
        upstream.stubFor(get(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + LINUX_ZIP))
                .willReturn(aResponse().withStatus(200).withBody(UPSTREAM_LINUX)));
        upstream.stubFor(get(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + DARWIN_ZIP))
                .willReturn(aResponse().withStatus(200).withBody(UPSTREAM_DARWIN)));
        upstream.stubFor(get(urlEqualTo("/releases/terraform-provider-null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS"))
                .willReturn(aResponse().withStatus(200).withBody(shaSums(LINUX_ZIP, UPSTREAM_LINUX, DARWIN_ZIP, UPSTREAM_DARWIN))));
        upstream.stubFor(get(urlEqualTo("/v1/providers/acme/internal/versions")).willReturn(notFound()));
        upstream.stubFor(get(urlEqualTo("/v1/modules/acme/demo/aws/versions"))
                .willReturn(okJson(upstreamModuleVersions("acme", "demo", "aws", "1.0.0", "2.0.0"))));
        upstream.stubFor(get(urlEqualTo("/v1/modules/acme/demo/aws/2.0.0/download"))
                .willReturn(aResponse().withStatus(204).withHeader("X-Terraform-Get", upstream.baseUrl() + "/archives/demo-2.0.0.tgz")));
        upstream.stubFor(get(urlEqualTo("/v1/modules/acme/demo/aws/1.0.0/download"))
                .willReturn(aResponse().withStatus(204).withHeader("X-Terraform-Get", upstream.baseUrl() + "/archives/demo-1.0.0.tgz")));
        upstream.stubFor(get(urlEqualTo("/archives/demo-2.0.0.tgz")).willReturn(aResponse().withStatus(200).withBody(moduleTgz("upstream-2.0.0"))));
        upstream.stubFor(get(urlEqualTo("/v1/modules/acme/only/aws/versions")).willReturn(notFound()));
    }

    private ResultActions fetch(String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/repository/" + GROUP + "/" + path));
    }

    private void publishHostedNull(MockMvc mvc) throws Exception {
        mvc.perform(put("/repository/" + HOSTED + "/v1/providers/hashicorp/null/3.2.4/linux/amd64/" + LINUX_ZIP)
                .header("Authorization", adminBasic()).content(HOSTED_ZIP)).andExpect(status().isCreated());
        mvc.perform(put("/repository/" + HOSTED + "/v1/providers/hashicorp/null/3.2.4/SHA256SUMS")
                .header("Authorization", adminBasic()).content(shaSums(LINUX_ZIP, HOSTED_ZIP))).andExpect(status().isCreated());
        mvc.perform(put("/repository/" + HOSTED + "/v1/providers/hashicorp/null/3.2.4/SHA256SUMS.sig")
                .header("Authorization", adminBasic()).content(signature())).andExpect(status().isCreated());
        mvc.perform(put("/repository/" + HOSTED + "/v1/providers/hashicorp/null/gpg-key")
                .header("Authorization", adminBasic()).content(gpgKey("ACME0001"))).andExpect(status().isCreated());
        mvc.perform(put("/repository/" + HOSTED + "/v1/providers/hashicorp/null/4.0.0/linux/amd64/" + archiveName("null", "4.0.0", "linux", "amd64"))
                .header("Authorization", adminBasic()).content(providerZip("four"))).andExpect(status().isCreated());
    }

    @Test
    void versionsAreMergedAndTheMemberThatOwnsAVersionServesAllOfIt() throws Exception {
        publishHostedNull(mvc);

        ObjectNode versions = object(fetch("v1/providers/hashicorp/null/versions").andExpect(status().isOk()).andReturn());
        assertThat(versions.path("versions")).extracting(v -> v.path("version").stringValue()).containsExactly("3.2.3", "3.2.4", "4.0.0");
        // hosted first: 3.2.4 is the hosted member's, so only its platform is listed; the proxy's darwin build of the
        // same version would come with HashiCorp's SHA256SUMS and signature, not the hosted ones
        assertThat(versions.path("versions").get(1).path("platforms"))
                .extracting(p -> p.path("os").stringValue() + "_" + p.path("arch").stringValue())
                .containsExactly("linux_amd64");
        assertThat(versions.path("versions").get(1).path("protocols")).extracting(v -> v.stringValue()).containsExactly("5.0", "6.0");
        // 3.2.3 only exists upstream, so the proxy's entry is listed unchanged
        assertThat(versions.path("versions").get(0).path("protocols")).extracting(v -> v.stringValue()).containsExactly("5.0");

        ObjectNode linux = object(fetch("v1/providers/hashicorp/null/3.2.4/download/linux/amd64").andExpect(status().isOk()).andReturn());
        assertThat(str(linux, "download_url")).isEqualTo(BASE + "/files/hashicorp/null/3.2.4/" + LINUX_ZIP);
        assertThat(str(linux, "shasums_url")).isEqualTo(BASE + "/files/hashicorp/null/3.2.4/SHA256SUMS");
        assertThat(str(linux, "shasum")).isEqualTo(sha256(HOSTED_ZIP));
        assertThat(str(linux.path("signing_keys").path("gpg_public_keys").get(0), "key_id")).isEqualTo("ACME0001");
        assertThat(fetch("files/hashicorp/null/3.2.4/" + LINUX_ZIP).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(HOSTED_ZIP);
        fetch("files/hashicorp/null/3.2.4/SHA256SUMS").andExpect(status().isOk());

        // nothing of the proxy's 3.2.4 leaks through the group
        fetch("v1/providers/hashicorp/null/3.2.4/download/darwin/arm64").andExpect(status().isNotFound());
        fetch("files/hashicorp/null/3.2.4/" + DARWIN_ZIP).andExpect(status().isNotFound());
        fetch("files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS").andExpect(status().isNotFound());
        upstream.verify(0, getRequestedFor(urlEqualTo("/v1/providers/hashicorp/null/3.2.4/download/darwin/arm64")));
        upstream.verify(0, getRequestedFor(urlEqualTo("/releases/terraform-provider-null/3.2.4/" + DARWIN_ZIP)));

        fetch("v1/providers/hashicorp/null").andExpect(status().isOk()).andExpect(jsonPath("$.version").value("4.0.0"))
                .andExpect(jsonPath("$.versions", hasSize(3)));
        fetch("v1/providers/hashicorp/null/gpg-key").andExpect(status().isOk()).andExpect(jsonPath("$.gpg_public_keys[0].key_id").value("ACME0001"));
        fetch(".well-known/terraform.json").andExpect(status().isOk()).andExpect(jsonPath("$['providers.v1']").value(BASE + "/v1/providers/"));
    }

    @Test
    void aVersionOnlyTheProxyHasIsServedFromTheProxyRewrittenToTheGroup() throws Exception {
        ObjectNode darwin = object(fetch("v1/providers/hashicorp/null/3.2.4/download/darwin/arm64").andExpect(status().isOk()).andReturn());
        assertThat(str(darwin, "download_url")).isEqualTo(BASE + "/files/hashicorp/null/3.2.4/" + DARWIN_ZIP);
        assertThat(str(darwin, "shasums_url")).isEqualTo(BASE + "/files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS");
        assertThat(str(darwin.path("signing_keys").path("gpg_public_keys").get(0), "key_id")).isEqualTo(HASHICORP_KEY_ID);
        assertThat(fetch("files/hashicorp/null/3.2.4/" + DARWIN_ZIP).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(UPSTREAM_DARWIN);
        assertThat(fetch("files/hashicorp/null/3.2.4/" + LINUX_ZIP).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(UPSTREAM_LINUX);
        fetch("files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS").andExpect(status().isOk());
        // the hosted member has no 3.2.4, so its fixed checksum file name is not there either
        fetch("files/hashicorp/null/3.2.4/SHA256SUMS").andExpect(status().isNotFound());
    }

    @Test
    void anUnreachableProxyMemberDoesNotHideTheHostedMember() throws Exception {
        publishHostedNull(mvc);
        upstream.resetAll();
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.any(com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
                .willReturn(aResponse().withStatus(503)));
        fetch("v1/providers/hashicorp/null/versions").andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[*].version").value(org.hamcrest.Matchers.contains("3.2.4", "4.0.0")));
        fetch("v1/providers/hashicorp/null/3.2.4/download/linux/amd64").andExpect(status().isOk());
        fetch("files/hashicorp/null/3.2.4/" + LINUX_ZIP).andExpect(status().isOk());
        // what only the proxy could have answered is a temporary failure, not a 404
        fetch("v1/providers/hashicorp/null/3.2.3/download/linux/amd64").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void networkMirrorIsMergedAcrossMembers() throws Exception {
        publishHostedNull(mvc);
        upstream.stubFor(get(urlEqualTo("/v1/providers/hashicorp/null/3.2.3/download/linux/amd64"))
                .willReturn(okJson(upstreamDownload(upstream.baseUrl() + "/releases", "null", "3.2.3", "linux", "amd64", "cd".repeat(32)))));
        fetch("mirror/localhost/hashicorp/null/index.json").andExpect(status().isOk())
                .andExpect(jsonPath("$.versions['3.2.3']").exists())
                .andExpect(jsonPath("$.versions['3.2.4']").exists())
                .andExpect(jsonPath("$.versions['4.0.0']").exists());
        // 3.2.4 is the hosted member's: its archive only
        fetch("mirror/localhost/hashicorp/null/3.2.4.json").andExpect(status().isOk())
                .andExpect(jsonPath("$.archives.linux_amd64.url").value(BASE + "/files/hashicorp/null/3.2.4/" + LINUX_ZIP))
                .andExpect(jsonPath("$.archives.linux_amd64.hashes[0]").value("zh:" + sha256(HOSTED_ZIP)))
                .andExpect(jsonPath("$.archives.darwin_arm64").doesNotExist());
        // 3.2.3 only exists upstream
        fetch("mirror/localhost/hashicorp/null/3.2.3.json").andExpect(status().isOk())
                .andExpect(jsonPath("$.archives.linux_amd64.url").value(BASE + "/files/hashicorp/null/3.2.3/" + archiveName("null", "3.2.3", "linux", "amd64")))
                .andExpect(jsonPath("$.archives.linux_amd64.hashes[0]").value("zh:" + "cd".repeat(32)));
        // the upstream's own host name only reaches the proxy, but hosted answers for every host
        fetch("mirror/" + upstream.baseUrl().replaceFirst("^http://", "") + "/hashicorp/null/4.0.0.json").andExpect(status().isOk())
                .andExpect(jsonPath("$.archives.linux_amd64.url").value(containsString("/4.0.0/")));
    }

    @Test
    void providersOnlyOneMemberHasStillResolve() throws Exception {
        mvc.perform(put("/repository/" + HOSTED + "/v1/providers/acme/internal/1.0.0/linux/amd64/" + archiveName("internal", "1.0.0", "linux", "amd64"))
                .header("Authorization", adminBasic()).content(providerZip("internal"))).andExpect(status().isCreated());
        fetch("v1/providers/acme/internal/versions").andExpect(status().isOk()).andExpect(jsonPath("$.versions[0].version").value("1.0.0"));
        fetch("v1/providers/acme/internal/1.0.0/download/linux/amd64").andExpect(status().isOk())
                .andExpect(jsonPath("$.download_url").value(BASE + "/files/acme/internal/1.0.0/" + archiveName("internal", "1.0.0", "linux", "amd64")));
        fetch("v1/providers/hashicorp/null/versions").andExpect(status().isOk()).andExpect(jsonPath("$.versions", hasSize(2)));
        fetch("v1/providers/hashicorp/null/3.2.3/download/linux/amd64").andExpect(status().isNotFound());
        fetch("v1/providers/nobody/nothing/versions").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("content.notFound"));
        fetch("files/nobody/nothing/1.0.0/x.zip").andExpect(status().isNotFound());
        fetch("mirror/localhost/nobody/nothing/index.json").andExpect(status().isNotFound());
    }

    @Test
    void modulesAreMergedWithHostedPrecedence() throws Exception {
        mvc.perform(put("/repository/" + HOSTED + "/v1/modules/acme/demo/aws/1.0.0.tgz").header("Authorization", adminBasic())
                .content(moduleTgz("hosted-1.0.0"))).andExpect(status().isCreated());
        mvc.perform(put("/repository/" + HOSTED + "/v1/modules/acme/only/aws/0.1.0.tgz").header("Authorization", adminBasic())
                .content(moduleTgz("only"))).andExpect(status().isCreated());

        fetch("v1/modules/acme/demo/aws/versions").andExpect(status().isOk())
                .andExpect(jsonPath("$.modules[0].source").value("acme/demo/aws"))
                .andExpect(jsonPath("$.modules[0].versions[0].version").value("1.0.0"))
                .andExpect(jsonPath("$.modules[0].versions[1].version").value("2.0.0"))
                .andExpect(jsonPath("$.modules[0].versions", hasSize(2)));
        fetch("v1/modules/acme/demo/aws/1.0.0/download").andExpect(status().isNoContent())
                .andExpect(header().string("X-Terraform-Get", BASE + "/files/modules/acme/demo/aws/1.0.0.tgz"));
        fetch("v1/modules/acme/demo/aws/2.0.0/download").andExpect(status().isNoContent())
                .andExpect(header().string("X-Terraform-Get", BASE + "/files/modules/acme/demo/aws/2.0.0.tgz"));
        fetch("v1/modules/acme/demo/aws/download").andExpect(status().isNoContent())
                .andExpect(header().string("X-Terraform-Get", BASE + "/files/modules/acme/demo/aws/2.0.0.tgz"));
        assertThat(fetch("files/modules/acme/demo/aws/1.0.0.tgz").andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(moduleTgz("hosted-1.0.0"));
        assertThat(fetch("files/modules/acme/demo/aws/2.0.0.tgz").andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(moduleTgz("upstream-2.0.0"));
        fetch("v1/modules/acme/demo/aws").andExpect(status().isOk()).andExpect(jsonPath("$.version").value("2.0.0"));
        fetch("v1/modules/acme/only/aws/versions").andExpect(status().isOk()).andExpect(jsonPath("$.modules[0].versions", hasSize(1)));
        fetch("v1/modules/acme/none/aws/versions").andExpect(status().isNotFound());
    }

    @Test
    void writesAreRefusedAndNameTheHostedMember() throws Exception {
        mvc.perform(put("/repository/" + GROUP + "/v1/providers/hashicorp/null/3.2.4/linux/amd64/" + LINUX_ZIP)
                        .header("Authorization", adminBasic()).content(HOSTED_ZIP))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"))
                .andExpect(jsonPath("$.message").value(containsString(HOSTED)));
        mvc.perform(put("/repository/" + GROUP + "/v1/modules/acme/demo/aws/1.0.0.tgz").header("Authorization", adminBasic()).content(moduleTgz("x")))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/repository/" + GROUP + "/v1/providers/hashicorp/null/3.2.4").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/repository/" + GROUP + "/v1/providers/hashicorp/null/gpg-key").header("Authorization", adminBasic()).content(gpgKey("X")))
                .andExpect(status().isMethodNotAllowed());
    }
}
