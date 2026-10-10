package dev.granary.server.helm;

import static dev.granary.server.helm.HelmFixtures.chart;
import static dev.granary.server.helm.HelmFixtures.chartWithYaml;
import static dev.granary.server.helm.HelmFixtures.index;
import static dev.granary.server.helm.HelmFixtures.multipartUpload;
import static dev.granary.server.helm.HelmFixtures.provenance;
import static dev.granary.server.helm.HelmFixtures.repositoryUrl;
import static dev.granary.server.helm.HelmFixtures.sha256;
import static dev.granary.server.helm.HelmFixtures.upload;
import static dev.granary.server.helm.HelmFixtures.url;
import static dev.granary.server.helm.HelmFixtures.versionNumbers;
import static dev.granary.server.helm.HelmFixtures.versions;
import static dev.granary.server.helm.HelmFixtures.yaml;
import static org.assertj.core.api.Assertions.assertThat;
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

import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** A hosted chart repository: uploads through the ChartMuseum API, the generated index.yaml and downloads. */
class HelmHostedIntegrationTest extends IntegrationTest {

    private static final String REPO = "helm-hosted";
    private static final String URL = "/repository/" + REPO;

    @Autowired
    private StorageService storage;

    @Autowired
    private ComponentDao components;

    private Repository repo;
    private String auth;

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "helm");
        auth = basic(ADMIN, tokens.issue(users.require(ADMIN), "helm", null).secret());
    }

    private void uploadOk(byte[] chart) throws Exception {
        mvc.perform(upload(URL, auth, chart))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.saved").value(true));
    }

    private Map<String, Object> indexOf(String path) throws Exception {
        return yaml(mvc.perform(index(path)).andExpect(status().isOk()).andReturn());
    }

    @Test
    void uploadedChartsAreInTheIndexAndDownloadable() throws Exception {
        byte[] demo = chart("demo", "1.0.0");
        uploadOk(demo);

        MvcResult result = mvc.perform(index(URL))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-yaml"))
                .andExpect(header().exists("ETag"))
                .andReturn();
        Map<String, Object> index = yaml(result);
        assertThat(index).containsEntry("apiVersion", "v1").containsKey("generated");
        assertThat(index.get("serverInfo")).isEqualTo(Map.of("contextPath", "/repository/helm-hosted"));
        Map<String, Object> entry = versions(index, "demo").getFirst();
        assertThat(entry).containsEntry("name", "demo").containsEntry("version", "1.0.0").containsEntry("apiVersion", "v2")
                .containsEntry("appVersion", "1.16.0").containsEntry("digest", sha256(demo)).containsKey("created");
        assertThat(url(entry)).isEqualTo(repositoryUrl(REPO) + "/charts/demo-1.0.0.tgz");

        byte[] downloaded = mvc.perform(get(URL + "/charts/demo-1.0.0.tgz"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/gzip"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(demo);
        // the root-level file name some repository managers use is the same file
        assertThat(mvc.perform(get(URL + "/demo-1.0.0.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(demo);
        mvc.perform(head(URL + "/charts/demo-1.0.0.tgz"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", demo.length));

        // revalidation of an unchanged index is a 304
        mvc.perform(index(URL).header("If-None-Match", result.getResponse().getHeader("ETag")))
                .andExpect(status().isNotModified());

        // the version is a component of the repository, its files are its assets
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "demo", "1.0.0")).isPresent();
        assertThat(storage.find(repo, "charts/demo-1.0.0.tgz").orElseThrow().asset().getDownloadCount()).isEqualTo(2);
        assertThat(storage.find(repo, "charts/demo-1.0.0.tgz").orElseThrow().asset().isMetadata()).isFalse();
    }

    @Test
    void theIndexListsVersionsNewestFirstAndChangesWithEveryUpload() throws Exception {
        String before = mvc.perform(index(URL)).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        assertThat(indexOf(URL).get("entries")).isEqualTo(Map.of());

        uploadOk(chart("demo", "1.0.0"));
        uploadOk(chart("demo", "1.10.0"));
        uploadOk(chart("demo", "1.2.0"));
        uploadOk(chart("demo", "2.0.0-rc.1"));
        uploadOk(chart("other", "0.1.0"));

        MvcResult result = mvc.perform(index(URL)).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("ETag")).isNotEqualTo(before);
        Map<String, Object> index = yaml(result);
        assertThat(versionNumbers(index, "demo")).containsExactly("2.0.0-rc.1", "1.10.0", "1.2.0", "1.0.0");
        assertThat(versionNumbers(index, "other")).containsExactly("0.1.0");
    }

    @Test
    void cmPushStyleMultipartUploadsWithProvenance() throws Exception {
        byte[] demo = chart("demo", "1.0.0");
        String prov = provenance("demo", "1.0.0", demo);
        mvc.perform(multipartUpload(URL, auth, demo, prov)).andExpect(status().isCreated()).andExpect(jsonPath("$.saved").value(true));

        mvc.perform(get(URL + "/charts/demo-1.0.0.tgz.prov"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pgp-signature"))
                .andExpect(content().string(prov));

        // a provenance file may also come on its own, before or after the chart
        byte[] other = chart("other", "0.1.0");
        mvc.perform(post(URL + "/api/prov").header("Authorization", auth).content(provenance("other", "0.1.0", other)))
                .andExpect(status().isCreated());
        uploadOk(other);
        mvc.perform(get(URL + "/charts/other-0.1.0.tgz.prov")).andExpect(status().isOk());

        // ... but it has to belong to the archive
        byte[] third = chart("third", "1.0.0");
        mvc.perform(multipartUpload(URL, auth, third, provenance("third", "1.0.0", chart("third", "1.0.0", "different"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("does not match the chart archive")));
        mvc.perform(multipartUpload(URL, auth, third, provenance("demo", "1.0.0", third)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("is for demo 1.0.0, not for third 1.0.0")));
        mvc.perform(get(URL + "/charts/third-1.0.0.tgz")).andExpect(status().isNotFound());
    }

    @Test
    void existingVersionsAreAConflictUnlessRedeployIsAllowed() throws Exception {
        uploadOk(chart("demo", "1.0.0", "first"));
        mvc.perform(upload(URL, auth, chart("demo", "1.0.0", "second")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("already exists")));
        // ChartMuseum's ?force does not override the repository's policy
        mvc.perform(upload(URL, auth, chart("demo", "1.0.0", "second")).param("force", "true"))
                .andExpect(status().isConflict());
        assertThat(versions(indexOf(URL), "demo").getFirst()).containsEntry("description", "first");

        repo.setAllowRedeploy(true);
        repositories.update(REPO, repo);
        byte[] second = chart("demo", "1.0.0", "second");
        uploadOk(second);
        Map<String, Object> entry = versions(indexOf(URL), "demo").getFirst();
        assertThat(entry).containsEntry("description", "second").containsEntry("digest", sha256(second));
        assertThat(mvc.perform(get(URL + "/charts/demo-1.0.0.tgz")).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(second);
    }

    @Test
    void anotherChartCannotOverwriteAnArchiveThroughAFileNameCollision() throws Exception {
        // helm packages both "acme-1" 2.0.0 and "acme" 1-2.0.0 (a valid loose semantic version) as acme-1-2.0.0.tgz
        byte[] owner = chart("acme-1", "2.0.0", "the real chart");
        byte[] impostor = chart("acme", "1-2.0.0", "impostor");
        uploadOk(owner);
        repo.setAllowRedeploy(true);
        repositories.update(REPO, repo);

        mvc.perform(upload(URL, auth, impostor))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("belongs to chart acme-1 2.0.0")));
        mvc.perform(put(URL + "/acme-1-2.0.0.tgz").header("Authorization", auth).content(impostor))
                .andExpect(status().isConflict());
        mvc.perform(delete(URL + "/api/charts/acme/1-2.0.0").header("Authorization", auth)).andExpect(status().isNotFound());

        assertThat(mvc.perform(get(URL + "/charts/acme-1-2.0.0.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(owner);
        Map<String, Object> index = indexOf(URL);
        assertThat(versions(index, "acme-1").getFirst()).containsEntry("digest", sha256(owner)).containsEntry("description", "the real chart");
        assertThat(versions(index, "acme")).isEmpty();
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "acme", "1-2.0.0")).isEmpty();
    }

    @Test
    void chartMuseumApiReads() throws Exception {
        uploadOk(chart("demo", "1.0.0"));
        uploadOk(chart("demo", "1.1.0"));
        uploadOk(chart("other", "0.1.0"));

        mvc.perform(get(URL + "/api/charts").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demo.length()").value(2))
                .andExpect(jsonPath("$.demo[0].version").value("1.1.0"))
                .andExpect(jsonPath("$.demo[0].urls[0]").value(repositoryUrl(REPO) + "/charts/demo-1.1.0.tgz"))
                .andExpect(jsonPath("$.other[0].name").value("other"));
        mvc.perform(get(URL + "/api/charts/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value("1.1.0"))
                .andExpect(jsonPath("$[1].version").value("1.0.0"));
        mvc.perform(get(URL + "/api/charts/demo/1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.digest").exists());
        mvc.perform(get(URL + "/api/charts/demo/latest")).andExpect(jsonPath("$.version").value("1.1.0"));
        mvc.perform(get(URL + "/api/charts/demo/9.9.9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("chart version not found: demo 9.9.9"));
        mvc.perform(get(URL + "/api/charts/nothing")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/health")).andExpect(status().isOk()).andExpect(jsonPath("$.healthy").value(true));
    }

    @Test
    void deletingAVersionRemovesItFromTheIndex() throws Exception {
        byte[] demo = chart("demo", "1.0.0");
        mvc.perform(multipartUpload(URL, auth, demo, provenance("demo", "1.0.0", demo))).andExpect(status().isCreated());
        uploadOk(chart("demo", "2.0.0"));
        uploadOk(chart("demo", "3.0.0"));

        mvc.perform(delete(URL + "/api/charts/demo/1.0.0").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        mvc.perform(get(URL + "/charts/demo-1.0.0.tgz")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/charts/demo-1.0.0.tgz.prov")).andExpect(status().isNotFound());
        mvc.perform(delete(URL + "/api/charts/demo/1.0.0").header("Authorization", auth)).andExpect(status().isNotFound());
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "demo", "1.0.0")).isEmpty();

        // deleting the archive by its file name removes the version as well
        mvc.perform(delete(URL + "/charts/demo-2.0.0.tgz").header("Authorization", auth)).andExpect(status().isOk());
        assertThat(versionNumbers(indexOf(URL), "demo")).containsExactly("3.0.0");

        // so does deleting the component in the UI's browser
        var component = components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "demo", "3.0.0").orElseThrow();
        storage.deleteComponent(component.getId());
        assertThat(versionNumbers(indexOf(URL), "demo")).isEmpty();
    }

    @Test
    void putUploadsLikeOtherRepositoryManagers() throws Exception {
        byte[] demo = chart("demo", "1.0.0");
        mvc.perform(put(URL + "/charts/demo-1.0.0.tgz").header("Authorization", auth).content(demo))
                .andExpect(status().isCreated());
        mvc.perform(put(URL + "/charts/demo-1.0.0.tgz.prov").header("Authorization", auth)
                        .content(provenance("demo", "1.0.0", demo)))
                .andExpect(status().isCreated());
        byte[] root = chart("root", "0.1.0");
        mvc.perform(put(URL + "/root-0.1.0.tgz").header("Authorization", auth).content(root)).andExpect(status().isCreated());
        Map<String, Object> index = indexOf(URL);
        assertThat(versionNumbers(index, "demo")).containsExactly("1.0.0");
        assertThat(url(versions(index, "root").getFirst())).isEqualTo(repositoryUrl(REPO) + "/charts/root-0.1.0.tgz");
    }

    @Test
    void invalidChartsAreRefusedWithTheReason() throws Exception {
        mvc.perform(upload(URL, auth, "<html>not a chart</html>".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid chart: not a gzip-compressed tar archive"));
        mvc.perform(upload(URL, auth, chartWithYaml("demo", "name: demo\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("chart.metadata.version is required")));
        mvc.perform(upload(URL, auth, chartWithYaml("demo", "name: ../demo\nversion: 1.0.0\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("is invalid")));
        mvc.perform(upload(URL, auth, chartWithYaml("demo", "name: demo\nversion: latest\n")))
                .andExpect(status().isBadRequest());
        mvc.perform(upload(URL, auth, new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("no chart")));
        mvc.perform(multipartUpload(URL, auth, null, null)).andExpect(status().isBadRequest());
        assertThat(indexOf(URL).get("entries")).isEqualTo(Map.of());
    }

    @Test
    void maxChartSizeIsEnforced() throws Exception {
        repo.setFormatSettings(Map.of("helm.maxChartSizeMb", "1"));
        repositories.update(REPO, repo);
        byte[] big = new byte[1024 * 1024 + 1];
        mvc.perform(upload(URL, auth, big))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error").value(containsString("1 MiB")));
        mvc.perform(multipartUpload(URL, auth, big, null)).andExpect(status().is(413));
        uploadOk(chart("demo", "1.0.0"));
    }

    @Test
    void relativeUrlsAndTheConfiguredBaseUrl() throws Exception {
        uploadOk(chart("demo", "1.0.0"));
        settings.setBaseUrl("https://charts.example.com/granary");
        Map<String, Object> index = indexOf(URL);
        assertThat(url(versions(index, "demo").getFirst())).isEqualTo("https://charts.example.com/granary/repository/helm-hosted/charts/demo-1.0.0.tgz");
        assertThat(index.get("serverInfo")).isEqualTo(Map.of("contextPath", "/granary/repository/helm-hosted"));

        repo.setFormatSettings(Map.of("helm.relativeUrls", "true"));
        repositories.update(REPO, repo);
        assertThat(url(versions(indexOf(URL), "demo").getFirst())).isEqualTo("charts/demo-1.0.0.tgz");
    }

    @Test
    void artifactHubMetadataAndTheLandingPage() throws Exception {
        mvc.perform(get(URL + "/artifacthub-repo.yml")).andExpect(status().isNotFound());
        String metadata = "repositoryID: 7b1c6fbe-6f43-4a44-9a0b-4f1a6b7e8a00\nowners:\n  - name: ops\n    email: ops@example.com\n";
        mvc.perform(put(URL + "/artifacthub-repo.yml").header("Authorization", auth).content(metadata)).andExpect(status().isCreated());
        mvc.perform(get(URL + "/artifacthub-repo.yml")).andExpect(status().isOk()).andExpect(content().string(metadata));
        mvc.perform(put(URL + "/artifacthub-repo.yml").header("Authorization", auth).content("a: [b"))
                .andExpect(status().isBadRequest());

        mvc.perform(get(URL + "/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("helm repo add granary " + repositoryUrl(REPO))));
    }

    @Test
    void unknownPathsAndMethods() throws Exception {
        mvc.perform(get(URL + "/charts/nothing-1.0.0.tgz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("chart not found: nothing-1.0.0.tgz"));
        mvc.perform(get(URL + "/somewhere/else.txt")).andExpect(status().isNotFound());
        mvc.perform(post(URL + "/index.yaml").header("Authorization", auth))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET, HEAD"));
        mvc.perform(get(java.net.URI.create(URL + "/charts/..%2Fsecret.tgz"))).andExpect(status().isBadRequest());
        // the internal entry documents are not part of the URL space
        uploadOk(chart("demo", "1.0.0"));
        mvc.perform(get(URL + "/entries/demo/1.0.0.json")).andExpect(status().isNotFound());
        assertThat(List.of(storage.find(repo, "entries/demo/1.0.0.json").orElseThrow().asset().isMetadata())).containsExactly(true);
    }
}
