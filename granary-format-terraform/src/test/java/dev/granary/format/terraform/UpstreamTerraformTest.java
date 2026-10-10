package dev.granary.format.terraform;

import static dev.granary.format.terraform.TerraformTestSupport.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The module download endpoint is the one piece of the proxy that bypasses the generic cache; its rules are tested here. */
class UpstreamTerraformTest {

    private static final String REMOTE = "https://registry.example.test/";
    private static final String PATH = "v1/modules/acme/demo/aws/1.0.0/download";

    private final FakeStorage storage = new FakeStorage();
    private final ProxyService proxy = mock(ProxyService.class);
    private final UpstreamClient client = mock(UpstreamClient.class);
    private final UpstreamHealth health = mock(UpstreamHealth.class);
    private final NegativeCache negativeCache = mock(NegativeCache.class);
    private final UpstreamTerraform upstream = new UpstreamTerraform(proxy, client, health, negativeCache, storage.mock, MAPPER);
    private final Repository repo = TerraformTestSupport.proxy("terraform-registry", REMOTE);

    @BeforeEach
    void setUp() {
        when(proxy.metadataMaxAge(any())).thenReturn(Duration.ofMinutes(30));
        when(proxy.negativeTtl(any())).thenReturn(Duration.ofMinutes(5));
        when(health.allowRequest(any())).thenReturn(true);
    }

    private UpstreamClient.Response response(int status, String xTerraformGet) {
        UpstreamClient.Response response = mock(UpstreamClient.Response.class);
        when(response.status()).thenReturn(status);
        when(response.header("X-Terraform-Get")).thenReturn(Optional.ofNullable(xTerraformGet));
        when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        return response;
    }

    @Test
    void upstreamHostOfARepository() {
        assertThat(UpstreamTerraform.upstreamHost(repo)).isEqualTo("registry.example.test");
        assertThat(UpstreamTerraform.upstreamHost(TerraformTestSupport.proxy("p", "http://localhost:8109/x/"))).isEqualTo("localhost:8109");
        assertThat(UpstreamTerraform.upstreamHost(TerraformTestSupport.proxy("p", "not a url"))).isEmpty();
        assertThat(UpstreamTerraform.upstreamHost(TerraformTestSupport.proxy("p", null))).isEmpty();
    }

    @Test
    void servicesFallBackToConventionsWhenDiscoveryIsUnavailable() {
        when(proxy.fetch(any(), anyString(), any(URI.class), any(), anyMap())).thenReturn(Optional.empty());
        UpstreamTerraform.Services services = upstream.services(repo);
        assertThat(services.providers()).isEqualTo(REMOTE + "v1/providers/");
        assertThat(services.modules()).isEqualTo(REMOTE + "v1/modules/");
        verify(proxy).fetch(eq(repo), eq(".well-known/terraform.json"), eq(URI.create(REMOTE + ".well-known/terraform.json")),
                eq(FetchOptions.index()), anyMap());

        Repository withPath = TerraformTestSupport.proxy("p", "https://host.example/registry");
        assertThat(upstream.services(withPath).providers()).isEqualTo("https://host.example/registry/v1/providers/");
    }

    @Test
    void servicesResolveRelativeAndAbsoluteEndpointsAndIgnoreJunk() {
        StoredContent discovery = TerraformTestSupport.content(storage.blobs, ".well-known/terraform.json",
                "{\"providers.v1\":\"/v1/providers\",\"modules.v1\":\"https://modules.example.test/v1/\",\"login.v1\":{}}".getBytes(StandardCharsets.UTF_8), true);
        when(proxy.fetch(any(), eq(".well-known/terraform.json"), any(URI.class), any(), anyMap())).thenReturn(Optional.of(discovery));
        UpstreamTerraform.Services services = upstream.services(repo);
        assertThat(services.providers()).isEqualTo(REMOTE + "v1/providers/");
        assertThat(services.modules()).isEqualTo("https://modules.example.test/v1/");

        StoredContent junk = TerraformTestSupport.content(storage.blobs, ".well-known/terraform.json", "not json".getBytes(StandardCharsets.UTF_8), true);
        when(proxy.fetch(any(), eq(".well-known/terraform.json"), any(URI.class), any(), anyMap())).thenReturn(Optional.of(junk));
        assertThat(upstream.services(repo).modules()).isEqualTo(REMOTE + "v1/modules/");
        StoredContent odd = TerraformTestSupport.content(storage.blobs, ".well-known/terraform.json", "{\"providers.v1\":\"::bad::\"}".getBytes(StandardCharsets.UTF_8), true);
        when(proxy.fetch(any(), eq(".well-known/terraform.json"), any(URI.class), any(), anyMap())).thenReturn(Optional.of(odd));
        assertThat(upstream.services(repo).providers()).isEqualTo(REMOTE + "v1/providers/");
    }

