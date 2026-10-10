package dev.granary.server.swift;

import static dev.granary.server.swift.SwiftFixtures.ACCEPT_JSON;
import static dev.granary.server.swift.SwiftFixtures.MANIFEST;
import static dev.granary.server.swift.SwiftFixtures.MANIFEST_6;
import static dev.granary.server.swift.SwiftFixtures.METADATA;
import static dev.granary.server.swift.SwiftFixtures.archive;
import static dev.granary.server.swift.SwiftFixtures.getJson;
import static dev.granary.server.swift.SwiftFixtures.getSwift;
import static dev.granary.server.swift.SwiftFixtures.getZip;
import static dev.granary.server.swift.SwiftFixtures.json;
import static dev.granary.server.swift.SwiftFixtures.part;
import static dev.granary.server.swift.SwiftFixtures.publish;
import static dev.granary.server.swift.SwiftFixtures.repositoryUrl;
import static dev.granary.server.swift.SwiftFixtures.sha256;
import static dev.granary.server.swift.SwiftFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/** A hosted Swift registry driven the way SwiftPM drives it: publish, resolve, download, delete. */
class SwiftHostedIntegrationTest extends IntegrationTest {

    private static final String REPO = "swift-hosted";
    private static final String URL = "/repository/" + REPO;
    private static final String BASE = repositoryUrl(REPO);

    @Autowired
    private StorageService storage;

    @Autowired
    private ComponentDao components;

