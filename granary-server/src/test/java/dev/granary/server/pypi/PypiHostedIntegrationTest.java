package dev.granary.server.pypi;

import static dev.granary.server.pypi.PypiFixtures.HTML_V1;
import static dev.granary.server.pypi.PypiFixtures.JSON_V1;
import static dev.granary.server.pypi.PypiFixtures.PIP_ACCEPT;
import static dev.granary.server.pypi.PypiFixtures.filenames;
import static dev.granary.server.pypi.PypiFixtures.metadata;
import static dev.granary.server.pypi.PypiFixtures.object;
import static dev.granary.server.pypi.PypiFixtures.repositoryUrl;
import static dev.granary.server.pypi.PypiFixtures.sdist;
import static dev.granary.server.pypi.PypiFixtures.sha256;
import static dev.granary.server.pypi.PypiFixtures.str;
import static dev.granary.server.pypi.PypiFixtures.text;
import static dev.granary.server.pypi.PypiFixtures.twineUpload;
import static dev.granary.server.pypi.PypiFixtures.uploadSdist;
import static dev.granary.server.pypi.PypiFixtures.uploadWheel;
import static dev.granary.server.pypi.PypiFixtures.wheel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The hosted repository the way twine, pip and uv use it: upload, simple index in both flavours, downloads, deletes. */
class PypiHostedIntegrationTest extends IntegrationTest {

    private static final String REPO = "pypi-hosted";
    private static final String URL = "/repository/" + REPO;
    private static final String NAME = "acme-demo";
    private static final String WHEEL_NAME = "acme_demo-1.0.0-py3-none-any.whl";
    private static final String SDIST_NAME = "acme_demo-1.0.0.tar.gz";
    private static final String WHEEL_PATH = "packages/acme-demo/1.0.0/" + WHEEL_NAME;
    private static final String SDIST_PATH = "packages/acme-demo/1.0.0/" + SDIST_NAME;

    @Autowired
    private ComponentDao components;

    @Autowired
    private StorageService storage;