    @Test
    void moduleDownloadIsFetchedOnceAndRememberedWhileFresh() throws Exception {
        when(proxy.fetch(any(), anyString(), any(URI.class), any(), anyMap())).thenReturn(Optional.empty());
        UpstreamClient.Response r1 = response(204, "git::https://github.com/acme/demo.git?ref=v1.0.0");
        when(client.getAbsolute(eq(repo), any(URI.class), anyMap(), anyMap())).thenReturn(r1);

        assertThat(upstream.moduleDownload(repo, "acme", "demo", "aws", "1.0.0")).contains("git::https://github.com/acme/demo.git?ref=v1.0.0");
        verify(client).getAbsolute(eq(repo), eq(URI.create(REMOTE + "v1/modules/acme/demo/aws/1.0.0/download")), anyMap(), anyMap());
        verify(health).recordSuccess(repo);
        assertThat(storage.text(repo, PATH)).contains("git::https://github.com/acme/demo.git");
        assertThat(storage.find(repo, PATH).orElseThrow().asset().isMetadata()).isTrue();

        assertThat(upstream.moduleDownload(repo, "acme", "demo", "aws", "1.0.0")).contains("git::https://github.com/acme/demo.git?ref=v1.0.0");
        verify(client, times(1)).getAbsolute(any(), any(URI.class), anyMap(), anyMap());
    }

    @Test
    void relativeModuleSourcesAreResolvedAgainstTheUpstreamDownloadEndpoint() throws Exception {
        when(proxy.fetch(any(), anyString(), any(URI.class), any(), anyMap())).thenReturn(Optional.empty());
        UpstreamClient.Response r1 = response(204, "/archives/demo-1.0.0.tgz");
        when(client.getAbsolute(eq(repo), any(URI.class), anyMap(), anyMap())).thenReturn(r1);
        // the CLIs resolve a relative X-Terraform-Get against the download URL; left as is it would resolve against Granary
        assertThat(upstream.moduleDownload(repo, "acme", "demo", "aws", "1.0.0")).contains("https://registry.example.test/archives/demo-1.0.0.tgz");
        assertThat(storage.text(repo, PATH)).contains("https://registry.example.test/archives/demo-1.0.0.tgz");

        URI endpoint = URI.create(REMOTE + "v1/modules/acme/demo/aws/1.0.0/download");
        assertThat(UpstreamTerraform.resolveRelativeSource(endpoint, "./demo.tgz")).isEqualTo(REMOTE + "v1/modules/acme/demo/aws/1.0.0/demo.tgz");
        assertThat(UpstreamTerraform.resolveRelativeSource(endpoint, "../../files/demo.zip")).isEqualTo(REMOTE + "v1/modules/acme/demo/files/demo.zip");
        assertThat(UpstreamTerraform.resolveRelativeSource(endpoint, "git::https://github.com/acme/demo.git")).isEqualTo("git::https://github.com/acme/demo.git");
        assertThat(UpstreamTerraform.resolveRelativeSource(endpoint, "github.com/acme/demo")).isEqualTo("github.com/acme/demo");
        assertThat(UpstreamTerraform.resolveRelativeSource(endpoint, "https://cdn.example/demo.tgz")).isEqualTo("https://cdn.example/demo.tgz");
        assertThat(UpstreamTerraform.resolveRelativeSource(endpoint, "/bad path with spaces")).isEqualTo("/bad path with spaces");
    }

    @Test
    void staleModuleDownloadIsRevalidatedAndKeptWhenUpstreamFails() throws Exception {
        when(proxy.fetch(any(), anyString(), any(URI.class), any(), anyMap())).thenReturn(Optional.empty());
        storage.put(repo, PATH, "{\"source\":\"https://old.example/demo.tgz\"}");
        storage.find(repo, PATH).orElseThrow().asset().setLastVerifiedAt(Instant.now().minus(Duration.ofHours(2)));

        UpstreamClient.Response r2 = response(204, "https://new.example/demo.tgz");
        when(client.getAbsolute(any(), any(URI.class), anyMap(), anyMap())).thenReturn(r2);
        assertThat(upstream.moduleDownload(repo, "acme", "demo", "aws", "1.0.0")).contains("https://new.example/demo.tgz");

        storage.find(repo, PATH).orElseThrow().asset().setLastVerifiedAt(Instant.now().minus(Duration.ofHours(2)));
        when(client.getAbsolute(any(), any(URI.class), anyMap(), anyMap())).thenThrow(new IOException("connection refused"));
        assertThat(upstream.moduleDownload(repo, "acme", "demo", "aws", "1.0.0")).contains("https://new.example/demo.tgz");
        verify(health).recordFailure(eq(repo), anyString());

        UpstreamClient.Response r3 = response(500, null);
        when(client.getAbsolute(any(), any(URI.class), anyMap(), anyMap())).thenReturn(r3);
        assertThat(upstream.moduleDownload(repo, "acme", "demo", "aws", "1.0.0")).contains("https://new.example/demo.tgz");
        UpstreamClient.Response r4 = response(204, null);
        when(client.getAbsolute(any(), any(URI.class), anyMap(), anyMap())).thenReturn(r4);
        assertThat(upstream.moduleDownload(repo, "acme", "demo", "aws", "1.0.0")).contains("https://new.example/demo.tgz");
    }