    private Repository repo;

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "swift");
    }

    private MvcResult publishOk(String version, byte[] zip, String metadata) throws Exception {
        return mvc.perform(publish(URL + "/acme/demo/" + version, zip, metadata).header("Authorization", adminBasic()))
                .andExpect(status().isCreated())
                .andReturn();
    }

    @Test
    void publishedReleaseIsServedTheWaySwiftPmResolvesIt() throws Exception {
        byte[] zip = archive("acme.demo", "one", true);
        MvcResult published = publishOk("1.0.0", zip, METADATA);
        assertThat(published.getResponse().getHeader("Location")).isEqualTo(BASE + "/acme/demo/1.0.0");
        assertThat(published.getResponse().getHeader("Content-Version")).isEqualTo("1");
        assertThat(json(published).path("id").stringValue()).isEqualTo("acme.demo");

        // GET <scope>/<name>: the release list
        MvcResult list = mvc.perform(getJson(URL + "/acme/demo"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Version", "1"))
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(jsonPath("$.releases['1.0.0'].url").value(BASE + "/acme/demo/1.0.0"))
                .andReturn();
        assertThat(list.getResponse().getHeader("Link")).isEqualTo("<" + BASE + "/acme/demo/1.0.0>; rel=\"latest-version\", "
                + "<https://github.com/acme/demo>; rel=\"canonical\", <git@github.com:acme/demo.git>; rel=\"alternate\"");

        // GET <scope>/<name>/<version>: the release metadata with the archive checksum
        MvcResult release = mvc.perform(getJson(URL + "/acme/demo/1.0.0"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Version", "1"))
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(jsonPath("$.id").value("acme.demo"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.resources[0].name").value("source-archive"))
                .andExpect(jsonPath("$.resources[0].type").value("application/zip"))
                .andExpect(jsonPath("$.resources[0].checksum").value(sha256(zip)))
                .andExpect(jsonPath("$.metadata.description").value("A demo package"))
                .andExpect(jsonPath("$.metadata.author.name").value("Jane Doe"))
                .andExpect(jsonPath("$.metadata.repositoryURLs[0]").value("https://github.com/acme/demo"))
                .andReturn();
        assertThat(Instant.parse(json(release).path("publishedAt").stringValue())).isBefore(Instant.now().plusSeconds(1));
        assertThat(json(release).path("resources").get(0).has("signing")).isFalse();
        assertThat(release.getResponse().getHeader("Link")).isEqualTo("<" + BASE + "/acme/demo/1.0.0>; rel=\"latest-version\"");
        mvc.perform(getJson(URL + "/acme/demo/1.0.0.json")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("1.0.0"));

        // GET <scope>/<name>/<version>/Package.swift: the manifest, advertising the Swift 6 variant
        MvcResult manifest = mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Version", "1"))
                .andExpect(header().string("Content-Type", "text/x-swift"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"Package.swift\""))
                .andExpect(header().exists("ETag"))
                .andReturn();
        assertThat(text(manifest)).isEqualTo(MANIFEST);
        assertThat(manifest.getResponse().getHeader("Link")).isEqualTo("<" + BASE + "/acme/demo/1.0.0/Package.swift?swift-version=6>; "
                + "rel=\"alternate\"; filename=\"Package@swift-6.swift\"; swift-tools-version=\"6.0\"");

        // SwiftPM 6.1 asks for the variant with the three-part tools version
        for (String swiftVersion : List.of("6.0.0", "6.0", "6")) {
            MvcResult variant = mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift?swift-version=" + swiftVersion))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "text/x-swift"))
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"Package@swift-6.swift\""))
                    .andReturn();
            assertThat(text(variant)).isEqualTo(MANIFEST_6);
        }
        // no variant for that Swift version: 303 to the unversioned manifest
        mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift?swift-version=5.8"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", BASE + "/acme/demo/1.0.0/Package.swift"))
                .andExpect(header().string("Content-Version", "1"));

        // GET <scope>/<name>/<version>.zip: the archive, byte for byte
        MvcResult download = mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Version", "1"))
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"demo-1.0.0.zip\""))
                .andExpect(header().string("Digest", "sha-256=" + Base64.getEncoder().encodeToString(SwiftFixtures.sha256Bytes(zip))))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Length", String.valueOf(zip.length)))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(zip);
        assertThat(storage.find(repo, "acme/demo/1.0.0.zip")).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isFalse();
            assertThat(c.asset().getDownloadCount()).isEqualTo(1);
        });
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "acme", "demo", "1.0.0")).isPresent();
    }

    @Test
    void archivesSupportHeadAndConditionalRequests() throws Exception {
        byte[] zip = archive("cond");
        publishOk("1.0.0", zip, null);
        MvcResult first = mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip")).andExpect(status().isOk()).andReturn();
        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();

        MvcResult headResult = mvc.perform(head(URI.create(URL + "/acme/demo/1.0.0.zip")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", String.valueOf(zip.length)))
                .andExpect(header().string("Content-Type", "application/zip"))
                .andReturn();
        assertThat(headResult.getResponse().getContentAsByteArray()).isEmpty();
        mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip").header("If-None-Match", etag)).andExpect(status().isNotModified());

        // generated JSON documents carry a content ETag too
        String listEtag = mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        mvc.perform(getJson(URL + "/acme/demo").header("If-None-Match", listEtag)).andExpect(status().isNotModified())
                .andExpect(header().string("Content-Version", "1"));
        MvcResult headList = mvc.perform(head(URI.create(URL + "/acme/demo")).header("Accept", ACCEPT_JSON)).andExpect(status().isOk()).andReturn();
        assertThat(headList.getResponse().getContentAsByteArray()).isEmpty();
    }

    @Test
    void releaseListsAreOrderedAndLinkNeighbours() throws Exception {
        for (String v : List.of("1.0.0", "1.10.0", "1.2.0", "2.0.0-beta.1")) {
            publishOk(v, archive(v), null);
        }
        MvcResult list = mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isOk()).andReturn();
        List<String> versions = json(list).path("releases").propertyNames().stream().toList();
        assertThat(versions).containsExactly("2.0.0-beta.1", "1.10.0", "1.2.0", "1.0.0");
        // no repositoryURLs metadata: only the latest-version link
        assertThat(list.getResponse().getHeader("Link")).isEqualTo("<" + BASE + "/acme/demo/2.0.0-beta.1>; rel=\"latest-version\"");

        MvcResult middle = mvc.perform(getJson(URL + "/acme/demo/1.2.0")).andExpect(status().isOk()).andReturn();
        assertThat(middle.getResponse().getHeader("Link")).isEqualTo("<" + BASE + "/acme/demo/2.0.0-beta.1>; rel=\"latest-version\", "
                + "<" + BASE + "/acme/demo/1.10.0>; rel=\"successor-version\", <" + BASE + "/acme/demo/1.0.0>; rel=\"predecessor-version\"");
        MvcResult oldest = mvc.perform(getJson(URL + "/acme/demo/1.0.0")).andExpect(status().isOk()).andReturn();
        assertThat(oldest.getResponse().getHeader("Link")).doesNotContain("predecessor-version").contains("successor-version");
    }

    @Test
    void identitiesAreCaseInsensitive() throws Exception {
        byte[] zip = archive("case");
        mvc.perform(publish(URL + "/Acme/Demo/1.0.0", zip, null).header("Authorization", adminBasic())).andExpect(status().isCreated());
        mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isOk()).andExpect(jsonPath("$.releases['1.0.0']").exists());
        mvc.perform(getJson(URL + "/ACME/DEMO/1.0.0")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value("Acme.Demo"));
        mvc.perform(getZip(URL + "/aCmE/dEmO/1.0.0.zip")).andExpect(status().isOk()).andExpect(content().bytes(zip));
        // the same identity in another spelling is the same release
        mvc.perform(publish(URL + "/acme/demo/1.0.0", zip, null).header("Authorization", adminBasic())).andExpect(status().isConflict());
    }

    @Test
    void republishingIsRefusedUnlessRedeployIsAllowed() throws Exception {
        publishOk("1.0.0", archive("first"), null);
        mvc.perform(publish(URL + "/acme/demo/1.0.0", archive("second"), null).header("Authorization", adminBasic()))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(header().string("Content-Version", "1"))
                .andExpect(jsonPath("$.detail").value("a release with version 1.0.0 already exists"))
                .andExpect(jsonPath("$.code").value("swift.release.exists"));
        mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip")).andExpect(content().bytes(archive("first")));

        Repository redeploy = new Repository();
        redeploy.setName("swift-redeploy");
        redeploy.setFormat("swift");
        redeploy.setType(RepositoryType.HOSTED);
        redeploy.setAllowRedeploy(true);
        repositories.create(redeploy);
        for (String seed : List.of("first", "second")) {
            mvc.perform(publish("/repository/swift-redeploy/acme/demo/1.0.0", archive(seed), null).header("Authorization", adminBasic()))
                    .andExpect(status().isCreated());
        }
        mvc.perform(getZip("/repository/swift-redeploy/acme/demo/1.0.0.zip")).andExpect(content().bytes(archive("second")));
        mvc.perform(getJson("/repository/swift-redeploy/acme/demo/1.0.0"))
                .andExpect(jsonPath("$.resources[0].checksum").value(sha256(archive("second"))));
    }

    @Test
    void identifiersMapSourceRepositoryUrlsToPackages() throws Exception {
        publishOk("1.0.0", archive("ids"), METADATA);
        // SwiftPM sends the URL without percent-encoding it
        for (String url : List.of("https://github.com/acme/demo", "https://github.com/acme/demo.git", "git@github.com:acme/demo.git",
                "HTTPS://GITHUB.COM/Acme/Demo/")) {
            mvc.perform(getJson(URL + "/identifiers?url=" + url))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Version", "1"))
                    .andExpect(jsonPath("$.identifiers[0]").value("acme.demo"))
                    .andExpect(jsonPath("$.identifiers.length()").value(1));
        }
        mvc.perform(getJson(URL + "/identifiers?url=https://github.com/acme/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        mvc.perform(getJson(URL + "/identifiers")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("the url query parameter is required"));

        // a release that stops listing the URL takes the identifier with it once no release lists it
        mvc.perform(delete(URI.create(URL + "/acme/demo/1.0.0")).header("Authorization", adminBasic())).andExpect(status().isNoContent());
        mvc.perform(getJson(URL + "/identifiers?url=https://github.com/acme/demo")).andExpect(status().isNotFound());
    }

    @Test
    void componentsDeletedInTheUiDisappearFromIdentifierLookups() throws Exception {
        publishOk("1.0.0", archive("ui"), METADATA);
        var component = components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "acme", "demo", "1.0.0").orElseThrow();
        mvc.perform(delete(URI.create("/api/v1/repositories/" + REPO + "/components/" + component.getId()))
                .header("Authorization", adminBearer())).andExpect(status().isOk());

        mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isNotFound());
        // the identifiers index is not touched by the management API; the lookup must not name a package without releases
        mvc.perform(getJson(URL + "/identifiers?url=https://github.com/acme/demo")).andExpect(status().isNotFound());
        // publishing again registers the URL again
        publishOk("1.0.0", archive("ui again"), METADATA);
        mvc.perform(getJson(URL + "/identifiers?url=https://github.com/acme/demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.identifiers[0]").value("acme.demo"));
    }

    @Test
    void deletingReleasesAndPackages() throws Exception {
        publishOk("1.0.0", archive("a"), null);
        publishOk("1.1.0", archive("b"), null);
        mvc.perform(delete(URI.create(URL + "/acme/demo/1.0.0")).header("Authorization", adminBasic()))
                .andExpect(status().isNoContent()).andExpect(header().string("Content-Version", "1"));
        mvc.perform(getJson(URL + "/acme/demo/1.0.0")).andExpect(status().isNotFound());
        mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip")).andExpect(status().isNotFound());
        mvc.perform(getJson(URL + "/acme/demo")).andExpect(jsonPath("$.releases['1.0.0']").doesNotExist())
                .andExpect(jsonPath("$.releases['1.1.0']").exists());
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "acme", "demo", "1.0.0")).isEmpty();
        mvc.perform(delete(URI.create(URL + "/acme/demo/1.0.0")).header("Authorization", adminBasic())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("release acme.demo 1.0.0 not found"));

        mvc.perform(delete(URI.create(URL + "/acme/demo")).header("Authorization", adminBasic())).andExpect(status().isNoContent());
        mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("package acme.demo not found"));
        assertThat(storage.list(repo, "acme/")).isEmpty();
        // the version can be published again once deleted
        publishOk("1.0.0", archive("c"), null);
    }

    @Test
    void invalidArchivesAreRefusedWith422() throws Exception {
        Map<String, String> atRoot = new LinkedHashMap<>();
        atRoot.put("Package.swift", MANIFEST);
        atRoot.put("Sources/Demo/Demo.swift", "");
        expectProblem(publish(URL + "/acme/demo/1.0.0", SwiftFixtures.zip(atRoot), null), 422, "single top-level directory");

        expectProblem(publish(URL + "/acme/demo/1.0.0", SwiftFixtures.zip(Map.of("demo/Sources/Demo/Demo.swift", "")), null), 422,
                "package doesn't contain a valid manifest (Package.swift) file in 'demo/'");
        expectProblem(publish(URL + "/acme/demo/1.0.0", SwiftFixtures.zip(Map.of("demo/Package.swift", "import PackageDescription")), null),
                422, "swift-tools-version");
        expectProblem(publish(URL + "/acme/demo/1.0.0", "not a zip".getBytes(StandardCharsets.UTF_8), null), 422, "not a valid zip file");
        expectProblem(publish(URL + "/acme/demo/1.0.0", SwiftFixtures.zip(Map.of("demo/../../evil", "x", "demo/Package.swift", MANIFEST)), null),
                422, "unsafe entry name");
        expectProblem(publish(URL + "/acme/demo/1.0.0", new byte[0], null), 422, "empty");
        assertThat(storage.list(repo, "")).isEmpty();
    }

    @Test
    void invalidMetadataIsRefusedWith422() throws Exception {
        expectProblem(publish(URL + "/acme/demo/1.0.0", archive("m"), "{not json"), 422, "invalid JSON provided for release metadata");
        expectProblem(publish(URL + "/acme/demo/1.0.0", archive("m"), "[]"), 422, "release metadata must be a JSON object");
        expectProblem(publish(URL + "/acme/demo/1.0.0", archive("m"), "{\"author\":{}}"), 422, "author.name is required");
        expectProblem(publish(URL + "/acme/demo/1.0.0", archive("m"), "{\"repositoryURLs\":\"https://x\"}"), 422,
                "repositoryURLs must be an array of strings");
        expectProblem(publish(URL + "/acme/demo/1.0.0", archive("m"), "{\"originalPublicationTime\":\"yesterday\"}"), 422,
                "originalPublicationTime 'yesterday' is not an ISO 8601 date-time");
        // the time is normalised to whole seconds in UTC
        publishOk("1.0.0", archive("m"), "{\"originalPublicationTime\":\"2024-01-31T13:00:00.250+01:00\",\"x-custom\":[1,2]}");
        mvc.perform(getJson(URL + "/acme/demo/1.0.0"))
                .andExpect(jsonPath("$.metadata.originalPublicationTime").value("2024-01-31T12:00:00Z"))
                .andExpect(jsonPath("$.metadata.x-custom[1]").value(2));
    }

    @Test
    void malformedPublishRequests() throws Exception {
        expectProblem(publish(URL + "/acme/demo/1.0.0", null, METADATA), 400, "no 'source-archive' part");
        mvc.perform(put(URI.create(URL + "/acme/demo/1.0.0")).header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("multipart/form-data")));
        expectProblem(publish(URL + "/acme/demo/1.0", archive("x"), null), 400, "'1.0' is not a valid semantic version");
        expectProblem(publish(URL + "/acme/-demo/1.0.0", archive("x"), null), 400, "'-demo' is not a valid package name");
        expectProblem(publish(URL + "/ac_me/demo/1.0.0", archive("x"), null), 400, "'ac_me' is not a valid package scope");
        expectProblem(publish(URL + "/acme/demo", archive("x"), null), 405, "Publish with PUT <scope>/<name>/<version>");
        expectProblem(publish(URL + "/acme/demo/1.0.0-rc.json/Package.swift", archive("x"), null), 405, "Publish with PUT");
        expectProblem(publish(URL + "/acme/demo/1.0.0-a.json", archive("x"), null), 422, "cannot be told apart");
        expectProblem(publish(URL + "/acme/demo/1.0.0-rc.zip", archive("x"), null), 422, "version 1.0.0-rc.zip ends in .zip");
        expectProblem(publish(URL + "/acme/demo/1.0.0.zip", archive("x"), null), 405, "Publish with PUT");
        mvc.perform(publish(URL + "/acme/demo/1.0.0", archive("x"), null).header("Authorization", adminBasic())
                        .with(r -> {
                            r.removeHeader("Accept");
                            r.addHeader("Accept", "application/vnd.swift.registry.v2+json");
                            return r;
                        }))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.detail").value("unsupported API version"));
        assertThat(storage.list(repo, "")).isEmpty();
    }

    @Test
    void aBareZipBodyIsAcceptedAsTheArchive() throws Exception {
        byte[] zip = archive("bare");
        mvc.perform(put(URI.create(URL + "/acme/demo/1.0.0")).header("Authorization", adminBasic())
                        .contentType("application/zip").content(zip))
                .andExpect(status().isCreated());
        mvc.perform(getJson(URL + "/acme/demo/1.0.0")).andExpect(jsonPath("$.resources[0].checksum").value(sha256(zip)));
    }

    @Test
    void signedReleasesKeepTheirSignature() throws Exception {
        byte[] zip = archive("signed");
        byte[] signature = {0x30, (byte) 0x82, 0x01, 0x02, 0x03};
        mvc.perform(publish(URL + "/acme/demo/1.0.0", zip, METADATA).header("Authorization", adminBasic())
                        .part(part("source-archive-signature", signature, "application/octet-stream", "binary"))
                        .header("X-Swift-Package-Signature-Format", "cms-1.0.0"))
                .andExpect(status().isCreated());
        String encoded = Base64.getEncoder().encodeToString(signature);
        mvc.perform(getJson(URL + "/acme/demo/1.0.0"))
                .andExpect(jsonPath("$.resources[0].signing.signatureBase64Encoded").value(encoded))
                .andExpect(jsonPath("$.resources[0].signing.signatureFormat").value("cms-1.0.0"));
        mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip"))
                .andExpect(header().string("X-Swift-Package-Signature-Format", "cms-1.0.0"))
                .andExpect(header().string("X-Swift-Package-Signature", encoded));

        // a signature needs its format
        expectProblem(publish(URL + "/acme/demo/1.1.0", zip, null)
                .part(part("source-archive-signature", signature, "application/octet-stream", "binary")), 400,
                "X-Swift-Package-Signature-Format is required");
    }

    @Test
    void repositorySettingsRequireSignaturesAndLimitArchiveSize() throws Exception {
        Repository strict = new Repository();
        strict.setName("swift-strict");
        strict.setFormat("swift");
        strict.setType(RepositoryType.HOSTED);
        strict.setFormatSettings(Map.of("swift.requireSignature", "true", "swift.maxArchiveSizeMb", "1"));
        repositories.create(strict);
        String url = "/repository/swift-strict/acme/demo/1.0.0";
        expectProblem(publish(url, archive("x"), null), 422, "only accepts signed releases");

        byte[] big = new byte[1024 * 1024 + 1];
        expectProblem(publish(url, big, null).part(part("source-archive-signature", new byte[] {1}, "application/octet-stream", "binary"))
                .header("X-Swift-Package-Signature-Format", "cms-1.0.0"), 413, "may not exceed 1048576 bytes");

        mvc.perform(publish(url, archive("x"), null).header("Authorization", adminBasic())
                        .part(part("source-archive-signature", new byte[] {1}, "application/octet-stream", "binary"))
                        .header("X-Swift-Package-Signature-Format", "cms-1.0.0"))
                .andExpect(status().isCreated());
    }

    @Test
    void protocolEndpointsAndErrors() throws Exception {
        mvc.perform(get(URI.create(URL + "/acme/demo")).header("Accept", "application/vnd.swift.registry.v2+json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        mvc.perform(get(URI.create(URL + "/acme/demo")).header("Accept", "application/vnd.swift.registry.vx+json"))
                .andExpect(status().isBadRequest());
        // browsers and curl without a registry media type get version 1
        mvc.perform(get(URI.create(URL + "/acme/demo")).header("Accept", "text/html,*/*")).andExpect(status().isNotFound())
                .andExpect(header().string("Content-Version", "1"))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("package acme.demo not found"));

        mvc.perform(getJson(URL + "/acme/demo/1.0.0")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("release acme.demo 1.0.0 not found"));
        mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift")).andExpect(status().isNotFound());
        mvc.perform(getJson(URL + "/acme/demo/not-semver")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'not-semver' is not a valid semantic version"));
        mvc.perform(getJson(URL + "/acme/demo/1.0.0/README.md")).andExpect(status().isNotFound());
        mvc.perform(getJson(URL + "/acme/demo/../../etc/passwd")).andExpect(status().isBadRequest());
        mvc.perform(get(URI.create(URL + "/acme%2Fdemo/1.0.0"))).andExpect(status().isBadRequest());

        mvc.perform(get(URI.create(URL + "/availability"))).andExpect(status().isOk()).andExpect(header().string("Content-Version", "1"));
        mvc.perform(get(URI.create(URL + "/"))).andExpect(status().isOk()).andExpect(jsonPath("$.format").value("swift"))
                .andExpect(jsonPath("$.url").value(BASE));
        // OPTIONS is answered by Spring MVC itself for the catch-all repository mapping
        mvc.perform(options(URI.create(URL + "/acme/demo"))).andExpect(status().isOk())
                .andExpect(header().string("Allow", containsString("PUT")));
        mvc.perform(post(URI.create(URL + "/acme/demo/1.0.0")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET, HEAD, PUT, DELETE"))
                .andExpect(header().string("Content-Type", "application/problem+json"));
        mvc.perform(put(URI.create(URL + "/acme/demo/1.0.0.zip")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "GET, HEAD"));
        mvc.perform(delete(URI.create(URL + "/identifiers")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "GET, HEAD"));
        mvc.perform(MockMvcRequestBuilders.request(HttpMethod.PATCH, URI.create(URL + "/acme/demo")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "GET, HEAD, DELETE"));
    }

    @Test
    void encodedSlashesNeverAddressAnotherPath() throws Exception {
        byte[] zip = archive("encoded");
        publishOk("1.0.0", zip, METADATA);
        // decoded, each of these would name an existing release, manifest or archive
        for (String raw : List.of("/acme%2Fdemo/1.0.0", "/acme%2fdemo/1.0.0.zip", "/acme/demo%2F1.0.0", "/acme/demo/1.0.0%2FPackage.swift")) {
            mvc.perform(getJson(URL + raw))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Content-Type", "application/problem+json"))
                    .andExpect(header().string("Content-Version", "1"))
                    .andExpect(jsonPath("$.detail", containsString("encoded slashes")));
            mvc.perform(head(URI.create(URL + raw))).andExpect(status().isBadRequest());
        }
        mvc.perform(publish(URL + "/acme%2Fdemo/1.1.0", zip, null).header("Authorization", adminBasic())).andExpect(status().isBadRequest());
        mvc.perform(delete(URI.create(URL + "/acme%2Fdemo/1.0.0")).header("Authorization", adminBasic())).andExpect(status().isBadRequest());
        assertThat(storage.exists(repo, "acme/demo/1.1.0.zip")).isFalse();
        mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip")).andExpect(status().isOk()).andExpect(content().bytes(zip));
        // a percent-encoded URL in the identifiers query is fine
        mvc.perform(getJson(URL + "/identifiers?url=https%3A%2F%2Fgithub.com%2Facme%2Fdemo"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.identifiers[0]").value("acme.demo"));
    }

    @Test
    void loginChecksCredentialsWhereSwiftPmSendsThem() throws Exception {
        // swift package-registry login <url>/repository/swift-hosted posts to the registry URL itself
        mvc.perform(post(URI.create(URL)).header("Authorization", adminBasic()).header("Accept", "*/*")
                        .header("User-Agent", SwiftFixtures.USER_AGENT))
                .andExpect(status().isOk()).andExpect(header().string("Content-Version", "1"));
        mvc.perform(post(URI.create(URL + "/login")).header("Authorization", adminBasic())).andExpect(status().isOk());
        mvc.perform(post(URI.create(URL))).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().string("Content-Type", "application/problem+json"));
        mvc.perform(get(URI.create(URL + "/login"))).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "POST"));
    }

    private void expectProblem(AbstractMockHttpServletRequestBuilder<?> request, int status,
                               String detail) throws Exception {
        MvcResult result = mvc.perform(request.header("Authorization", adminBasic()))
                .andExpect(status().is(status))
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(header().string("Content-Version", "1"))
                .andReturn();
        JsonNode problem = json(result);
        assertThat(problem.path("detail").stringValue()).contains(detail);
        assertThat(problem.path("status").intValue()).isEqualTo(status);
    }
}
