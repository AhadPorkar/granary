package dev.granary.format.terraform;

import static dev.granary.format.terraform.TerraformTestSupport.MAPPER;
import static dev.granary.format.terraform.TerraformTestSupport.baseUrl;
import static dev.granary.format.terraform.TerraformTestSupport.body;
import static dev.granary.format.terraform.TerraformTestSupport.bodyBytes;
import static dev.granary.format.terraform.TerraformTestSupport.gpgKeyJson;
import static dev.granary.format.terraform.TerraformTestSupport.json;
import static dev.granary.format.terraform.TerraformTestSupport.request;
import static dev.granary.format.terraform.TerraformTestSupport.sha256;
import static dev.granary.format.terraform.TerraformTestSupport.signature;
import static dev.granary.format.terraform.TerraformTestSupport.str;
import static dev.granary.format.terraform.TerraformTestSupport.tgz;
import static dev.granary.format.terraform.TerraformTestSupport.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.GranaryException;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.RootRequest;
import dev.granary.core.format.RootRoute;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class TerraformFormatHandlerTest {

    private static final String UPSTREAM = "https://registry.example.test/";
    private static final String NULL_ZIP = "terraform-provider-null_3.2.4_linux_amd64.zip";

    @TempDir
    Path dataDir;

    private final FakeStorage storage = new FakeStorage();
    private final ProxyService proxy = mock(ProxyService.class);
    private final UpstreamClient client = mock(UpstreamClient.class);
    private final UpstreamHealth health = mock(UpstreamHealth.class);
    private final NegativeCache negativeCache = mock(NegativeCache.class);
    private final GroupResolver groups = mock(GroupResolver.class);

    /** What the fake upstream serves, keyed by the cache path the handler asks the proxy for. */
    private final Map<String, byte[]> upstreamByCachePath = new LinkedHashMap<>();
    /** The upstream URI the handler resolved for each cache path. */
    private final Map<String, URI> requestedUris = new LinkedHashMap<>();

    private TerraformFormatHandler handler;
    private Repository hosted;
    private Repository proxied;
    private Repository group;

    @BeforeEach
    void setUp() {
        GranaryProperties properties = new GranaryProperties();
        properties.setDataDir(dataDir);
        handler = new TerraformFormatHandler(storage.mock, proxy, client, health, negativeCache, groups, MAPPER, properties);
        hosted = TerraformTestSupport.hosted("terraform-hosted");
        proxied = TerraformTestSupport.proxy("terraform-registry", UPSTREAM);
        group = TerraformTestSupport.group("terraform-group", "terraform-hosted", "terraform-registry");
        when(groups.leafMembers(group)).thenReturn(List.of(hosted, proxied));
        when(proxy.metadataMaxAge(any())).thenReturn(Duration.ofMinutes(30));
        when(proxy.negativeTtl(any())).thenReturn(Duration.ofMinutes(5));
        when(health.allowRequest(any())).thenReturn(true);
        when(proxy.fetch(any(), anyString(), any(URI.class), any(), anyMap())).thenAnswer(inv -> {
            Repository repo = inv.getArgument(0);
            String cachePath = inv.getArgument(1);
            URI uri = inv.getArgument(2);
            FetchOptions options = inv.getArgument(3);
            requestedUris.put(cachePath, uri);
            Optional<dev.granary.core.storage.StoredContent> cached = storage.find(repo, cachePath);
            if (cached.isPresent() && !options.metadata()) {
                return cached;
            }
            byte[] bytes = upstreamByCachePath.get(cachePath);
            if (bytes == null) {
                return cached;
            }
            return Optional.of(storage.put(repo, cachePath, bytes, options.metadata() ? "application/json" : "application/zip",
                    options.metadata()));
        });
    }

    // --- identity ------------------------------------------------------------------------------

    @Test
    void identityTemplatesAndSettings() {
        assertThat(handler.format()).isEqualTo("terraform");
        assertThat(handler.displayName()).isEqualTo("Terraform / OpenTofu");
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder(RepositoryType.values());

        List<RepositoryTemplate> templates = handler.templates();
        assertThat(templates).extracting(RepositoryTemplate::name)
                .containsExactly("terraform-registry", "opentofu-registry", "terraform-hosted", "terraform-group");
        assertThat(templates.get(0).remoteUrl()).isEqualTo("https://registry.terraform.io/");
        assertThat(templates.get(1).remoteUrl()).isEqualTo("https://registry.opentofu.org/");
        assertThat(templates.get(2).type()).isEqualTo(RepositoryType.HOSTED);
        assertThat(templates.get(3).members()).containsExactly("terraform-hosted", "terraform-registry");
        assertThat(templates.get(3).formatSettings()).containsEntry("terraform.rootRepository", "true");
        assertThat(templates).allSatisfy(t -> assertThat(t.format()).isEqualTo("terraform"));

        assertThat(handler.settings()).extracting(SettingDescriptor::key)
                .containsExactly("terraform.rootRepository", "terraform.providerProtocols");
        assertThat(handler.settings().get(0).type()).isEqualTo(SettingDescriptor.Type.BOOLEAN);
        assertThat(handler.settings().get(1).defaultValue()).isEqualTo("5.0,6.0");
        assertThat(handler.settings().get(1).appliesTo()).containsExactly(RepositoryType.HOSTED);
    }

    @Test
    void validationOfSettings() {
        handler.validate(hosted);
        hosted.setFormatSettings(Map.of("terraform.providerProtocols", "5.0, 6.0", "terraform.rootRepository", "yes"));
        handler.validate(hosted);
        hosted.setFormatSettings(Map.of("terraform.providerProtocols", "five"));
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(GranaryException.class, () -> handler.validate(hosted)).code())
                .isEqualTo("repository.terraform.providerProtocols.invalid");
        hosted.setFormatSettings(Map.of("terraform.rootRepository", "maybe"));
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(GranaryException.class, () -> handler.validate(hosted)).code())
                .isEqualTo("repository.terraform.rootRepository.invalid");
    }

    @Test
    void accessAndMetadataPaths() {
        assertThat(handler.requiredAccess(request(hosted, "GET", ".well-known/terraform.json"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "HEAD", "files/a/b/1.0.0/x.zip"))).isEqualTo(AccessLevel.READ);
        assertThat(handler.requiredAccess(request(hosted, "PUT", "v1/providers/a/b/1.0.0/linux/amd64/x.zip"))).isEqualTo(AccessLevel.WRITE);
        assertThat(handler.requiredAccess(request(hosted, "DELETE", "v1/providers/a/b/1.0.0"))).isEqualTo(AccessLevel.WRITE);

        assertThat(handler.isMetadataPath(".well-known/terraform.json")).isTrue();
        assertThat(handler.isMetadataPath("v1/providers/hashicorp/null/versions")).isTrue();
        assertThat(handler.isMetadataPath("v1/providers/hashicorp/null/3.2.4/download/linux/amd64")).isTrue();
        assertThat(handler.isMetadataPath("v1/modules/a/b/c/1.0.0/download")).isTrue();
        assertThat(handler.isMetadataPath("mirror/registry.terraform.io/hashicorp/null/index.json")).isTrue();
        assertThat(handler.isMetadataPath("providers/acme/demo/gpg-key.json")).isTrue();
        assertThat(handler.isMetadataPath("")).isTrue();
        assertThat(handler.isMetadataPath(null)).isTrue();
        assertThat(handler.isMetadataPath("files/hashicorp/null/3.2.4/" + NULL_ZIP)).isFalse();
        assertThat(handler.isMetadataPath("files/modules/a/b/c/1.0.0.tgz")).isFalse();
        assertThat(handler.isMetadataPath("providers/acme/demo/1.0.0/linux/amd64/x.zip")).isFalse();
    }

    @Test
    void rootRoutePrefersTheFlaggedRepositoryThenGroupsThenAnything() {
        Repository flagged = TerraformTestSupport.hosted("tf-flagged");
        flagged.setFormatSettings(Map.of("terraform.rootRepository", "true"));
        RootRequest.RepositoryLookup lookup = lookup(List.of(hosted, proxied, group, flagged));

        assertThat(handler.routeRoot(new RootRequest("GET", ".well-known/terraform.json", lookup)))
                .contains(new RootRoute("tf-flagged", ".well-known/terraform.json"));
        assertThat(handler.routeRoot(new RootRequest("HEAD", ".well-known/terraform.json", lookup(List.of(hosted, proxied, group)))))
                .contains(new RootRoute("terraform-group", ".well-known/terraform.json"));
        assertThat(handler.routeRoot(new RootRequest("GET", ".well-known/terraform.json", lookup(List.of(proxied, hosted)))))
                .contains(new RootRoute("terraform-registry", ".well-known/terraform.json"));
        assertThat(handler.routeRoot(new RootRequest("GET", ".well-known/terraform.json", lookup(List.of())))).isEmpty();
        assertThat(handler.routeRoot(new RootRequest("PUT", ".well-known/terraform.json", lookup))).isEmpty();
        assertThat(handler.routeRoot(new RootRequest("GET", ".well-known/openid-configuration", lookup))).isEmpty();
        assertThat(handler.routeRoot(new RootRequest("GET", "v1/providers/a/b/versions", lookup))).isEmpty();
    }

    private static RootRequest.RepositoryLookup lookup(List<Repository> repos) {
        return new RootRequest.RepositoryLookup() {
            @Override
            public Optional<Repository> find(String name) {
                return repos.stream().filter(r -> r.getName().equals(name)).findFirst();
            }

            @Override
            public List<Repository> ofFormat(String format) {
                return repos.stream().filter(r -> r.getFormat().equals(format)).toList();
            }
        };
    }

    @Test
    void discoveryDocumentForEveryRepositoryType() throws IOException {
        for (Repository repo : List.of(hosted, proxied, group)) {
            ResponseEntity<?> response = handler.handle(request(repo, "GET", ".well-known/terraform.json"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(response.getHeaders().getETag()).isNotNull();
            JsonNode doc = json(response);
            assertThat(str(doc, "providers.v1")).isEqualTo(baseUrl(repo) + "/v1/providers/");
            assertThat(str(doc, "modules.v1")).isEqualTo(baseUrl(repo) + "/v1/modules/");
        }
        ResponseEntity<?> root = handler.handle(request(hosted, "GET", ""));
        assertThat(str(json(root), "format")).isEqualTo("terraform");
        assertThat(str(json(root), "type")).isEqualTo("hosted");

        ResponseEntity<?> first = handler.handle(request(hosted, "GET", ".well-known/terraform.json"));
        RepositoryRequest conditional = request(hosted, "GET", ".well-known/terraform.json");
        TerraformTestSupport.http(conditional).addHeader(HttpHeaders.IF_NONE_MATCH, first.getHeaders().getETag());
        assertThat(handler.handle(conditional).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        ResponseEntity<?> head = handler.handle(request(hosted, "HEAD", ".well-known/terraform.json"));
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isEqualTo(first.getHeaders().getContentLength());
    }

    // --- hosted providers ------------------------------------------------------------------------

    private ResponseEntity<?> put(Repository repo, String path, byte[] body, String contentType) throws IOException {
        return handler.handle(request(repo, "PUT", path, body, contentType));
    }

    private void uploadNullProvider(Repository repo, String ns, byte[] zipBytes) throws IOException {
        String sums = sha256(zipBytes) + "  " + NULL_ZIP + "\n";
        assertThat(put(repo, "v1/providers/" + ns + "/null/3.2.4/linux/amd64/" + NULL_ZIP, zipBytes, "application/zip").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(put(repo, "v1/providers/" + ns + "/null/3.2.4/SHA256SUMS", sums.getBytes(StandardCharsets.UTF_8), "text/plain").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(put(repo, "v1/providers/" + ns + "/null/3.2.4/SHA256SUMS.sig", signature(), null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(put(repo, "v1/providers/" + ns + "/null/gpg-key", gpgKeyJson("34365D9472D7468F").getBytes(StandardCharsets.UTF_8),
                "application/json").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void hostedProviderUploadProducesTheRegistryDocuments() throws IOException {
        byte[] zipBytes = zip("null-3.2.4");
        uploadNullProvider(hosted, "acme", zipBytes);

        assertThat(storage.paths(hosted)).containsExactly(
                "providers/acme/null/3.2.4/SHA256SUMS", "providers/acme/null/3.2.4/SHA256SUMS.sig",
                "providers/acme/null/3.2.4/linux/amd64/" + NULL_ZIP, "providers/acme/null/gpg-key.json");
        assertThat(storage.coordinates.values()).containsOnly(new StorageService.ComponentCoordinates("acme", "null", "3.2.4"));
        assertThat(storage.find(hosted, "providers/acme/null/gpg-key.json").orElseThrow().asset().isMetadata()).isTrue();
        assertThat(storage.find(hosted, "providers/acme/null/3.2.4/linux/amd64/" + NULL_ZIP).orElseThrow().asset().isMetadata()).isFalse();

        JsonNode versions = json(handler.handle(request(hosted, "GET", "v1/providers/acme/null/versions")));
        assertThat(versions.path("versions")).hasSize(1);
        assertThat(str(versions.path("versions").get(0), "version")).isEqualTo("3.2.4");
        assertThat(versions.path("versions").get(0).path("protocols")).extracting(JsonNode::stringValue).containsExactly("5.0", "6.0");
        assertThat(versions.path("versions").get(0).path("platforms").get(0).path("os").stringValue()).isEqualTo("linux");

        JsonNode download = json(handler.handle(request(hosted, "GET", "v1/providers/acme/null/3.2.4/download/linux/amd64")));
        assertThat(str(download, "filename")).isEqualTo(NULL_ZIP);
        assertThat(str(download, "download_url")).isEqualTo(baseUrl(hosted) + "/files/acme/null/3.2.4/" + NULL_ZIP);
        assertThat(str(download, "shasums_url")).isEqualTo(baseUrl(hosted) + "/files/acme/null/3.2.4/SHA256SUMS");
        assertThat(str(download, "shasums_signature_url")).isEqualTo(baseUrl(hosted) + "/files/acme/null/3.2.4/SHA256SUMS.sig");
        assertThat(str(download, "shasum")).isEqualTo(sha256(zipBytes));
        assertThat(str(download.path("signing_keys").path("gpg_public_keys").get(0), "key_id")).isEqualTo("34365D9472D7468F");
        assertThat(handler.handle(request(hosted, "GET", "v1/providers/acme/null/3.2.4/download/windows/arm64")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<?> file = handler.handle(request(hosted, "GET", "files/acme/null/3.2.4/" + NULL_ZIP));
        assertThat(file.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyBytes(file)).isEqualTo(zipBytes);
        assertThat(file.getHeaders().getETag()).isEqualTo("\"" + sha256(zipBytes) + "\"");
        assertThat(file.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("application/zip"));
        assertThat(file.getHeaders().getContentLength()).isEqualTo(zipBytes.length);
        verify(storage.mock).recordDownload(any());
        assertThat(body(handler.handle(request(hosted, "GET", "files/acme/null/3.2.4/SHA256SUMS")))).contains(NULL_ZIP);
        assertThat(bodyBytes(handler.handle(request(hosted, "GET", "files/acme/null/3.2.4/SHA256SUMS.sig")))).isEqualTo(signature());
        assertThat(handler.handle(request(hosted, "GET", "v1/providers/acme/null/3.2.4/SHA256SUMS")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(handler.handle(request(hosted, "GET", "files/acme/null/3.2.4/other.zip")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode info = json(handler.handle(request(hosted, "GET", "v1/providers/acme/null")));
        assertThat(str(info, "version")).isEqualTo("3.2.4");
        assertThat(info.path("versions")).extracting(JsonNode::stringValue).containsExactly("3.2.4");
        assertThat(str(json(handler.handle(request(hosted, "GET", "v1/providers/acme/null/3.2.4"))), "id")).isEqualTo("acme/null/3.2.4");
        assertThat(handler.handle(request(hosted, "GET", "v1/providers/acme/null/9.9.9")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode keys = json(handler.handle(request(hosted, "GET", "v1/providers/acme/null/gpg-key")));
        assertThat(keys.path("gpg_public_keys")).hasSize(1);

        JsonNode mirrorIndex = json(handler.handle(request(hosted, "GET", "mirror/anything.example/acme/null/index.json")));
        assertThat(mirrorIndex.path("versions").propertyNames()).containsExactly("3.2.4");
        JsonNode mirrorVersion = json(handler.handle(request(hosted, "GET", "mirror/anything.example/acme/null/3.2.4.json")));
        assertThat(str(mirrorVersion.path("archives").path("linux_amd64"), "url")).isEqualTo(baseUrl(hosted) + "/files/acme/null/3.2.4/" + NULL_ZIP);
        assertThat(mirrorVersion.path("archives").path("linux_amd64").path("hashes").get(0).stringValue()).isEqualTo("zh:" + sha256(zipBytes));
    }

    @Test
    void hostedProviderWithoutChecksumsStillHasADownloadDocument() throws IOException {
        byte[] zipBytes = zip("bare");
        assertThat(put(hosted, "v1/providers/acme/bare/1.0.0/darwin/arm64/terraform-provider-bare_1.0.0_darwin_arm64.zip", zipBytes, null)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode download = json(handler.handle(request(hosted, "GET", "v1/providers/acme/bare/1.0.0/download/darwin/arm64")));
        assertThat(str(download, "shasum")).isEqualTo(sha256(zipBytes));
        assertThat(download.path("signing_keys").path("gpg_public_keys")).isEmpty();
        assertThat(handler.handle(request(hosted, "GET", "files/acme/bare/1.0.0/SHA256SUMS")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "v1/providers/acme/bare/gpg-key")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void hostedProviderProtocolsComeFromTheSetting() throws IOException {
        hosted.setFormatSettings(Map.of("terraform.providerProtocols", "6.0"));
        put(hosted, "v1/providers/acme/demo/1.0.0/linux/amd64/terraform-provider-demo_1.0.0_linux_amd64.zip", zip("p"), null);
        JsonNode versions = json(handler.handle(request(hosted, "GET", "v1/providers/acme/demo/versions")));
        assertThat(versions.path("versions").get(0).path("protocols")).extracting(JsonNode::stringValue).containsExactly("6.0");
    }

    @Test
    void redeployRulesForProviders() throws IOException {
        byte[] first = zip("first");
        uploadNullProvider(hosted, "acme", first);
        ResponseEntity<?> again = put(hosted, "v1/providers/acme/null/3.2.4/linux/amd64/" + NULL_ZIP, zip("second"), null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((Map<?, ?>) again.getBody()).get("code")).isEqualTo("repository.terraform.versionExists");
        // a differently named archive for the same platform is the same conflict
        assertThat(put(hosted, "v1/providers/acme/null/3.2.4/linux/amd64/other_name.zip", zip("second"), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(put(hosted, "v1/providers/acme/null/3.2.4/SHA256SUMS", (sha256(first) + "  " + NULL_ZIP + "\n").getBytes(StandardCharsets.UTF_8), null)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(put(hosted, "v1/providers/acme/null/3.2.4/SHA256SUMS.sig", signature(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(bodyBytes(handler.handle(request(hosted, "GET", "files/acme/null/3.2.4/" + NULL_ZIP)))).isEqualTo(first);
        // the signing key is configuration and may always be replaced
        assertThat(put(hosted, "v1/providers/acme/null/gpg-key", gpgKeyJson("NEWKEY").getBytes(StandardCharsets.UTF_8), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(str(json(handler.handle(request(hosted, "GET", "v1/providers/acme/null/gpg-key"))).path("gpg_public_keys").get(0), "key_id"))
                .isEqualTo("NEWKEY");

        // another platform of the same version is fine
        assertThat(put(hosted, "v1/providers/acme/null/3.2.4/darwin/arm64/terraform-provider-null_3.2.4_darwin_arm64.zip", zip("mac"), null)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(handler.handle(request(hosted, "GET", "v1/providers/acme/null/versions"))).path("versions").get(0).path("platforms")).hasSize(2);

        hosted.setAllowRedeploy(true);
        byte[] replacement = zip("replacement");
        assertThat(put(hosted, "v1/providers/acme/null/3.2.4/linux/amd64/renamed.zip", replacement, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.paths(hosted)).doesNotContain("providers/acme/null/3.2.4/linux/amd64/" + NULL_ZIP)
                .contains("providers/acme/null/3.2.4/linux/amd64/renamed.zip");
        assertThat(str(json(handler.handle(request(hosted, "GET", "v1/providers/acme/null/3.2.4/download/linux/amd64"))), "filename"))
                .isEqualTo("renamed.zip");
    }

    @Test
    void aVersionCannotBeUploadedTwiceUnderTwoSpellings() throws IOException {
        byte[] first = zip("first");
        uploadNullProvider(hosted, "acme", first);
        // "v3.2.4" is the same release as "3.2.4": same storage location, same conflict
        assertThat(put(hosted, "v1/providers/acme/null/v3.2.4/linux/amd64/" + NULL_ZIP, zip("second"), null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(put(hosted, "v1/providers/acme/null/v3.2.4/SHA256SUMS", (sha256(first) + "  " + NULL_ZIP + "\n").getBytes(StandardCharsets.UTF_8), null)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(put(hosted, "v1/modules/acme/demo/local/1.0.0.tgz", tgz("one"), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(put(hosted, "v1/modules/acme/demo/local/v1.0.0.tgz", tgz("two"), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // uploads under the "v" spelling land on the canonical name and are found under both
        assertThat(put(hosted, "v1/providers/acme/other/v2.0.0/linux/amd64/terraform-provider-other_2.0.0_linux_amd64.zip", zip("o"), null)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.paths(hosted)).contains("providers/acme/other/2.0.0/linux/amd64/terraform-provider-other_2.0.0_linux_amd64.zip");
        assertThat(json(handler.handle(request(hosted, "GET", "v1/providers/acme/other/versions"))).path("versions").get(0).path("version").stringValue())
                .isEqualTo("2.0.0");
        assertThat(handler.handle(request(hosted, "GET", "v1/providers/acme/other/v2.0.0/download/linux/amd64")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(handler.handle(request(hosted, "GET", "files/acme/other/v2.0.0/terraform-provider-other_2.0.0_linux_amd64.zip")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(storage.paths(hosted).stream().filter(p -> p.contains("/v"))).isEmpty();
    }

    @Test
    void checksumsAreVerifiedInBothUploadOrders() throws IOException {
        byte[] zipBytes = zip("verified");
        String wrong = "0".repeat(64) + "  " + NULL_ZIP + "\n";
        // archive first, then a SHA256SUMS that disagrees
        put(hosted, "v1/providers/acme/null/3.2.4/linux/amd64/" + NULL_ZIP, zipBytes, null);
        ResponseEntity<?> mismatch = put(hosted, "v1/providers/acme/null/3.2.4/SHA256SUMS", wrong.getBytes(StandardCharsets.UTF_8), null);
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) mismatch.getBody()).get("code")).isEqualTo("repository.terraform.checksumMismatch");
        assertThat(storage.paths(hosted)).doesNotContain("providers/acme/null/3.2.4/SHA256SUMS");

        // SHA256SUMS first, then an archive that disagrees
        put(hosted, "v1/providers/acme/null/3.3.0/SHA256SUMS", ("0".repeat(64) + "  terraform-provider-null_3.3.0_linux_amd64.zip\n").getBytes(StandardCharsets.UTF_8), null);
        ResponseEntity<?> rejected = put(hosted, "v1/providers/acme/null/3.3.0/linux/amd64/terraform-provider-null_3.3.0_linux_amd64.zip", zipBytes, null);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(storage.paths(hosted)).doesNotContain("providers/acme/null/3.3.0/linux/amd64/terraform-provider-null_3.3.0_linux_amd64.zip");
        // a file the SHA256SUMS does not mention is accepted
        assertThat(put(hosted, "v1/providers/acme/null/3.3.0/linux/arm64/terraform-provider-null_3.3.0_linux_arm64.zip", zipBytes, null)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void malformedUploadsAre400NotCrashes() throws IOException {
        assertThat(code(put(hosted, "v1/providers/acme/null/3.2.4/linux/amd64/" + NULL_ZIP, new byte[0], null)))
                .isEqualTo("repository.terraform.invalidUpload");
        assertThat(put(hosted, "v1/providers/acme/null/3.2.4/linux/amd64/" + NULL_ZIP, "not a zip".getBytes(StandardCharsets.UTF_8), null)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(hosted, "v1/providers/acme/null/3.2.4/SHA256SUMS", "garbage".getBytes(StandardCharsets.UTF_8), null)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(hosted, "v1/providers/acme/null/3.2.4/SHA256SUMS", new byte[0], null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(hosted, "v1/providers/acme/null/3.2.4/SHA256SUMS.sig", new byte[0], null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(hosted, "v1/providers/acme/null/3.2.4/SHA256SUMS.sig", "plain text".getBytes(StandardCharsets.UTF_8), null)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(hosted, "v1/providers/acme/null/3.2.4/SHA256SUMS.sig", new byte[70_000], null).getStatusCode())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(put(hosted, "v1/providers/acme/null/gpg-key", "{not json".getBytes(StandardCharsets.UTF_8), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(hosted, "v1/providers/acme/null/gpg-key", "{\"key_id\":\"x\"}".getBytes(StandardCharsets.UTF_8), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(hosted, "v1/providers/acme/null/gpg-key", new byte[0], null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(hosted, "v1/modules/acme/demo/local/1.0.0.tgz", "not gzip".getBytes(StandardCharsets.UTF_8), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(hosted, "v1/modules/acme/demo/local/1.0.0.tgz", new byte[0], null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(storage.paths(hosted)).isEmpty();
        assertThat(java.nio.file.Files.list(dataDir.resolve("tmp").resolve("terraform"))).isEmpty(); // temp files cleaned up
    }

    @Test
    void illegalAndUnknownPathsAndMethods() throws IOException {
        assertThat(code(handler.handle(request(hosted, "GET", "v1/providers/../x/versions")))).isEqualTo("path.invalid");
        assertThat(handler.handle(request(hosted, "GET", "v1/providers/a/b/notaversion/download/linux/amd64")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handle(request(hosted, "PUT", "v1/providers/a/b/1.0.0/linux/amd64/x.zip\\y", zip("x"), null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.handle(request(hosted, "GET", "something/odd")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "v1/providers/nope/nope/versions")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "v1/modules/nope/nope/nope/versions")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "v1/modules/nope/nope/nope/download")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "mirror/h/nope/nope/index.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "mirror/h/nope/nope/1.0.0.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "PATCH", "v1/providers/a/b/versions")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "PUT", "v1/providers/a/b/versions", zip("x"), null)).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "POST", ".well-known/terraform.json", zip("x"), null)).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "DELETE", "v1/providers/a/b/versions")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(handler.handle(request(hosted, "DELETE", "v1/providers/a/b/1.0.0")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteRemovesAVersionOrAWholeProvider() throws IOException {
        uploadNullProvider(hosted, "acme", zip("a"));
        put(hosted, "v1/providers/acme/null/3.3.0/linux/amd64/terraform-provider-null_3.3.0_linux_amd64.zip", zip("b"), null);
        assertThat(handler.handle(request(hosted, "DELETE", "v1/providers/acme/null/3.2.4")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storage.paths(hosted)).containsExactly("providers/acme/null/3.3.0/linux/amd64/terraform-provider-null_3.3.0_linux_amd64.zip",
                "providers/acme/null/gpg-key.json");
        assertThat(json(handler.handle(request(hosted, "GET", "v1/providers/acme/null/versions"))).path("versions")).hasSize(1);
        assertThat(handler.handle(request(hosted, "DELETE", "v1/providers/acme/null/3.2.4")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "DELETE", "v1/providers/acme/null/gpg-key")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handler.handle(request(hosted, "DELETE", "v1/providers/acme/null")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storage.paths(hosted)).isEmpty();
        assertThat(handler.handle(request(hosted, "GET", "v1/providers/acme/null/versions")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- hosted modules --------------------------------------------------------------------------

    @Test
    void hostedModuleLifecycle() throws IOException {
        byte[] archive = tgz("demo-1.0.0");
        ResponseEntity<?> created = put(hosted, "v1/modules/acme/demo/local/1.0.0.tgz", archive, "application/gzip");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(str(json(created), "sha256")).isEqualTo(sha256(archive));
        put(hosted, "v1/modules/acme/demo/local/1.1.0-beta.1.tgz", tgz("beta"), null);
        put(hosted, "v1/modules/acme/demo/local/0.9.0.tgz", tgz("old"), null);
        assertThat(storage.coordinates.get(hosted.getId() + "|modules/acme/demo/local/1.0.0.tgz"))
                .isEqualTo(new StorageService.ComponentCoordinates("acme", "demo/local", "1.0.0"));

        JsonNode versions = json(handler.handle(request(hosted, "GET", "v1/modules/acme/demo/local/versions")));
        assertThat(str(versions.path("modules").get(0), "source")).isEqualTo("acme/demo/local");
        assertThat(versions.path("modules").get(0).path("versions")).extracting(v -> v.path("version").stringValue())
                .containsExactly("0.9.0", "1.0.0", "1.1.0-beta.1");

        ResponseEntity<?> download = handler.handle(request(hosted, "GET", "v1/modules/acme/demo/local/1.0.0/download"));
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(download.getHeaders().getFirst("X-Terraform-Get")).isEqualTo(baseUrl(hosted) + "/files/modules/acme/demo/local/1.0.0.tgz");
        ResponseEntity<?> latest = handler.handle(request(hosted, "GET", "v1/modules/acme/demo/local/download"));
        assertThat(latest.getHeaders().getFirst("X-Terraform-Get")).endsWith("/1.0.0.tgz"); // pre-releases are not "latest"
        assertThat(handler.handle(request(hosted, "GET", "v1/modules/acme/demo/local/2.0.0/download")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<?> file = handler.handle(request(hosted, "GET", "files/modules/acme/demo/local/1.0.0.tgz"));
        assertThat(file.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyBytes(file)).isEqualTo(archive);
        assertThat(file.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("application/gzip"));
        assertThat(handler.handle(request(hosted, "GET", "files/modules/acme/demo/local/1.0.0.zip")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bodyBytes(handler.handle(request(hosted, "GET", "v1/modules/acme/demo/local/1.0.0.tgz")))).isEqualTo(archive);

        assertThat(str(json(handler.handle(request(hosted, "GET", "v1/modules/acme/demo/local"))), "version")).isEqualTo("1.0.0");
        assertThat(str(json(handler.handle(request(hosted, "GET", "v1/modules/acme/demo/local/0.9.0"))), "id")).isEqualTo("acme/demo/local/0.9.0");
        assertThat(handler.handle(request(hosted, "GET", "v1/modules/acme/demo/local/5.0.0")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(put(hosted, "v1/modules/acme/demo/local/1.0.0.tgz", tgz("again"), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        hosted.setAllowRedeploy(true);
        assertThat(put(hosted, "v1/modules/acme/demo/local/1.0.0.tgz", tgz("again"), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(bodyBytes(handler.handle(request(hosted, "GET", "files/modules/acme/demo/local/1.0.0.tgz")))).isEqualTo(tgz("again"));

        assertThat(handler.handle(request(hosted, "DELETE", "v1/modules/acme/demo/local/1.0.0")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handler.handle(request(hosted, "DELETE", "v1/modules/acme/demo/local/0.9.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handler.handle(request(hosted, "DELETE", "v1/modules/acme/demo/local")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(handler.handle(request(hosted, "DELETE", "v1/modules/acme/demo/local")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(hosted, "GET", "v1/modules/acme/demo/local/versions")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- read-only repositories --------------------------------------------------------------------

    @Test
    void proxiesAndGroupsRefuseWritesNamingTheHostedMember() throws IOException {
        for (Repository repo : List.of(proxied, group)) {
            ResponseEntity<?> response = put(repo, "v1/providers/acme/null/3.2.4/linux/amd64/" + NULL_ZIP, zip("x"), null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(((Map<?, ?>) response.getBody()).get("code")).isEqualTo("repository.readOnly");
            assertThat(handler.handle(request(repo, "DELETE", "v1/providers/acme/null/3.2.4")).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(put(repo, "v1/modules/acme/demo/local/1.0.0.tgz", tgz("x"), null).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }
        ResponseEntity<?> response = put(group, "v1/modules/acme/demo/local/1.0.0.tgz", tgz("x"), null);
        assertThat(((Map<?, ?>) response.getBody()).get("message").toString()).contains("terraform-hosted");
        verify(storage.mock, never()).store(any(), anyString(), any(), any(), any(), eq(false), any());
    }

    // --- proxy ---------------------------------------------------------------------------------------

    private void stubUpstreamNullProvider(byte[] zipBytes, String sums) {
        upstreamByCachePath.put(".well-known/terraform.json", "{\"providers.v1\":\"/v1/providers/\",\"modules.v1\":\"/v1/modules/\"}".getBytes(StandardCharsets.UTF_8));
        upstreamByCachePath.put("v1/providers/hashicorp/null/versions", ("{\"id\":\"hashicorp/null\",\"versions\":[{\"version\":\"3.2.4\",\"protocols\":[\"5.0\"],"
                + "\"platforms\":[{\"os\":\"linux\",\"arch\":\"amd64\"},{\"os\":\"darwin\",\"arch\":\"arm64\"}]}],\"warnings\":null}").getBytes(StandardCharsets.UTF_8));
        upstreamByCachePath.put("v1/providers/hashicorp/null/3.2.4/download/linux/amd64", downloadDocument("linux", "amd64", sha256(zipBytes)).getBytes(StandardCharsets.UTF_8));
        upstreamByCachePath.put("v1/providers/hashicorp/null/3.2.4/download/darwin/arm64", downloadDocument("darwin", "arm64", "ab".repeat(32)).getBytes(StandardCharsets.UTF_8));
        upstreamByCachePath.put("files/hashicorp/null/3.2.4/" + NULL_ZIP, zipBytes);
        upstreamByCachePath.put("files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS", sums.getBytes(StandardCharsets.UTF_8));
        upstreamByCachePath.put("files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS.sig", signature());
    }

    private static String downloadDocument(String os, String arch, String shasum) {
        String file = "terraform-provider-null_3.2.4_" + os + "_" + arch + ".zip";
        return "{\"protocols\":[\"5.0\"],\"os\":\"" + os + "\",\"arch\":\"" + arch + "\",\"filename\":\"" + file + "\","
                + "\"download_url\":\"https://releases.hashicorp.com/terraform-provider-null/3.2.4/" + file + "\","
                + "\"shasums_url\":\"https://releases.hashicorp.com/terraform-provider-null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS\","
                + "\"shasums_signature_url\":\"https://releases.hashicorp.com/terraform-provider-null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS.sig\","
                + "\"shasum\":\"" + shasum + "\",\"signing_keys\":{\"gpg_public_keys\":[{\"key_id\":\"34365D9472D7468F\",\"ascii_armor\":\"-----BEGIN PGP PUBLIC KEY BLOCK-----\"}]}}";
    }

    @Test
    void proxyRewritesDownloadDocumentsAndFetchesArchivesFromTheOriginalUrls() throws IOException {
        byte[] zipBytes = zip("upstream");
        String sums = sha256(zipBytes) + "  " + NULL_ZIP + "\n";
        stubUpstreamNullProvider(zipBytes, sums);

        JsonNode versions = json(handler.handle(request(proxied, "GET", "v1/providers/hashicorp/null/versions")));
        assertThat(str(versions, "id")).isEqualTo("hashicorp/null"); // passed through untouched
        assertThat(requestedUris.get(".well-known/terraform.json")).isEqualTo(URI.create(UPSTREAM + ".well-known/terraform.json"));
        assertThat(requestedUris.get("v1/providers/hashicorp/null/versions")).isEqualTo(URI.create(UPSTREAM + "v1/providers/hashicorp/null/versions"));

        JsonNode download = json(handler.handle(request(proxied, "GET", "v1/providers/hashicorp/null/3.2.4/download/linux/amd64")));
        assertThat(str(download, "download_url")).isEqualTo(baseUrl(proxied) + "/files/hashicorp/null/3.2.4/" + NULL_ZIP);
        assertThat(str(download, "shasums_url")).isEqualTo(baseUrl(proxied) + "/files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS");
        assertThat(str(download, "shasums_signature_url")).isEqualTo(baseUrl(proxied) + "/files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS.sig");
        assertThat(str(download, "shasum")).isEqualTo(sha256(zipBytes));
        assertThat(str(download.path("signing_keys").path("gpg_public_keys").get(0), "key_id")).isEqualTo("34365D9472D7468F");
        assertThat(storage.text(proxied, "v1/providers/hashicorp/null/3.2.4/download/linux/amd64")).contains("releases.hashicorp.com"); // raw copy cached

        ResponseEntity<?> file = handler.handle(request(proxied, "GET", "files/hashicorp/null/3.2.4/" + NULL_ZIP));
        assertThat(file.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyBytes(file)).isEqualTo(zipBytes);
        assertThat(requestedUris.get("files/hashicorp/null/3.2.4/" + NULL_ZIP))
                .isEqualTo(URI.create("https://releases.hashicorp.com/terraform-provider-null/3.2.4/" + NULL_ZIP));
        assertThat(body(handler.handle(request(proxied, "GET", "files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS")))).isEqualTo(sums);
        assertThat(bodyBytes(handler.handle(request(proxied, "GET", "files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS.sig")))).isEqualTo(signature());
        assertThat(handler.handle(request(proxied, "GET", "files/hashicorp/null/3.2.4/unknown.zip")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void proxyResolvesFilesRequestedBeforeAnyDownloadDocumentWasSeen() throws IOException {
        byte[] zipBytes = zip("direct");
        stubUpstreamNullProvider(zipBytes, sha256(zipBytes) + "  " + NULL_ZIP + "\n");
        // the conventional file name says which platform's document to fetch
        assertThat(bodyBytes(handler.handle(request(proxied, "GET", "files/hashicorp/null/3.2.4/" + NULL_ZIP)))).isEqualTo(zipBytes);
        assertThat(storage.paths(proxied)).contains("v1/providers/hashicorp/null/3.2.4/download/linux/amd64");
        storage.assets.clear();
        requestedUris.clear();
        // for the checksum file any platform will do: the versions document names the first
        assertThat(handler.handle(request(proxied, "GET", "files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(requestedUris).containsKey("v1/providers/hashicorp/null/versions");
    }

    @Test
    void proxyServesTheNetworkMirrorForItsUpstreamHostAndTheGranaryHost() throws IOException {
        byte[] zipBytes = zip("mirror");
        stubUpstreamNullProvider(zipBytes, "");
        JsonNode index = json(handler.handle(request(proxied, "GET", "mirror/registry.example.test/hashicorp/null/index.json")));
        assertThat(index.path("versions").propertyNames()).containsExactly("3.2.4");
        assertThat(handler.handle(request(proxied, "GET", "mirror/granary.test/hashicorp/null/index.json")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(handler.handle(request(proxied, "GET", "mirror/other.example/hashicorp/null/index.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode version = json(handler.handle(request(proxied, "GET", "mirror/registry.example.test/hashicorp/null/3.2.4.json")));
        assertThat(version.path("archives").propertyNames()).containsExactly("darwin_arm64", "linux_amd64");
        assertThat(str(version.path("archives").path("linux_amd64"), "url")).isEqualTo(baseUrl(proxied) + "/files/hashicorp/null/3.2.4/" + NULL_ZIP);
        assertThat(version.path("archives").path("linux_amd64").path("hashes").get(0).stringValue()).isEqualTo("zh:" + sha256(zipBytes));
        assertThat(version.path("archives").path("darwin_arm64").path("hashes").get(0).stringValue()).isEqualTo("zh:" + "ab".repeat(32));
        assertThat(handler.handle(request(proxied, "GET", "mirror/registry.example.test/hashicorp/null/9.9.9.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void proxyMirrorVersionWithoutAnyPlatformDocumentIsAMissNotAnEmptyVersion() throws IOException {
        // the version list is known (cached) but no platform's download document can be had (upstream down)
        upstreamByCachePath.put(".well-known/terraform.json", "{\"providers.v1\":\"/v1/providers/\"}".getBytes(StandardCharsets.UTF_8));
        upstreamByCachePath.put("v1/providers/hashicorp/null/versions", ("{\"versions\":[{\"version\":\"3.2.4\",\"protocols\":[\"5.0\"],"
                + "\"platforms\":[{\"os\":\"linux\",\"arch\":\"amd64\"}]}]}").getBytes(StandardCharsets.UTF_8));
        assertThat(handler.handle(request(proxied, "GET", "mirror/registry.example.test/hashicorp/null/index.json")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // a 404 from the handler lets the dispatcher answer 503 while upstream is unreachable, instead of a 200
        // with no archives that the CLI would turn into "no package for this platform"
        assertThat(handler.handle(request(proxied, "GET", "mirror/registry.example.test/hashicorp/null/3.2.4.json")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(group, "GET", "mirror/registry.example.test/hashicorp/null/3.2.4.json")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void proxyDiscoveryHonoursAbsoluteServiceUrlsAndFallsBackToConventions() throws IOException {
        upstreamByCachePath.put(".well-known/terraform.json", "{\"providers.v1\":\"https://providers.example.test/api/\",\"modules.v1\":\"/mods/v1/\"}".getBytes(StandardCharsets.UTF_8));
        upstreamByCachePath.put("v1/providers/a/b/versions", "{\"versions\":[]}".getBytes(StandardCharsets.UTF_8));
        upstreamByCachePath.put("v1/modules/a/b/c/versions", "{\"modules\":[{\"versions\":[]}]}".getBytes(StandardCharsets.UTF_8));
        handler.handle(request(proxied, "GET", "v1/providers/a/b/versions"));
        handler.handle(request(proxied, "GET", "v1/modules/a/b/c/versions"));
        assertThat(requestedUris.get("v1/providers/a/b/versions")).isEqualTo(URI.create("https://providers.example.test/api/a/b/versions"));
        assertThat(requestedUris.get("v1/modules/a/b/c/versions")).isEqualTo(URI.create("https://registry.example.test/mods/v1/a/b/c/versions"));

        upstreamByCachePath.clear();
        storage.assets.clear();
        upstreamByCachePath.put("v1/providers/a/b/versions", "{\"versions\":[]}".getBytes(StandardCharsets.UTF_8));
        handler.handle(request(proxied, "GET", "v1/providers/a/b/versions"));
        assertThat(requestedUris.get("v1/providers/a/b/versions")).isEqualTo(URI.create("https://registry.example.test/v1/providers/a/b/versions"));
        assertThat(handler.handle(request(proxied, "GET", "v1/providers/x/y/versions")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void proxyModuleFilesComeFromTheRememberedSource() throws IOException {
        storage.put(proxied, "v1/modules/hashicorp/consul/aws/0.11.0/download",
                "{\"source\":\"https://api.github.com/repos/hashicorp/terraform-aws-consul/tarball/v0.11.0//*?archive=tar.gz\"}");
        storage.put(proxied, "v1/modules/acme/git/aws/1.0.0/download", "{\"source\":\"git::https://github.com/acme/mod.git?ref=v1.0.0\"}");
        byte[] archive = tgz("consul");
        upstreamByCachePath.put("files/modules/hashicorp/consul/aws/0.11.0.tgz", archive);

        ResponseEntity<?> download = handler.handle(request(proxied, "GET", "v1/modules/hashicorp/consul/aws/0.11.0/download"));
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(download.getHeaders().getFirst("X-Terraform-Get")).isEqualTo(baseUrl(proxied) + "/files/modules/hashicorp/consul/aws/0.11.0.tgz//*");
        assertThat(handler.handle(request(proxied, "GET", "v1/modules/acme/git/aws/1.0.0/download")).getHeaders().getFirst("X-Terraform-Get"))
                .isEqualTo("git::https://github.com/acme/mod.git?ref=v1.0.0");

        assertThat(bodyBytes(handler.handle(request(proxied, "GET", "files/modules/hashicorp/consul/aws/0.11.0.tgz")))).isEqualTo(archive);
        assertThat(requestedUris.get("files/modules/hashicorp/consul/aws/0.11.0.tgz"))
                .isEqualTo(URI.create("https://api.github.com/repos/hashicorp/terraform-aws-consul/tarball/v0.11.0"));
        assertThat(handler.handle(request(proxied, "GET", "files/modules/hashicorp/consul/aws/0.11.0.zip")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handle(request(proxied, "GET", "files/modules/acme/git/aws/1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- group ----------------------------------------------------------------------------------------

    @Test
    void groupMergesVersionsAndTheFirstMemberThatHasAVersionServesAllOfIt() throws IOException {
        byte[] hostedZip = zip("hosted");
        uploadNullProvider(hosted, "hashicorp", hostedZip);
        put(hosted, "v1/providers/hashicorp/null/4.0.0/linux/amd64/terraform-provider-null_4.0.0_linux_amd64.zip", zip("four"), null);
        byte[] upstreamZip = zip("upstream");
        stubUpstreamNullProvider(upstreamZip, sha256(upstreamZip) + "  " + NULL_ZIP + "\n");

        JsonNode versions = json(handler.handle(request(group, "GET", "v1/providers/hashicorp/null/versions")));
        assertThat(versions.path("versions")).extracting(v -> v.path("version").stringValue()).containsExactly("3.2.4", "4.0.0");
        JsonNode v324 = versions.path("versions").get(0);
        // the hosted member has 3.2.4, so its entry is listed as is: the proxy's darwin build of the same version
        // is not offered, because its checksums and signature are not the hosted ones
        assertThat(v324.path("protocols")).extracting(JsonNode::stringValue).containsExactly("5.0", "6.0");
        assertThat(v324.path("platforms")).extracting(p -> p.path("os").stringValue() + "_" + p.path("arch").stringValue())
                .containsExactly("linux_amd64");

        JsonNode linux = json(handler.handle(request(group, "GET", "v1/providers/hashicorp/null/3.2.4/download/linux/amd64")));
        assertThat(str(linux, "download_url")).isEqualTo(baseUrl(group) + "/files/hashicorp/null/3.2.4/" + NULL_ZIP);
        assertThat(str(linux, "shasum")).isEqualTo(sha256(hostedZip));
        assertThat(str(linux, "shasums_url")).isEqualTo(baseUrl(group) + "/files/hashicorp/null/3.2.4/SHA256SUMS");
        assertThat(handler.handle(request(group, "GET", "v1/providers/hashicorp/null/3.2.4/download/darwin/arm64")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(requestedUris).doesNotContainKey("v1/providers/hashicorp/null/3.2.4/download/darwin/arm64");

        assertThat(bodyBytes(handler.handle(request(group, "GET", "files/hashicorp/null/3.2.4/" + NULL_ZIP)))).isEqualTo(hostedZip);
        assertThat(body(handler.handle(request(group, "GET", "files/hashicorp/null/3.2.4/SHA256SUMS")))).contains(sha256(hostedZip));
        assertThat(handler.handle(request(group, "GET", "files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode info = json(handler.handle(request(group, "GET", "v1/providers/hashicorp/null")));
        assertThat(str(info, "version")).isEqualTo("4.0.0");
        assertThat(info.path("versions")).hasSize(2);
        assertThat(json(handler.handle(request(group, "GET", "v1/providers/hashicorp/null/gpg-key"))).path("gpg_public_keys")).hasSize(1);

        JsonNode mirror = json(handler.handle(request(group, "GET", "mirror/registry.example.test/hashicorp/null/index.json")));
        assertThat(mirror.path("versions").propertyNames()).containsExactly("3.2.4", "4.0.0");
        JsonNode mirrorVersion = json(handler.handle(request(group, "GET", "mirror/registry.example.test/hashicorp/null/3.2.4.json")));
        assertThat(mirrorVersion.path("archives").propertyNames()).containsExactly("linux_amd64");
        assertThat(mirrorVersion.path("archives").path("linux_amd64").path("hashes").get(0).stringValue()).isEqualTo("zh:" + sha256(hostedZip));
        assertThat(handler.handle(request(group, "GET", "mirror/registry.example.test/nobody/nothing/index.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void groupServesAVersionOnlyTheProxyHasEntirelyFromTheProxy() throws IOException {
        put(hosted, "v1/providers/hashicorp/null/4.0.0/linux/amd64/terraform-provider-null_4.0.0_linux_amd64.zip", zip("four"), null);
        byte[] upstreamZip = zip("upstream");
        stubUpstreamNullProvider(upstreamZip, sha256(upstreamZip) + "  " + NULL_ZIP + "\n");

        JsonNode versions = json(handler.handle(request(group, "GET", "v1/providers/hashicorp/null/versions")));
        assertThat(versions.path("versions").get(0).path("platforms")).hasSize(2);
        JsonNode darwin = json(handler.handle(request(group, "GET", "v1/providers/hashicorp/null/3.2.4/download/darwin/arm64")));
        assertThat(str(darwin, "download_url")).isEqualTo(baseUrl(group) + "/files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_darwin_arm64.zip");
        assertThat(str(darwin, "shasums_url")).isEqualTo(baseUrl(group) + "/files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS");
        assertThat(bodyBytes(handler.handle(request(group, "GET", "files/hashicorp/null/3.2.4/" + NULL_ZIP)))).isEqualTo(upstreamZip);
        assertThat(body(handler.handle(request(group, "GET", "files/hashicorp/null/3.2.4/terraform-provider-null_3.2.4_SHA256SUMS")))).contains(NULL_ZIP);
        JsonNode mirrorVersion = json(handler.handle(request(group, "GET", "mirror/registry.example.test/hashicorp/null/3.2.4.json")));
        assertThat(mirrorVersion.path("archives").propertyNames()).containsExactly("darwin_arm64", "linux_amd64");
        // 4.0.0 is the hosted member's alone
        assertThat(str(json(handler.handle(request(group, "GET", "v1/providers/hashicorp/null/4.0.0/download/linux/amd64"))), "download_url"))
                .isEqualTo(baseUrl(group) + "/files/hashicorp/null/4.0.0/terraform-provider-null_4.0.0_linux_amd64.zip");
        assertThat(handler.handle(request(group, "GET", "files/hashicorp/null/4.0.0/terraform-provider-null_4.0.0_linux_amd64.zip"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void groupMergesModulesAndPrefersTheHostedMember() throws IOException {
        put(hosted, "v1/modules/acme/demo/aws/1.0.0.tgz", tgz("h1"), null);
        upstreamByCachePath.put(".well-known/terraform.json", "{\"modules.v1\":\"/v1/modules/\"}".getBytes(StandardCharsets.UTF_8));
        upstreamByCachePath.put("v1/modules/acme/demo/aws/versions", "{\"modules\":[{\"source\":\"acme/demo/aws\",\"versions\":[{\"version\":\"1.0.0\"},{\"version\":\"2.0.0\"}]}]}".getBytes(StandardCharsets.UTF_8));
        storage.put(proxied, "v1/modules/acme/demo/aws/2.0.0/download", "{\"source\":\"https://example.test/demo-2.0.0.tgz\"}");
        storage.put(proxied, "v1/modules/acme/demo/aws/1.0.0/download", "{\"source\":\"https://example.test/demo-1.0.0.tgz\"}");

        JsonNode versions = json(handler.handle(request(group, "GET", "v1/modules/acme/demo/aws/versions")));
        assertThat(versions.path("modules").get(0).path("versions")).extracting(v -> v.path("version").stringValue()).containsExactly("1.0.0", "2.0.0");
        assertThat(handler.handle(request(group, "GET", "v1/modules/acme/demo/aws/1.0.0/download")).getHeaders().getFirst("X-Terraform-Get"))
                .isEqualTo(baseUrl(group) + "/files/modules/acme/demo/aws/1.0.0.tgz");
        assertThat(handler.handle(request(group, "GET", "v1/modules/acme/demo/aws/2.0.0/download")).getHeaders().getFirst("X-Terraform-Get"))
                .isEqualTo(baseUrl(group) + "/files/modules/acme/demo/aws/2.0.0.tgz");
        assertThat(handler.handle(request(group, "GET", "v1/modules/acme/demo/aws/download")).getHeaders().getFirst("X-Terraform-Get"))
                .endsWith("/2.0.0.tgz");
        assertThat(bodyBytes(handler.handle(request(group, "GET", "files/modules/acme/demo/aws/1.0.0.tgz")))).isEqualTo(tgz("h1"));
        assertThat(str(json(handler.handle(request(group, "GET", "v1/modules/acme/demo/aws"))), "version")).isEqualTo("2.0.0");
    }

    private static String code(ResponseEntity<?> response) {
        return String.valueOf(((Map<?, ?>) response.getBody()).get("code"));
    }
}