    @Test
    void offlineRepositoriesAndOpenCircuitsNeverCallUpstream() throws Exception {
        when(proxy.isOffline(repo)).thenReturn(true);
        assertThat(upstream.moduleDownload(repo, "acme", "demo", "aws", "1.0.0")).isEmpty();
        storage.put(repo, PATH, "{\"source\":\"https://cached.example/demo.tgz\"}");
        storage.find(repo, PATH).orElseThrow().asset().setLastVerifiedAt(Instant.now().minus(Duration.ofDays(1)));
        assertThat(upstream.moduleDownload(repo, "acme", "demo", "aws", "1.0.0")).contains("https://cached.example/demo.tgz");

        when(proxy.isOffline(repo)).thenReturn(false);
        when(health.allowRequest(repo)).thenReturn(false);
        assertThat(upstream.moduleDownload(repo, "acme", "demo", "aws", "1.0.0")).contains("https://cached.example/demo.tgz");
        verify(client, never()).getAbsolute(any(), any(URI.class), anyMap(), anyMap());
    }

    @Test
    void upstream404IsRememberedInTheNegativeCache() throws Exception {
        when(proxy.fetch(any(), anyString(), any(URI.class), any(), anyMap())).thenReturn(Optional.empty());
        UpstreamClient.Response r5 = response(404, null);
        when(client.getAbsolute(any(), any(URI.class), anyMap(), anyMap())).thenReturn(r5);
        assertThat(upstream.moduleDownload(repo, "acme", "demo", "aws", "1.0.0")).isEmpty();
        verify(negativeCache).put(eq(repo.getId()), eq(PATH), eq(Duration.ofMinutes(5)));
        verify(health).recordSuccess(repo);

        when(negativeCache.contains(repo.getId(), PATH)).thenReturn(true);
        assertThat(upstream.moduleDownload(repo, "acme", "demo", "aws", "1.0.0")).isEmpty();
        verify(client, times(1)).getAbsolute(any(), any(URI.class), anyMap(), anyMap());
    }

    @Test
    void moduleFileNeedsACacheableSourceWithTheRequestedExtension() throws Exception {
        when(proxy.fetch(any(), anyString(), any(URI.class), any(), anyMap())).thenReturn(Optional.empty());
        storage.put(repo, PATH, "{\"source\":\"https://files.example/demo.zip//sub\"}");
        assertThat(upstream.moduleFile(repo, "acme", "demo", "aws", "1.0.0", "tgz")).isEmpty();
        verify(proxy, never()).fetch(any(), eq("files/modules/acme/demo/aws/1.0.0.tgz"), any(URI.class), any(), anyMap());

        StoredContent archive = TerraformTestSupport.content(storage.blobs, "files/modules/acme/demo/aws/1.0.0.zip", TerraformTestSupport.zip("m"), false);
        when(proxy.fetch(eq(repo), eq("files/modules/acme/demo/aws/1.0.0.zip"), eq(URI.create("https://files.example/demo.zip")),
                eq(FetchOptions.artifact()), anyMap())).thenReturn(Optional.of(archive));
        assertThat(upstream.moduleFile(repo, "acme", "demo", "aws", "1.0.0", "zip")).contains(archive);

        // a copy already in the cache is served without consulting the document at all
        storage.put(repo, "files/modules/acme/demo/aws/1.0.0.tgz", TerraformTestSupport.tgz("cached"), "application/gzip", false);
        assertThat(upstream.moduleFile(repo, "acme", "demo", "aws", "1.0.0", "tgz")).isPresent();
    }

    @Test
    void providerFileWithNoDocumentAndNoConventionIs404() {
        when(proxy.fetch(any(), anyString(), any(URI.class), any(), anyMap())).thenReturn(Optional.empty());
        assertThat(upstream.providerFile(repo, "acme", "demo", "1.0.0", "random.bin")).isEmpty();
        verify(proxy, never()).fetch(any(), eq("files/acme/demo/1.0.0/random.bin"), any(URI.class), any(), anyMap());
        storage.put(repo, "v1/providers/acme/demo/1.0.0/download/linux/amd64", "{\"download_url\":\"::not a url::\"}");
        assertThat(upstream.providerFile(repo, "acme", "demo", "1.0.0", ":not a url::")).isEmpty();
        storage.put(repo, "v1/providers/acme/demo/1.0.0/download/linux/arm64", "garbage");
        assertThat(upstream.providerFile(repo, "acme", "demo", "1.0.0", "x.zip")).isEmpty();
    }
}
