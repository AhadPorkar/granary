package dev.granary.server.docker;

import static dev.granary.server.docker.DockerFixtures.OCI_INDEX;
import static dev.granary.server.docker.DockerFixtures.OCI_MANIFEST;
import static dev.granary.server.docker.DockerFixtures.config;
import static dev.granary.server.docker.DockerFixtures.digest;
import static dev.granary.server.docker.DockerFixtures.errorCode;
import static dev.granary.server.docker.DockerFixtures.index;
import static dev.granary.server.docker.DockerFixtures.json;
import static dev.granary.server.docker.DockerFixtures.layer;
import static dev.granary.server.docker.DockerFixtures.manifest;
import static dev.granary.server.docker.DockerFixtures.pushImage;
import static dev.granary.server.docker.DockerFixtures.putManifest;
import static dev.granary.server.docker.DockerFixtures.referrer;
import static dev.granary.server.docker.DockerFixtures.uploadBlob;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** The full push/pull choreography of docker, podman, oras and helm against a hosted repository. */
class DockerHostedIntegrationTest extends IntegrationTest {

    private static final String REPO = "docker-hosted";
    private static final String URL = "/repository/" + REPO;

    @Autowired
    private StorageService storage;

    @Autowired
    private ComponentDao components;

    private Repository repo;

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "docker");
    }

    @Test
    void pingAndVersionHeader() throws Exception {
        mvc.perform(get(URL + "/v2/").header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isOk())
                .andExpect(header().string("Docker-Distribution-API-Version", "registry/2.0"))
                .andExpect(content().json("{}"));
        mvc.perform(get(URL + "/v2").header(HttpHeaders.AUTHORIZATION, adminBasic())).andExpect(status().isOk());
    }

    @Test
    void chunkedPushThenPull() throws Exception {
        byte[] config = config("app");
        byte[] layer = layer("app");
        String configDigest = uploadBlob(mvc, URL, "acme/app", config, adminBasic());

        // the layer goes up in two PATCH chunks, as docker does for large layers
        MvcResult start = mvc.perform(post(URL + "/v2/acme/app/blobs/uploads/").header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Docker-Upload-UUID"))
                .andExpect(header().string("Range", "0-0"))
                .andExpect(header().string("Content-Length", "0"))
                .andReturn();
        String location = start.getResponse().getHeader(HttpHeaders.LOCATION);
        String uuid = start.getResponse().getHeader("Docker-Upload-UUID");
        assertThat(location).isEqualTo("http://localhost" + URL + "/v2/acme/app/blobs/uploads/" + uuid);

        int half = layer.length / 2;
        mvc.perform(patch(URI.create(location)).header(HttpHeaders.AUTHORIZATION, adminBasic())
                        .header(HttpHeaders.CONTENT_RANGE, "0-" + (half - 1))
                        .contentType("application/octet-stream").content(Arrays.copyOfRange(layer, 0, half)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Range", "0-" + (half - 1)))
                .andExpect(header().string(HttpHeaders.LOCATION, location))
                .andExpect(header().string("Docker-Upload-UUID", uuid));
        mvc.perform(get(URI.create(location)).header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Range", "0-" + (half - 1)));
        mvc.perform(patch(URI.create(location)).header(HttpHeaders.AUTHORIZATION, adminBasic())
                        .header(HttpHeaders.CONTENT_RANGE, "0-" + (half - 1))
                        .contentType("application/octet-stream").content(Arrays.copyOfRange(layer, 0, half)))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Range", "0-" + (half - 1)));
        mvc.perform(patch(URI.create(location)).header(HttpHeaders.AUTHORIZATION, adminBasic())
                        .header(HttpHeaders.CONTENT_RANGE, half + "-" + (layer.length - 1))
                        .contentType("application/octet-stream").content(Arrays.copyOfRange(layer, half, layer.length)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Range", "0-" + (layer.length - 1)));
        String layerDigest = digest(layer);
        mvc.perform(put(URI.create(location)).queryParam("digest", layerDigest).header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost" + URL + "/v2/acme/app/blobs/" + layerDigest))
                .andExpect(header().string("Docker-Content-Digest", layerDigest))
                .andExpect(header().string("Content-Length", "0"));
        mvc.perform(get(URI.create(location)).header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("BLOB_UPLOAD_UNKNOWN"));

        byte[] manifest = manifest(OCI_MANIFEST, config, layer);
        String manifestDigest = digest(manifest);
        mvc.perform(put(URL + "/v2/acme/app/manifests/1.0").header(HttpHeaders.AUTHORIZATION, adminBasic())
                        .contentType(OCI_MANIFEST).content(manifest))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost" + URL + "/v2/acme/app/manifests/" + manifestDigest))
                .andExpect(header().string("Docker-Content-Digest", manifestDigest))
                .andExpect(header().string("Content-Length", "0"));

        // what docker asks for on pull
        mvc.perform(head(URL + "/v2/acme/app/manifests/1.0").accept(DockerFixtures.ALL_MANIFESTS))
                .andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", manifestDigest))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, OCI_MANIFEST))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, manifest.length))
                .andExpect(content().bytes(new byte[0]));
        MvcResult pulled = mvc.perform(get(URL + "/v2/acme/app/manifests/" + manifestDigest).accept(DockerFixtures.ALL_MANIFESTS))
                .andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", manifestDigest))
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + manifestDigest + "\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, OCI_MANIFEST))
                .andReturn();
        assertThat(pulled.getResponse().getContentAsByteArray()).isEqualTo(manifest);
        mvc.perform(get(URL + "/v2/acme/app/manifests/1.0").header(HttpHeaders.IF_NONE_MATCH, "\"" + manifestDigest + "\""))
                .andExpect(status().isNotModified());

        MvcResult blob = mvc.perform(get(URL + "/v2/acme/app/blobs/" + layerDigest))
                .andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", layerDigest))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/octet-stream"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, layer.length))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andReturn();
        assertThat(blob.getResponse().getContentAsByteArray()).isEqualTo(layer);
        mvc.perform(head(URL + "/v2/acme/app/blobs/" + configDigest))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, config.length));
        MvcResult range = mvc.perform(get(URL + "/v2/acme/app/blobs/" + layerDigest).header(HttpHeaders.RANGE, "bytes=0-9"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-9/" + layer.length))
                .andReturn();
        assertThat(range.getResponse().getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(layer, 0, 10));

        // storage layout and the component
        assertThat(storage.find(repo, "blobs/sha256/" + layerDigest.substring(7))).isPresent();
        assertThat(storage.find(repo, "manifests/acme/app/sha256/" + manifestDigest.substring(7))).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isFalse();
            assertThat(c.asset().getContentType()).isEqualTo(OCI_MANIFEST);
            assertThat(c.asset().getDownloadCount()).isEqualTo(1);
        });
        assertThat(storage.find(repo, "tags/acme/app/1.0")).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isTrue();
            try {
                assertThat(c.readUtf8()).isEqualTo(manifestDigest);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
        assertThat(components.findAll()).filteredOn(c -> repo.getId().equals(c.getRepositoryId()))
                .extracting(Component::getNamespace, Component::getName, Component::getVersion)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("acme", "app", "1.0"));
        assertThat(storage.find(repo, "tmp")).isEmpty();
    }

    @Test
    void monolithicPostAndCrossRepositoryMount() throws Exception {
        byte[] layer = layer("mono");
        String digest = digest(layer);
        mvc.perform(post(URL + "/v2/acme/app/blobs/uploads/").queryParam("digest", digest)
                        .header(HttpHeaders.AUTHORIZATION, adminBasic())
                        .contentType("application/octet-stream").content(layer))
                .andExpect(status().isCreated())
                .andExpect(header().string("Docker-Content-Digest", digest))
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost" + URL + "/v2/acme/app/blobs/" + digest));

        mvc.perform(post(URL + "/v2/acme/other/blobs/uploads/").queryParam("mount", digest).queryParam("from", "acme/app")
                        .header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Docker-Content-Digest", digest))
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost" + URL + "/v2/acme/other/blobs/" + digest));
        mvc.perform(post(URL + "/v2/acme/other/blobs/uploads/").queryParam("mount", digest(config("x"))).queryParam("from", "acme/app")
                        .header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Docker-Upload-UUID"));

        mvc.perform(post(URL + "/v2/acme/app/blobs/uploads/").queryParam("digest", digest(config("wrong")))
                        .header(HttpHeaders.AUTHORIZATION, adminBasic())
                        .contentType("application/octet-stream").content(layer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DIGEST_INVALID"))
                .andExpect(jsonPath("$.errors[0].detail.actual").value(digest));
    }

    @Test
    void uploadCanBeCancelledAndUnknownSessionsAre404() throws Exception {
        MvcResult start = mvc.perform(post(URL + "/v2/acme/app/blobs/uploads/").header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isAccepted()).andReturn();
        String location = start.getResponse().getHeader(HttpHeaders.LOCATION);
        mvc.perform(delete(URI.create(location)).header(HttpHeaders.AUTHORIZATION, adminBasic())).andExpect(status().isNoContent());
        mvc.perform(patch(URI.create(location)).header(HttpHeaders.AUTHORIZATION, adminBasic()).content("x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("BLOB_UPLOAD_UNKNOWN"));
        mvc.perform(put(URL + "/v2/acme/app/blobs/uploads/does-not-exist").queryParam("digest", digest(layer("x")))
                        .header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isNotFound());
        // completing with the wrong digest keeps the session
        MvcResult again = mvc.perform(post(URL + "/v2/acme/app/blobs/uploads/").header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andReturn();
        String loc2 = again.getResponse().getHeader(HttpHeaders.LOCATION);
        mvc.perform(put(URI.create(loc2)).queryParam("digest", digest(layer("other"))).header(HttpHeaders.AUTHORIZATION, adminBasic())
                        .content(layer("x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DIGEST_INVALID"));
        mvc.perform(get(URI.create(loc2)).header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Range", "0-" + (layer("x").length - 1)));
    }

    @Test
    void manifestValidation() throws Exception {
        byte[] config = config("v");
        byte[] layer = layer("v");
        byte[] manifest = manifest(OCI_MANIFEST, config, layer);
        MvcResult missing = putManifest(mvc, URL, "acme/app", "1.0", manifest, OCI_MANIFEST, adminBasic());
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(missing)).isEqualTo("MANIFEST_BLOB_UNKNOWN");
        assertThat(json(missing).path("errors").path(0).path("detail").path("digest").stringValue()).isEqualTo(digest(config));
        assertThat(missing.getResponse().getHeader("Docker-Distribution-API-Version")).isEqualTo("registry/2.0");

        uploadBlob(mvc, URL, "acme/app", config, adminBasic());
        uploadBlob(mvc, URL, "acme/app", layer, adminBasic());
        MvcResult wrongDigest = putManifest(mvc, URL, "acme/app", digest(layer), manifest, OCI_MANIFEST, adminBasic());
        assertThat(wrongDigest.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(wrongDigest)).isEqualTo("DIGEST_INVALID");
        MvcResult badName = putManifest(mvc, URL, "Acme/App", "1.0", manifest, OCI_MANIFEST, adminBasic());
        assertThat(badName.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(badName)).isEqualTo("NAME_INVALID");
        MvcResult badTag = putManifest(mvc, URL, "acme/app", ".hidden", manifest, OCI_MANIFEST, adminBasic());
        assertThat(errorCode(badTag)).isEqualTo("TAG_INVALID");
        MvcResult garbage = putManifest(mvc, URL, "acme/app", "1.0", "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8),
                OCI_MANIFEST, adminBasic());
        assertThat(garbage.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(garbage)).isEqualTo("MANIFEST_INVALID");

        MvcResult ok = putManifest(mvc, URL, "acme/app", digest(manifest), manifest, OCI_MANIFEST, adminBasic());
        assertThat(ok.getResponse().getStatus()).isEqualTo(201);
        mvc.perform(get(URL + "/v2/acme/app/tags/list")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("acme/app"))
                .andExpect(jsonPath("$.tags").isEmpty());
        mvc.perform(get(URL + "/v2/acme/app/manifests/1.0")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("MANIFEST_UNKNOWN"));
        mvc.perform(get(URL + "/v2/acme/nobody/blobs/" + digest(layer))).andExpect(status().isOk()); // blobs are per repository
        mvc.perform(get(URL + "/v2/acme/app/blobs/" + digest(config("missing")))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("BLOB_UNKNOWN"));
        mvc.perform(get(URL + "/v2/acme/app/blobs/latest")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DIGEST_INVALID"));
    }

    @Test
    void movingATagNeedsRedeployAndDeletesWork() throws Exception {
        DockerFixtures.Image first = pushImage(mvc, URL, "acme/app", "latest", adminBasic());
        byte[] config2 = config("second");
        uploadBlob(mvc, URL, "acme/app", config2, adminBasic());
        byte[] second = manifest(OCI_MANIFEST, config2, first.layer());

        MvcResult same = putManifest(mvc, URL, "acme/app", "latest", first.manifest(), OCI_MANIFEST, adminBasic());
        assertThat(same.getResponse().getStatus()).isEqualTo(201);
        MvcResult moved = putManifest(mvc, URL, "acme/app", "latest", second, OCI_MANIFEST, adminBasic());
        assertThat(moved.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(moved)).isEqualTo("DENIED");

        repo.setAllowRedeploy(true);
        repositories.update(REPO, repo);
        assertThat(putManifest(mvc, URL, "acme/app", "latest", second, OCI_MANIFEST, adminBasic()).getResponse().getStatus()).isEqualTo(201);
        mvc.perform(get(URL + "/v2/acme/app/manifests/latest"))
                .andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", digest(second)));

        // untag, then delete by digest
        mvc.perform(delete(URL + "/v2/acme/app/manifests/latest").header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isAccepted());
        mvc.perform(get(URL + "/v2/acme/app/manifests/latest")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/v2/acme/app/manifests/" + digest(second))).andExpect(status().isOk());
        mvc.perform(delete(URL + "/v2/acme/app/manifests/" + digest(second)).header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isAccepted());
        mvc.perform(delete(URL + "/v2/acme/app/manifests/" + digest(second)).header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("MANIFEST_UNKNOWN"));
        mvc.perform(delete(URL + "/v2/acme/app/blobs/" + digest(config2)).header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isAccepted());
        mvc.perform(get(URL + "/v2/acme/app/blobs/" + digest(config2))).andExpect(status().isNotFound());
    }

    @Test
    void multiPlatformIndexAndReferrers() throws Exception {
        DockerFixtures.Image amd = pushImage(mvc, URL, "acme/multi", "amd64", adminBasic());
        byte[] armConfig = config("arm");
        byte[] armLayer = layer("arm");
        uploadBlob(mvc, URL, "acme/multi", armConfig, adminBasic());
        uploadBlob(mvc, URL, "acme/multi", armLayer, adminBasic());
        byte[] arm = manifest(OCI_MANIFEST, armConfig, armLayer);
        assertThat(putManifest(mvc, URL, "acme/multi", digest(arm), arm, OCI_MANIFEST, adminBasic()).getResponse().getStatus()).isEqualTo(201);

        byte[] list = index(OCI_INDEX, Map.of("amd64", amd.manifest(), "arm64", arm));
        MvcResult pushed = putManifest(mvc, URL, "acme/multi", "1.0", list, OCI_INDEX, adminBasic());
        assertThat(pushed.getResponse().getStatus()).isEqualTo(201);
        MvcResult pulled = mvc.perform(get(URL + "/v2/acme/multi/manifests/1.0").accept(DockerFixtures.ALL_MANIFESTS))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, OCI_INDEX))
                .andExpect(header().string("Docker-Content-Digest", digest(list)))
                .andReturn();
        JsonNode doc = json(pulled);
        assertThat(doc.path("manifests").size()).isEqualTo(2);
        for (JsonNode entry : doc.path("manifests")) {
            mvc.perform(get(URL + "/v2/acme/multi/manifests/" + entry.path("digest").stringValue()))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, OCI_MANIFEST));
        }

        // an SBOM attached to the amd64 manifest
        byte[] sbomBlob = layer("sbom");
        uploadBlob(mvc, URL, "acme/multi", sbomBlob, adminBasic());
        byte[] sbom = referrer(amd.manifest(), "application/vnd.example.sbom", armConfig, sbomBlob);
        mvc.perform(put(URL + "/v2/acme/multi/manifests/" + digest(sbom)).header(HttpHeaders.AUTHORIZATION, adminBasic())
                        .contentType(OCI_MANIFEST).content(sbom))
                .andExpect(status().isCreated())
                .andExpect(header().string("OCI-Subject", amd.digest()));
        mvc.perform(get(URL + "/v2/acme/multi/referrers/" + amd.digest()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, OCI_INDEX))
                .andExpect(jsonPath("$.manifests.length()").value(1))
                .andExpect(jsonPath("$.manifests[0].digest").value(digest(sbom)))
                .andExpect(jsonPath("$.manifests[0].artifactType").value("application/vnd.example.sbom"));
        mvc.perform(get(URL + "/v2/acme/multi/referrers/" + amd.digest()).queryParam("artifactType", "application/vnd.other"))
                .andExpect(status().isOk())
                .andExpect(header().string("OCI-Filters-Applied", "artifactType"))
                .andExpect(jsonPath("$.manifests").isEmpty());
        mvc.perform(get(URL + "/v2/acme/multi/referrers/" + digest(arm)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifests").isEmpty());
    }

    @Test
    void tagListAndCatalogArePaged() throws Exception {
        pushImage(mvc, URL, "acme/app", "1.0", adminBasic());
        DockerFixtures.Image img = pushImage(mvc, URL, "acme/app", "1.1", adminBasic());
        for (String tag : new String[] {"2.0", "latest"}) {
            assertThat(putManifest(mvc, URL, "acme/app", tag, img.manifest(), OCI_MANIFEST, adminBasic()).getResponse().getStatus()).isEqualTo(201);
        }
        pushImage(mvc, URL, "zeta", "1", adminBasic());

        mvc.perform(get(URL + "/v2/acme/app/tags/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("acme/app"))
                .andExpect(jsonPath("$.tags").value(org.hamcrest.Matchers.contains("1.0", "1.1", "2.0", "latest")))
                .andExpect(header().doesNotExist(HttpHeaders.LINK));
        mvc.perform(get(URL + "/v2/acme/app/tags/list").queryParam("n", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags").value(org.hamcrest.Matchers.contains("1.0", "1.1", "2.0")))
                .andExpect(header().string(HttpHeaders.LINK, "<http://localhost" + URL + "/v2/acme/app/tags/list?n=3&last=2.0>; rel=\"next\""));
        mvc.perform(get(URL + "/v2/acme/app/tags/list").queryParam("n", "3").queryParam("last", "2.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags").value(org.hamcrest.Matchers.contains("latest")))
                .andExpect(header().doesNotExist(HttpHeaders.LINK));
        mvc.perform(get(URL + "/v2/nobody/tags/list"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("NAME_UNKNOWN"));

        mvc.perform(get(URL + "/v2/_catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories").value(org.hamcrest.Matchers.contains("acme/app", "zeta")));
        mvc.perform(get(URL + "/v2/_catalog").queryParam("n", "1"))
                .andExpect(jsonPath("$.repositories").value(org.hamcrest.Matchers.contains("acme/app")))
                .andExpect(header().string(HttpHeaders.LINK, "<http://localhost" + URL + "/v2/_catalog?n=1&last=acme%2Fapp>; rel=\"next\""));
    }

    @Test
    void unknownEndpointsAndMethods() throws Exception {
        mvc.perform(get(URL + "/v2/acme/app")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("UNSUPPORTED"));
        mvc.perform(get(URL + "/v1/")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/v2/acme/app/manifests/..%2F..%2Fetc")).andExpect(status().is4xxClientError());
        mvc.perform(post(URL + "/v2/acme/app/manifests/1.0").header(HttpHeaders.AUTHORIZATION, adminBasic()).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errors[0].code").value("UNSUPPORTED"));
        mvc.perform(post(URL + "/v2/_catalog").header(HttpHeaders.AUTHORIZATION, adminBasic())).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void connectSnippetsListEveryClient() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/repositories/" + REPO + "/connect").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("docker login", "docker push", "podman", "skopeo", "buildah", "nerdctl", "hosts.toml",
                "oras", "helm", "imagePullSecrets", "insecure-registries");
    }
}