    private Repository repo;
    private final byte[] wheel = wheel(NAME, "1.0.0");
    private final byte[] sdist = sdist(NAME, "1.0.0");

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "pypi");
    }

    private void upload(String name, String version) throws Exception {
        mvc.perform(uploadWheel(URL + "/", name, version, wheel(name, version)).header("Authorization", adminBasic()))
                .andExpect(status().isOk());
    }

    @Test
    void twineUploadThenPipInstall() throws Exception {
        // twine POSTs to the repository root ("repository = <url>/" in .pypirc)
        MvcResult uploaded = mvc.perform(uploadWheel(URL + "/", NAME, "1.0.0", wheel).header("Authorization", adminBasic()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn();
        assertThat(text(uploaded)).isEqualTo("OK: " + WHEEL_NAME + " stored in " + REPO + "\n");
        mvc.perform(uploadSdist(URL + "/", NAME, "1.0.0", sdist).header("Authorization", adminBasic()))
                .andExpect(status().isOk());

        // the project list
        MvcResult list = mvc.perform(get(URL + "/simple/").accept(PIP_ACCEPT))
                .andExpect(status().isOk())
                .andExpect(content().contentType(JSON_V1))
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andReturn();
        ObjectNode projects = object(list);
        assertThat(str(projects, "meta", "api-version")).isEqualTo("1.1");
        assertThat(projects.path("projects")).hasSize(1);
        assertThat(str(projects.path("projects").get(0), "name")).isEqualTo(NAME);
        assertThat(text(mvc.perform(get(URL + "/simple/")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML)).andReturn()))
                .contains("<a href=\"acme-demo/\">acme-demo</a>")
                .contains("<meta name=\"pypi:repository-version\" content=\"1.1\">");

        // the project page as pip requests it (PEP 691)
        ObjectNode page = object(mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT))
                .andExpect(status().isOk())
                .andExpect(content().contentType(JSON_V1))
                .andReturn());
        assertThat(str(page, "name")).isEqualTo(NAME);
        assertThat(str(page, "meta", "api-version")).isEqualTo("1.1");
        assertThat(filenames(page)).containsExactly(WHEEL_NAME, SDIST_NAME);
        assertThat(page.path("versions").valueStream().map(JsonNode::asString)).containsExactly("1.0.0");
        JsonNode first = page.path("files").get(0);
        assertThat(str(first, "url")).isEqualTo(repositoryUrl(REPO) + "/" + WHEEL_PATH);
        assertThat(str(first, "hashes", "sha256")).isEqualTo(sha256(wheel));
        assertThat(str(first, "requires-python")).isEqualTo(">=3.8");
        assertThat(str(first, "core-metadata", "sha256")).isEqualTo(sha256(metadata(NAME, "1.0.0", ">=3.8", "Demo package " + NAME).getBytes(StandardCharsets.UTF_8)));
        assertThat(str(first, "dist-info-metadata", "sha256")).isEqualTo(str(first, "core-metadata", "sha256"));
        assertThat(first.path("size").longValue()).isEqualTo(wheel.length);
        assertThat(first.path("yanked").booleanValue()).isFalse();
        assertThat(str(first, "upload-time")).isNotNull();

        // the same page as an HTML-only client (PEP 503) sees it
        String html = text(mvc.perform(get(URL + "/simple/acme-demo/").accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andReturn());
        assertThat(html)
                .contains("<meta name=\"pypi:repository-version\" content=\"1.1\">")
                .contains("<a href=\"" + repositoryUrl(REPO) + "/" + WHEEL_PATH + "#sha256=" + sha256(wheel) + "\"")
                .contains("data-requires-python=\"&gt;=3.8\"")
                .contains("data-core-metadata=\"sha256=")
                .contains("data-dist-info-metadata=\"sha256=")
                .contains(">" + WHEEL_NAME + "</a>")
                .contains(">" + SDIST_NAME + "</a>")
                .doesNotContain("data-yanked");
        mvc.perform(get(URL + "/simple/acme-demo/").accept(HTML_V1 + ", text/html;q=0.9"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(HTML_V1));
        mvc.perform(get(URL + "/simple/acme-demo/").param("format", JSON_V1))
                .andExpect(status().isOk())
                .andExpect(content().contentType(JSON_V1));

        // the download and its PEP 658 sidecar
        MvcResult download = mvc.perform(get(URL + "/" + WHEEL_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + WHEEL_NAME + "\""))
                .andExpect(header().string("X-Checksum-Sha256", sha256(wheel)))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(wheel);
        assertThat(text(mvc.perform(get(URL + "/" + WHEEL_PATH + ".metadata")).andExpect(status().isOk()).andReturn()))
                .startsWith("Metadata-Version: 2.4\nName: acme-demo\nVersion: 1.0.0\n")
                .contains("Requires-Python: >=3.8");
        assertThat(mvc.perform(get(URL + "/" + SDIST_PATH)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray()).isEqualTo(sdist);
        mvc.perform(head(URL + "/" + WHEEL_PATH)).andExpect(status().isOk());

        // what was recorded
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", NAME, "1.0.0")).isPresent();
        assertThat(storage.find(repo, WHEEL_PATH)).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isFalse();
            assertThat(c.asset().getDownloadCount()).isEqualTo(1);
        });
        assertThat(storage.find(repo, WHEEL_PATH + ".metadata")).isPresent();
        assertThat(storage.find(repo, SDIST_PATH + ".metadata")).isPresent();
    }

    @Test
    void uploadsAreAcceptedOnTheLegacyUrlToo() throws Exception {
        mvc.perform(uploadSdist(URL + "/legacy/", NAME, "1.0.0", sdist).header("Authorization", adminBasic()))
                .andExpect(status().isOk());
        mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].filename").value(SDIST_NAME));
        mvc.perform(get(URL + "/legacy/")).andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }

    @Test
    void nonCanonicalNamesAreRedirectedLikePypiOrg() throws Exception {
        upload(NAME, "1.0.0");
        for (String spelling : new String[] {"Acme.Demo", "acme_demo/", "ACME-DEMO/index.html", "acme-demo"}) {
            mvc.perform(get(URL + "/simple/" + spelling))
                    .andExpect(status().isMovedPermanently())
                    .andExpect(header().string("Location", repositoryUrl(REPO) + "/simple/acme-demo/"));
        }
        mvc.perform(get(URL + "/simple")).andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", repositoryUrl(REPO) + "/simple/"));
    }

    @Test
    void conditionalGetOfAnIndexPageIs304UntilSomethingChanges() throws Exception {
        upload(NAME, "1.0.0");
        String etag = mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT)).andReturn().getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        upload(NAME, "1.1.0");
        mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT).header("If-None-Match", etag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions").value(org.hamcrest.Matchers.contains("1.0.0", "1.1.0")));
    }

    @Test
    void simplePagesSayTheyVaryByAccept() throws Exception {
        upload(NAME, "1.0.0");
        for (String path : new String[] {"/simple/", "/simple/acme-demo/"}) {
            String etag = mvc.perform(get(URL + path).accept(PIP_ACCEPT)).andExpect(status().isOk())
                    .andExpect(header().string("Vary", containsString("Accept")))
                    .andReturn().getResponse().getHeader("ETag");
            mvc.perform(get(URL + path).accept("text/html")).andExpect(status().isOk())
                    .andExpect(header().string("Vary", containsString("Accept")));
            mvc.perform(get(URL + path).accept(PIP_ACCEPT).header("If-None-Match", etag)).andExpect(status().isNotModified())
                    .andExpect(header().string("Vary", containsString("Accept")));
        }
    }

    @Test
    void concurrentUploadsOfTheSameNewFileStoreExactlyOne() throws Exception {
        int clients = 8;
        List<byte[]> variants = new ArrayList<>();
        List<Callable<MvcResult>> uploads = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            byte[] variant = wheel(NAME, "1.0.0", metadata(NAME, "1.0.0", ">=3.8", "variant " + i));
            variants.add(variant);
            uploads.add(() -> mvc.perform(uploadWheel(URL + "/", NAME, "1.0.0", variant).header("Authorization", adminBasic())).andReturn());
        }
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<MvcResult> results = new ArrayList<>();
        try {
            for (Future<MvcResult> f : pool.invokeAll(uploads)) {
                results.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 200).hasSize(1);
        assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 400).hasSize(clients - 1)
                .allSatisfy(r -> assertThat(text(r)).startsWith("File already exists"));
        // the stored wheel and its sidecar both come from the one upload that was accepted
        int winner = 0;
        while (results.get(winner).getResponse().getStatus() != 200) {
            winner++;
        }
        byte[] stored = mvc.perform(get(URL + "/" + WHEEL_PATH)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(stored).isEqualTo(variants.get(winner));
        assertThat(text(mvc.perform(get(URL + "/" + WHEEL_PATH + ".metadata")).andExpect(status().isOk()).andReturn()))
                .contains("Summary: variant " + winner);
        mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT)).andExpect(jsonPath("$.files.length()").value(1));
    }

    @Test
    void jsonApiDescribesHostedProjects() throws Exception {
        upload(NAME, "1.0.0");
        upload(NAME, "1.1.0");
        ObjectNode doc = object(mvc.perform(get(URL + "/pypi/acme-demo/json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn());
        assertThat(str(doc, "info", "name")).isEqualTo(NAME);
        assertThat(str(doc, "info", "version")).isEqualTo("1.1.0");
        assertThat(str(doc, "info", "summary")).isEqualTo("Demo package acme-demo");
        assertThat(str(doc, "info", "requires_python")).isEqualTo(">=3.8");
        assertThat(doc.path("releases").propertyNames()).containsExactly("1.0.0", "1.1.0");
        assertThat(str(doc.path("releases").path("1.0.0").get(0), "url")).isEqualTo(repositoryUrl(REPO) + "/" + WHEEL_PATH);
        assertThat(str(doc.path("urls").get(0), "filename")).isEqualTo("acme_demo-1.1.0-py3-none-any.whl");

        // the JSON API answers any spelling of the name directly
        mvc.perform(get(URL + "/pypi/Acme.Demo/json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.name").value(NAME));
        mvc.perform(get(URL + "/pypi/acme-demo/1.0.0/json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.version").value("1.0.0"))
                .andExpect(jsonPath("$.urls[0].filename").value(WHEEL_NAME));
        mvc.perform(get(URL + "/pypi/acme-demo/9.9/json")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/pypi/nothing/json")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("content.notFound"));
    }

    @Test
    void reuploadingAFileIsRefusedUnlessRedeployIsAllowed() throws Exception {
        upload(NAME, "1.0.0");
        MvcResult again = mvc.perform(uploadWheel(URL + "/", NAME, "1.0.0", wheel(NAME, "1.0.0", metadata(NAME, "1.0.0", null, "changed")))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn();
        assertThat(text(again)).startsWith("File already exists (" + WHEEL_NAME + ")");
        assertThat(text(mvc.perform(get(URL + "/" + WHEEL_PATH + ".metadata")).andReturn())).contains("Requires-Python: >=3.8");

        Repository snapshots = new Repository();
        snapshots.setName("pypi-snapshots");
        snapshots.setFormat("pypi");
        snapshots.setType(RepositoryType.HOSTED);
        snapshots.setAllowRedeploy(true);
        repositories.create(snapshots);
        for (String summary : new String[] {"first", "second"}) {
            mvc.perform(uploadWheel("/repository/pypi-snapshots/", NAME, "1.0.0", wheel(NAME, "1.0.0", metadata(NAME, "1.0.0", ">=3.9", summary)))
                            .header("Authorization", adminBasic()))
                    .andExpect(status().isOk());
        }
        assertThat(text(mvc.perform(get("/repository/pypi-snapshots/" + WHEEL_PATH + ".metadata")).andReturn()))
                .contains("Summary: second");
    }

    @Test
    void brokenUploadsAreRefusedWithAPlainTextReason() throws Exception {
        mvc.perform(twineUpload(URL + "/", NAME, "1.0.0", "bdist_wheel", WHEEL_NAME, wheel, java.util.Map.of("sha256_digest", "0000"))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("sha256_digest")));
        mvc.perform(twineUpload(URL + "/", "other", "1.0.0", "bdist_wheel", WHEEL_NAME, wheel).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("does not belong to project 'other'")));
        mvc.perform(twineUpload(URL + "/", NAME, "2.0.0", "bdist_wheel", WHEEL_NAME, wheel).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("does not match version '2.0.0'")));
        // the form and file name agree, but the METADATA inside the archive belongs to another release
        mvc.perform(uploadWheel(URL + "/", NAME, "2.0.0", wheel).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("is for version 1.0.0, not 2.0.0")));
        mvc.perform(uploadWheel(URL + "/", "other", "1.0.0", wheel).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("names project 'acme-demo', not 'other'")));
        mvc.perform(twineUpload(URL + "/", NAME, "1.0.0", "bdist_wheel", WHEEL_NAME, "not a zip".getBytes(StandardCharsets.UTF_8))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("not a valid wheel")));
        mvc.perform(twineUpload(URL + "/", NAME, "1.0.0", "bdist_wheel", WHEEL_NAME, wheel, java.util.Map.of(":action", "submit"))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("file_upload")));
        mvc.perform(twineUpload(URL + "/", NAME, "1.0.0", "bdist_wheel", WHEEL_NAME, wheel, java.util.Map.of("protocol_version", "2"))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest());
        mvc.perform(put(URL + "/").content(wheel).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(get(URL + "/simple/acme-demo/")).andExpect(status().isNotFound());
        assertThat(storage.list(repo, "packages/")).isEmpty();
    }

    @Test
    void filesAndVersionsCanBeDeleted() throws Exception {
        upload(NAME, "1.0.0");
        mvc.perform(uploadSdist(URL + "/", NAME, "1.0.0", sdist).header("Authorization", adminBasic())).andExpect(status().isOk());
        upload(NAME, "1.1.0");

        mvc.perform(delete(URL + "/" + SDIST_PATH).header("Authorization", adminBasic())).andExpect(status().isNoContent());
        mvc.perform(get(URL + "/" + SDIST_PATH)).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/" + SDIST_PATH + ".metadata")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()").value(2));

        mvc.perform(delete(URL + "/packages/acme-demo/1.0.0").header("Authorization", adminBasic())).andExpect(status().isNoContent());
        mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()").value(1))
                .andExpect(jsonPath("$.versions[0]").value("1.1.0"));
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", NAME, "1.0.0")).isEmpty();

        mvc.perform(delete(URL + "/packages/acme-demo/1.0.0").header("Authorization", adminBasic())).andExpect(status().isNotFound());
        mvc.perform(delete(URL + "/" + SDIST_PATH).header("Authorization", adminBasic())).andExpect(status().isNotFound());
        mvc.perform(delete(URL + "/packages/acme-demo/1.1.0").header("Authorization", adminBasic())).andExpect(status().isNoContent());
        mvc.perform(get(URL + "/simple/acme-demo/")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/simple/").accept(PIP_ACCEPT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.projects").isEmpty());
    }

    @Test
    void oddPathsAndMethods() throws Exception {
        mvc.perform(get(URL + "/simple/../x/")).andExpect(status().isBadRequest());
        mvc.perform(get(URL + "/packages/a/b/.hidden")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("path.invalid"));
        mvc.perform(get(URL + "/packages/a/b/c")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/robots.txt")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/simple/no-such-project/")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("content.notFound"));
        mvc.perform(put(URL + "/simple/x/").header("Authorization", adminBasic())).andExpect(status().isMethodNotAllowed());
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("pip install --index-url " + repositoryUrl(REPO) + "/simple")));
    }
}
