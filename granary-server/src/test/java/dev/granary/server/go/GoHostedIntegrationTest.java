package dev.granary.server.go;

import static dev.granary.server.go.GoFixtures.MODULE;
import static dev.granary.server.go.GoFixtures.bytes;
import static dev.granary.server.go.GoFixtures.curlPut;
import static dev.granary.server.go.GoFixtures.goGet;
import static dev.granary.server.go.GoFixtures.goMod;
import static dev.granary.server.go.GoFixtures.json;
import static dev.granary.server.go.GoFixtures.moduleZip;
import static dev.granary.server.go.GoFixtures.sha256;
import static dev.granary.server.go.GoFixtures.text;
import static dev.granary.server.go.GoFixtures.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** A hosted Go repository: publishing with {@code curl -T}, every GOPROXY endpoint, validation and deletes. */
class GoHostedIntegrationTest extends IntegrationTest {

    private static final String REPO = "go-hosted";

    @Autowired
    private StorageService storage;

    private Repository repo;

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "go");
    }

    private MvcResult publish(String module, String escapedModule, String version, byte[] zip) throws Exception {
        return mvc.perform(curlPut(REPO, escapedModule + "/@v/" + version + ".zip", zip).header("Authorization", adminBasic()))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MvcResult publish(String version) throws Exception {
        return publish(MODULE, MODULE, version, moduleZip(MODULE, version));
    }

    @Test
    void aPublishedVersionIsServedThroughEveryProtocolEndpoint() throws Exception {
        byte[] zip = moduleZip(MODULE, "v0.1.0");
        Instant before = Instant.now().minusSeconds(1);
        JsonNode created = json(publish(MODULE, MODULE, "v0.1.0", zip));
        assertThat(created.path("module").asString()).isEqualTo(MODULE);
        assertThat(created.path("version").asString()).isEqualTo("v0.1.0");
        assertThat(created.path("files").valueStream().map(JsonNode::asString)).containsExactly(
                MODULE + "/@v/v0.1.0.zip", MODULE + "/@v/v0.1.0.mod", MODULE + "/@v/v0.1.0.info");

        assertThat(text(mvc.perform(goGet(REPO, MODULE + "/@v/list"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().exists("ETag"))
                .andReturn())).isEqualTo("v0.1.0\n");

        JsonNode info = json(mvc.perform(goGet(REPO, MODULE + "/@v/v0.1.0.info"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn());
        assertThat(info.path("Version").asString()).isEqualTo("v0.1.0");
        Instant time = Instant.parse(info.path("Time").asString());
        assertThat(time).isBetween(before, Instant.now().plusSeconds(1));

        assertThat(text(mvc.perform(goGet(REPO, MODULE + "/@v/v0.1.0.mod"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andReturn())).isEqualTo(goMod(MODULE));

        MvcResult download = mvc.perform(goGet(REPO, MODULE + "/@v/v0.1.0.zip"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().longValue("Content-Length", zip.length))
                .andExpect(header().string("ETag", "\"" + sha256(zip) + "\""))
                .andExpect(header().string("X-Checksum-Sha256", sha256(zip)))
                .andExpect(header().exists("Last-Modified"))
                .andReturn();
        assertThat(bytes(download)).isEqualTo(zip);

        mvc.perform(goGet(REPO, MODULE + "/@latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Version").value("v0.1.0"))
                .andExpect(jsonPath("$.Time").value(info.path("Time").asString()));

        // HEAD and conditional requests behave like for every other format
        mvc.perform(head("/repository/" + REPO + "/" + MODULE + "/@v/v0.1.0.zip"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", zip.length))
                .andExpect(content().bytes(new byte[0]));
        mvc.perform(goGet(REPO, MODULE + "/@v/v0.1.0.zip").header("If-None-Match", "\"" + sha256(zip) + "\""))
                .andExpect(status().isNotModified());
        String listEtag = mvc.perform(goGet(REPO, MODULE + "/@v/list")).andReturn().getResponse().getHeader("ETag");
        mvc.perform(goGet(REPO, MODULE + "/@v/list").header("If-None-Match", listEtag)).andExpect(status().isNotModified());
        mvc.perform(head("/repository/" + REPO + "/" + MODULE + "/@v/list"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", "v0.1.0\n".length()));

        // only real downloads of the zip count
        assertThat(storage.find(repo, MODULE + "/@v/v0.1.0.zip").orElseThrow().asset().getDownloadCount()).isEqualTo(1);
    }

    @Test
    void theVersionListIsSortedBySemverAndLatestPrefersReleases() throws Exception {
        for (String v : new String[] {"v0.2.0", "v0.10.0", "v1.0.0-rc.1", "v0.9.0", "v0.0.0-20240101000000-abcdefabcdef", "v1.0.0-beta.2", "v0.10.0-alpha"}) {
            publish(v);
        }
        assertThat(text(mvc.perform(goGet(REPO, MODULE + "/@v/list")).andExpect(status().isOk()).andReturn()))
                .as("pseudo-versions are not listed, the rest is in semver order")
                .isEqualTo("v0.2.0\nv0.9.0\nv0.10.0-alpha\nv0.10.0\nv1.0.0-beta.2\nv1.0.0-rc.1\n");
        mvc.perform(goGet(REPO, MODULE + "/@latest")).andExpect(jsonPath("$.Version").value("v0.10.0"));
        // the pseudo-version itself is still downloadable
        mvc.perform(goGet(REPO, MODULE + "/@v/v0.0.0-20240101000000-abcdefabcdef.mod")).andExpect(status().isOk());
    }

    @Test
    void latestFallsBackToPrereleasesAndThenPseudoVersions() throws Exception {
        String pseudo = "example.com/acme/untagged";
        publish(pseudo, pseudo, "v0.0.0-20240101000000-abcdefabcdef", moduleZip(pseudo, "v0.0.0-20240101000000-abcdefabcdef"));
        publish(pseudo, pseudo, "v0.0.0-20240301000000-123456123456", moduleZip(pseudo, "v0.0.0-20240301000000-123456123456"));
        assertThat(text(mvc.perform(goGet(REPO, pseudo + "/@v/list")).andExpect(status().isOk()).andReturn())).isEmpty();
        mvc.perform(goGet(REPO, pseudo + "/@latest")).andExpect(jsonPath("$.Version").value("v0.0.0-20240301000000-123456123456"));

        String pre = "example.com/acme/pre";
        publish(pre, pre, "v0.1.0-rc.1", moduleZip(pre, "v0.1.0-rc.1"));
        publish(pre, pre, "v0.1.0-rc.2", moduleZip(pre, "v0.1.0-rc.2"));
        mvc.perform(goGet(REPO, pre + "/@latest")).andExpect(jsonPath("$.Version").value("v0.1.0-rc.2"));
    }

    @Test
    void upperCaseModulePathsTravelCaseEncoded() throws Exception {
        String module = "github.com/Acme/Hello";
        String escaped = "github.com/!acme/!hello";
        byte[] zip = moduleZip(module, "v1.0.0");
        JsonNode created = json(publish(module, escaped, "v1.0.0", zip));
        assertThat(created.path("module").asString()).isEqualTo(module);
        assertThat(created.path("files").get(0).asString()).isEqualTo(escaped + "/@v/v1.0.0.zip");
        assertThat(text(mvc.perform(goGet(REPO, escaped + "/@v/list")).andExpect(status().isOk()).andReturn())).isEqualTo("v1.0.0\n");
        assertThat(bytes(mvc.perform(goGet(REPO, escaped + "/@v/v1.0.0.zip")).andExpect(status().isOk()).andReturn())).isEqualTo(zip);
        // upper-case letters must be escaped; the go command never sends them raw
        mvc.perform(goGet(REPO, module + "/@v/list"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.go.invalidPath"));
        // a lower-case module of the same name is a different module
        mvc.perform(goGet(REPO, "github.com/acme/hello/@v/list")).andExpect(status().isNotFound());

        // upper-case letters in versions are escaped the same way
        String v = "v1.1.0-RC1";
        publish(module, escaped, "v1.1.0-!r!c1", moduleZip(module, v));
        assertThat(text(mvc.perform(goGet(REPO, escaped + "/@v/list")).andReturn())).isEqualTo("v1.0.0\nv1.1.0-RC1\n");
        mvc.perform(goGet(REPO, escaped + "/@v/v1.1.0-!r!c1.info")).andExpect(status().isOk()).andExpect(jsonPath("$.Version").value(v));
    }

    @Test
    void componentsAreRecordedForTheBrowserAndSearch() throws Exception {
        publish("v0.1.0");
        publish("v0.2.0");
        mvc.perform(get("/api/v1/repositories/" + REPO + "/components").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].namespace").value("example.com/acme"))
                .andExpect(jsonPath("$.items[0].name").value("hello"))
                .andExpect(jsonPath("$.items[0].version").value("v0.1.0"));
        mvc.perform(get("/api/v1/search?q=hello").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].repository").value(REPO));
        // every file of a version belongs to its component
        assertThat(storage.find(repo, MODULE + "/@v/v0.1.0.mod").orElseThrow().asset().getComponentId())
                .isEqualTo(storage.find(repo, MODULE + "/@v/v0.1.0.zip").orElseThrow().asset().getComponentId());
    }

    @Test
    void republishingIsRefusedUnlessTheRepositoryAllowsRedeploys() throws Exception {
        publish("v0.1.0");
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.zip", moduleZip(MODULE, "v0.1.0")).header("Authorization", adminBasic()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("repository.go.versionExists"))
                .andExpect(jsonPath("$.message").value(containsString(MODULE + "@v0.1.0 already exists")));

        repo.setAllowRedeploy(true);
        repositories.update(REPO, repo);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("go.mod", goMod(MODULE));
        files.put("hello.go", "package hello\n\nfunc Hello() string { return \"second\" }\n");
        byte[] second = moduleZip(MODULE, "v0.1.0", files);
        publish(MODULE, MODULE, "v0.1.0", second);
        assertThat(bytes(mvc.perform(goGet(REPO, MODULE + "/@v/v0.1.0.zip")).andReturn())).isEqualTo(second);
    }

    @Test
    void infoAndModCanBeUploadedAsTheGoCommandKeepsThemButThePublishedVersionNeverChanges() throws Exception {
        publish("v0.1.0");
        // the real tag time replaces the generated upload time
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.info", utf8("{\"Version\":\"v0.1.0\",\"Time\":\"2024-05-01T10:00:00Z\"}"))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.files[0]").value(MODULE + "/@v/v0.1.0.info"));
        mvc.perform(goGet(REPO, MODULE + "/@v/v0.1.0.info")).andExpect(jsonPath("$.Time").value("2024-05-01T10:00:00Z"));
        mvc.perform(goGet(REPO, MODULE + "/@latest")).andExpect(jsonPath("$.Time").value("2024-05-01T10:00:00Z"));
        // uploading it again is harmless, changing it is not allowed
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.info", utf8("{\"Version\":\"v0.1.0\",\"Time\":\"2024-05-01T10:00:00Z\"}"))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files.length()").value(0));
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.info", utf8("{\"Version\":\"v0.1.0\",\"Time\":\"2025-01-01T00:00:00Z\"}"))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("repository.go.versionExists"));

        // the go.mod consumers recorded in go.sum: the same bytes are accepted, anything else is refused
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.mod", utf8(goMod(MODULE))).header("Authorization", adminBasic()))
                .andExpect(status().isOk());
        String mod = goMod(MODULE) + "\nrequire rsc.io/quote v1.5.2\n";
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.mod", utf8(mod)).header("Authorization", adminBasic()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("go.sum")));
        assertThat(text(mvc.perform(goGet(REPO, MODULE + "/@v/v0.1.0.mod")).andReturn())).isEqualTo(goMod(MODULE));

        // unless the repository allows redeploys
        repo.setAllowRedeploy(true);
        repositories.update(REPO, repo);
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.mod", utf8(mod)).header("Authorization", adminBasic()))
                .andExpect(status().isCreated());
        assertThat(text(mvc.perform(goGet(REPO, MODULE + "/@v/v0.1.0.mod")).andReturn())).isEqualTo(mod);

        // what does not fit the URL is refused
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.info", utf8("{\"Version\":\"v0.2.0\"}")).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.go.badRequest"));
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.info", utf8("not json")).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest());
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.info", utf8("{\"Version\":\"v0.1.0\",\"Time\":\"yesterday\"}")).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest());
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.mod", utf8("module example.com/other\n")).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("example.com/other")));
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.mod", new byte[0]).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void infoAndModOfAVersionThatWasNeverPublishedAreRefused() throws Exception {
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.5.0.info", utf8("{\"Version\":\"v0.5.0\",\"Time\":\"2023-01-02T03:04:05Z\"}"))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("repository.go.versionMissing"))
                .andExpect(jsonPath("$.message").value(containsString("upload " + MODULE + "/@v/v0.5.0.zip first")));
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.5.0.mod", utf8(goMod(MODULE))).header("Authorization", adminBasic()))
                .andExpect(status().isConflict());
        // no half-published version: nothing to resolve, no component in the browser
        mvc.perform(goGet(REPO, MODULE + "/@v/v0.5.0.info")).andExpect(status().isNotFound());
        assertThat(storage.list(repo, "")).isEmpty();
        mvc.perform(get("/api/v1/repositories/" + REPO + "/components").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void concurrentUploadsOfOneVersionEndInOne201AndConflicts() throws Exception {
        int uploaders = 6;
        ExecutorService pool = Executors.newFixedThreadPool(uploaders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        String auth = adminBasic();
        try {
            for (int i = 0; i < uploaders; i++) {
                Map<String, String> files = new LinkedHashMap<>();
                files.put("go.mod", goMod(MODULE));
                files.put("u" + i + ".go", "package hello\n");
                byte[] zip = moduleZip(MODULE, "v0.1.0", files);
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.zip", zip).header("Authorization", auth))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(storage.list(repo, "")).hasSize(3);
        mvc.perform(goGet(REPO, MODULE + "/@v/list")).andExpect(content().string("v0.1.0\n"));
        mvc.perform(get("/api/v1/repositories/" + REPO + "/components").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void zipsMadeWithZipRAreStoredWithoutDirectoryEntries() throws Exception {
        // zip -r writes an entry for every directory; the go command hashes those too, so they must not be served
        String prefix = MODULE + "@v0.1.0/";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(prefix, new byte[0]);
        entries.put(prefix + "go.mod", utf8(goMod(MODULE)));
        entries.put(prefix + "internal/", new byte[0]);
        entries.put(prefix + "internal/greet.go", utf8("package internal\n"));
        publish(MODULE, MODULE, "v0.1.0", GoFixtures.zip(entries));
        byte[] served = bytes(mvc.perform(goGet(REPO, MODULE + "/@v/v0.1.0.zip")).andExpect(status().isOk()).andReturn());
        java.util.List<String> names = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(served))) {
            for (java.util.zip.ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names).containsExactly(prefix + "go.mod", prefix + "internal/greet.go");
        mvc.perform(goGet(REPO, MODULE + "/@v/v0.1.0.zip").header("If-None-Match", "\"" + sha256(served) + "\""))
                .andExpect(status().isNotModified());
    }

    @Test
    void invalidZipsAreRefusedWithTheReason() throws Exception {
        expectInvalidZip("v0.1.0", moduleZip("example.com/acme/other", "v0.1.0"), "path does not have prefix");
        expectInvalidZip("v0.1.0", moduleZip(MODULE, "v0.2.0"), "path does not have prefix");
        expectInvalidZip("v0.1.0", moduleZip(MODULE, "v0.1.0", Map.of("hello.go", "package hello\n")), "has no " + MODULE + "@v0.1.0/go.mod");
        expectInvalidZip("v0.1.0", moduleZip(MODULE, "v0.1.0", Map.of("go.mod", goMod(MODULE), "vendor/rsc.io/quote/quote.go", "package quote\n")),
                "vendor directory");
        expectInvalidZip("v0.1.0", moduleZip(MODULE, "v0.1.0", Map.of("go.mod", goMod(MODULE), "sub/go.mod", goMod(MODULE + "/sub"))),
                "go.mod file not in module root directory");
        expectInvalidZip("v0.1.0", moduleZip(MODULE, "v0.1.0", Map.of("go.mod", goMod(MODULE), "README", "a", "readme", "b")),
                "case-insensitive file name collision");
        expectInvalidZip("v0.1.0", moduleZip(MODULE, "v0.1.0", Map.of("go.mod", goMod(MODULE), "a/../../etc/passwd", "x")), "not clean");
        expectInvalidZip("v0.1.0", utf8("this is not a zip"), "not a valid zip");

        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.zip", moduleZip(MODULE, "v0.1.0", Map.of("go.mod", goMod("example.com/acme/other"))))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("declares its path as \"example.com/acme/other\"")));
        mvc.perform(curlPut(REPO, MODULE + "/@v/v0.1.0.zip", new byte[0]).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("empty")));

        // nothing of a refused upload is left behind
        mvc.perform(goGet(REPO, MODULE + "/@v/list")).andExpect(status().isNotFound());
        assertThat(storage.list(repo, "")).isEmpty();
    }

    private void expectInvalidZip(String version, byte[] zip, String reason) throws Exception {
        mvc.perform(curlPut(REPO, MODULE + "/@v/" + version + ".zip", zip).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.go.invalidZip"))
                .andExpect(jsonPath("$.message").value(containsString(reason)));
    }

    @Test
    void versionsMustBeCanonicalAndMatchTheMajorVersionSuffix() throws Exception {
        expectInvalidVersion(MODULE, "v2.0.0", "should be v0 or v1, not v2");
        expectInvalidVersion(MODULE, "v1.0", "non-canonical version");
        expectInvalidVersion(MODULE, "1.0.0", "must be a semantic version");
        expectInvalidVersion(MODULE + "/v2", "v1.0.0", "should be v2, not v1");
        expectInvalidVersion(MODULE + "/v2", "v2.0.0+incompatible", "+incompatible suffix not allowed");

        String v2 = MODULE + "/v2";
        publish(v2, v2, "v2.1.0", moduleZip(v2, "v2.1.0"));
        mvc.perform(goGet(REPO, v2 + "/@v/list")).andExpect(content().string("v2.1.0\n"));

        // +incompatible versions of modules without go.mod get a synthetic one, as on proxy.golang.org
        String legacy = "example.com/acme/legacy";
        publish(legacy, legacy, "v3.0.0+incompatible", moduleZip(legacy, "v3.0.0+incompatible", Map.of("legacy.go", "package legacy\n")));
        assertThat(text(mvc.perform(goGet(REPO, legacy + "/@v/v3.0.0+incompatible.mod")).andExpect(status().isOk()).andReturn()))
                .isEqualTo("module " + legacy + "\n");
    }

    private void expectInvalidVersion(String module, String version, String reason) throws Exception {
        mvc.perform(curlPut(REPO, module + "/@v/" + version + ".zip", moduleZip(module, version)).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.go.invalidVersion"))
                .andExpect(jsonPath("$.message").value(containsString(reason)));
    }

    @Test
    void deletingAVersionRemovesItsFilesAndComponent() throws Exception {
        publish("v0.1.0");
        publish("v0.2.0");
        mvc.perform(delete("/repository/" + REPO + "/" + MODULE + "/@v/v0.2.0").header("Authorization", adminBasic()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted.length()").value(3));
        for (String ext : new String[] {"info", "mod", "zip"}) {
            mvc.perform(goGet(REPO, MODULE + "/@v/v0.2.0." + ext)).andExpect(status().isNotFound());
        }
        mvc.perform(goGet(REPO, MODULE + "/@v/list")).andExpect(content().string("v0.1.0\n"));
        mvc.perform(goGet(REPO, MODULE + "/@latest")).andExpect(jsonPath("$.Version").value("v0.1.0"));
        mvc.perform(get("/api/v1/repositories/" + REPO + "/components").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(delete("/repository/" + REPO + "/" + MODULE + "/@v/v0.2.0").header("Authorization", adminBasic()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/repository/" + REPO + "/" + MODULE + "/@v/v0.1.0").header("Authorization", adminBasic()))
                .andExpect(status().isOk());
        mvc.perform(goGet(REPO, MODULE + "/@v/list")).andExpect(status().isNotFound());
        mvc.perform(goGet(REPO, MODULE + "/@latest")).andExpect(status().isNotFound());
        // and the version can be published again
        publish("v0.1.0");
    }

    @Test
    void unknownModulesAndVersionsAre404SoTheGoCommandMovesOn() throws Exception {
        publish("v0.1.0");
        // the go command probes every prefix of a package path; all of these must be a clean 404
        for (String path : new String[] {"example.com/@v/v0.1.0.info", "example.com/acme/@v/v0.1.0.info", "example.com/acme/@v/list",
                MODULE + "/@v/v9.9.9.info", MODULE + "/@v/v9.9.9.mod", MODULE + "/@v/v9.9.9.zip", MODULE + "/@v/master.info",
                MODULE + "/@v/v1.0.mod", "example.com/nothing/@latest"}) {
            mvc.perform(goGet(REPO, path)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("content.notFound"));
        }
        mvc.perform(goGet(REPO, MODULE + "/@v/v0.1.0")).andExpect(status().isNotFound());
        mvc.perform(goGet(REPO, MODULE + "/@v/")).andExpect(status().isNotFound());
        mvc.perform(goGet(REPO, "anything/else")).andExpect(status().isNotFound());
        // a hosted repository has no checksum database: the go command then contacts sum.golang.org itself
        mvc.perform(goGet(REPO, "sumdb/sum.golang.org/supported")).andExpect(status().isNotFound());
        mvc.perform(goGet(REPO, "sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "example.com/acme/./hello/@v/list",
            "example.com/acme/hello./@v/list",
            "examplecom/hello/@v/list",
            "example.com/acme/hel%20lo/@v/list",
            "example.com/acme/!!hello/@v/list",
            "example.com/acme/hello!/@v/list",
            "example.com/acme/hello/@v/v1.0.0%00.info",
            "example.com/acme/hello/@v/v1.0.0!.zip",
            "-example.com/acme/hello/@v/list",
            "example.com/acme/hello/@v/v1.0.0;rm.zip"})
    void malformedPathsAre400NotServerErrors(String path) throws Exception {
        // either the request firewall or the handler refuses it; never a 500, never a lookup
        mvc.perform(get("/repository/" + REPO + "/" + path)).andExpect(status().isBadRequest());
    }

    @Test
    void traversalNeverReachesTheBlobStore() throws Exception {
        publish("v0.1.0");
        mvc.perform(get("/repository/" + REPO + "/example.com/acme/hello/../../../etc/passwd")).andExpect(status().isBadRequest());
        mvc.perform(get("/repository/" + REPO + "/example.com/acme/hello/@v/..%2F..%2Fv0.1.0.zip")).andExpect(status().isBadRequest());
        mvc.perform(get("/repository/" + REPO + "/example.com\\acme\\hello/@v/list")).andExpect(status().isBadRequest());
        mvc.perform(curlPut(REPO, "example.com/acme/../hello/@v/v0.2.0.zip", moduleZip("example.com/hello", "v0.2.0"))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest());
        assertThat(storage.list(repo, "")).extracting(a -> a.getPath()).allMatch(p -> p.startsWith(MODULE + "/@v/"));
    }

    @Test
    void unsupportedMethodsAre405() throws Exception {
        publish("v0.1.0");
        mvc.perform(post("/repository/" + REPO + "/" + MODULE + "/@v/v0.1.0.zip").header("Authorization", adminBasic()).content(new byte[] {1}))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("method.notAllowed"));
        mvc.perform(curlPut(REPO, MODULE + "/@v/list", utf8("v9.0.0\n")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(curlPut(REPO, MODULE + "/@latest", utf8("{}")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/repository/" + REPO + "/" + MODULE + "/@v/v0.1.0.zip").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(curlPut(REPO, "sumdb/sum.golang.org/latest", utf8("x")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void theRepositoryRootDescribesTheGoproxyUrl() throws Exception {
        mvc.perform(get("/repository/" + REPO + "/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("go"))
                .andExpect(jsonPath("$.type").value("hosted"))
                .andExpect(jsonPath("$.goproxy").value(GoFixtures.repositoryUrl(REPO)));
    }

    @Test
    void uploadedTimestampsAreStableAcrossReads() throws Exception {
        publish("v0.1.0");
        String first = text(mvc.perform(goGet(REPO, MODULE + "/@v/v0.1.0.info")).andReturn());
        Thread.sleep(Duration.ofMillis(1100));
        assertThat(text(mvc.perform(goGet(REPO, MODULE + "/@v/v0.1.0.info")).andReturn())).isEqualTo(first);
    }
}
