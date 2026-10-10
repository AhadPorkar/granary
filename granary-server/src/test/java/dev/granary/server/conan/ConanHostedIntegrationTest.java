package dev.granary.server.conan;

import static dev.granary.server.conan.ConanFixtures.CAPABILITIES;
import static dev.granary.server.conan.ConanFixtures.PID;
import static dev.granary.server.conan.ConanFixtures.PREV;
import static dev.granary.server.conan.ConanFixtures.PREV2;
import static dev.granary.server.conan.ConanFixtures.RREV;
import static dev.granary.server.conan.ConanFixtures.RREV2;
import static dev.granary.server.conan.ConanFixtures.USER_AGENT;
import static dev.granary.server.conan.ConanFixtures.json;
import static dev.granary.server.conan.ConanFixtures.packageFiles;
import static dev.granary.server.conan.ConanFixtures.packageRevision;
import static dev.granary.server.conan.ConanFixtures.recipe;
import static dev.granary.server.conan.ConanFixtures.recipeFiles;
import static dev.granary.server.conan.ConanFixtures.revision;
import static dev.granary.server.conan.ConanFixtures.sha1;
import static dev.granary.server.conan.ConanFixtures.text;
import static dev.granary.server.conan.ConanFixtures.upload;
import static dev.granary.server.conan.ConanFixtures.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** {@code conan upload}, {@code conan install}, {@code conan list} and {@code conan remove} against a hosted repository. */
class ConanHostedIntegrationTest extends IntegrationTest {

    private static final String REPO = "conan-hosted";
    private static final String URL = "/repository/" + REPO;
    private static final String ZLIB = recipe("zlib", "1.3.1");

    @Autowired
    private StorageService storage;

    private Repository repo;
    private String token;

    @BeforeEach
    void createRepository() throws Exception {
        repo = hosted(REPO, "conan");
        token = "Bearer " + text(mvc.perform(get(URL + "/v2/users/authenticate").header("Authorization", adminBasic()))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    void pingAnnouncesRevisions() throws Exception {
        mvc.perform(get(URL + "/v1/ping").header("User-Agent", USER_AGENT))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Conan-Server-Capabilities", CAPABILITIES));
    }

    @Test
    void uploadTheWayTheClientDoesThenInstall() throws Exception {
        // conan upload: check_credentials, "is this revision there?", then every file (dedup probe first)
        mvc.perform(get(URL + "/v2/users/check_credentials").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(content().string("admin"));
        mvc.perform(get(URL + ZLIB + "/revisions").header("Authorization", token)).andExpect(status().isNotFound());
        Map<String, byte[]> recipeFiles = recipeFiles("zlib");
        for (Map.Entry<String, byte[]> file : recipeFiles.entrySet()) {
            String url = URL + revision(ZLIB, RREV) + "/files/" + file.getKey();
            mvc.perform(put(url).header("Authorization", token).header("X-Checksum-Deploy", "true")
                    .header("X-Checksum-Sha1", sha1(file.getValue()))).andExpect(status().isNotFound());
            mvc.perform(upload(url, file.getValue(), token)).andExpect(status().isCreated());
        }
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/packages/" + PID + "/revisions").header("Authorization", token))
                .andExpect(status().isNotFound());
        Map<String, byte[]> binary = packageFiles("zlib");
        for (Map.Entry<String, byte[]> file : binary.entrySet()) {
            mvc.perform(upload(URL + packageRevision(ZLIB, RREV, PID, PREV) + "/files/" + file.getKey(), file.getValue(), token))
                    .andExpect(status().isCreated());
        }

        // conan install, anonymously: latest recipe revision, its files, the binary for the configuration
        MvcResult latest = mvc.perform(get(URL + ZLIB + "/latest").header("User-Agent", USER_AGENT))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(jsonPath("$.revision").value(RREV))
                .andReturn();
        assertThat(json(latest).path("time").stringValue()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}\\+0000");
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files['conanfile.py']").isMap())
                .andExpect(jsonPath("$.files['conan_export.tgz']").isMap())
                .andExpect(jsonPath("$.files['conanmanifest.txt']").isMap());
        for (Map.Entry<String, byte[]> file : recipeFiles.entrySet()) {
            mvc.perform(get(URL + revision(ZLIB, RREV) + "/files/" + file.getKey()))
                    .andExpect(status().isOk())
                    .andExpect(header().longValue("Content-Length", file.getValue().length))
                    .andExpect(header().exists("ETag"))
                    .andExpect(content().bytes(file.getValue()));
        }
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/search").param("list_only", "False"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['" + PID + "'].settings.os").value("Linux"))
                .andExpect(jsonPath("$['" + PID + "'].settings['compiler.version']").value("13"))
                .andExpect(jsonPath("$['" + PID + "'].options.shared").value("False"));
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/packages/" + PID + "/latest")
                        .header("Conan-PkgID-Settings", "arch=x86_64;os=Linux").header("Conan-PkgID-Options", "shared=False"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(PREV));
        mvc.perform(get(URL + packageRevision(ZLIB, RREV, PID, PREV) + "/files"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.files['conan_package.tgz']").isMap());
        mvc.perform(get(URL + packageRevision(ZLIB, RREV, PID, PREV) + "/files/conan_package.tgz"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/gzip"))
                .andExpect(content().bytes(binary.get("conan_package.tgz")));

        // conan search / conan list
        mvc.perform(get(URL + "/v2/conans/search").param("q", "zlib"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0]").value("zlib/1.3.1"));
        mvc.perform(get(URL + "/v2/conans/search").param("q", "zlib/*"))
                .andExpect(jsonPath("$.results.length()").value(1));
        mvc.perform(get(URL + "/v2/conans/search").param("q", "boost*"))
                .andExpect(jsonPath("$.results.length()").value(0));
        mvc.perform(get(URL + ZLIB + "/revisions"))
                .andExpect(jsonPath("$.revisions.length()").value(1))
                .andExpect(jsonPath("$.revisions[0].revision").value(RREV));
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/packages/" + PID + "/revisions"))
                .andExpect(jsonPath("$.revisions[0].revision").value(PREV));

        // stored under the documented layout and shown as one component in the UI
        Asset export = storage.find(repo, "conans/zlib/1.3.1/_/_/" + RREV + "/export/conan_export.tgz").orElseThrow().asset();
        assertThat(export.getOrigin()).isEqualTo(Asset.Origin.UPLOADED);
        assertThat(export.getDownloadCount()).isEqualTo(1);
        assertThat(storage.find(repo, "conans/zlib/1.3.1/_/_/" + RREV + "/package/" + PID + "/" + PREV + "/conaninfo.txt"))
                .isPresent();
        mvc.perform(get("/api/v1/repositories/" + REPO + "/components").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("zlib"))
                .andExpect(jsonPath("$.items[0].version").value("1.3.1"))
                .andExpect(jsonPath("$.items[0].namespace").value(""))
                .andExpect(jsonPath("$.items[0].format").value("conan"));
    }

    @Test
    void parallelUploadsOfOneReferenceShareOneComponent() throws Exception {
        // conan upload with core.upload:parallel sends the files of a revision at the same time
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        recipeFiles("parallel").forEach((file, body) -> files.put(revision(ZLIB, RREV) + "/files/" + file, body));
        packageFiles("parallel").forEach((file, body) -> files.put(packageRevision(ZLIB, RREV, PID, PREV) + "/files/" + file, body));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new java.util.ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(files.size())) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(upload(URL + file.getKey(), file.getValue(), token)).andReturn();
                }));
            }
            start.countDown();
            for (Future<MvcResult> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            }
        }
        List<java.util.UUID> components = storage.list(repo, "conans/zlib/1.3.1/").stream().map(Asset::getComponentId).distinct().toList();
        assertThat(components).hasSize(1).doesNotContainNull();
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/packages/" + PID + "/latest")).andExpect(jsonPath("$.revision").value(PREV));
    }

    @Test
    void checksumDeployShortcutsAKnownFile() throws Exception {
        publish(ZLIB, RREV, PID, PREV, "zlib");
        byte[] conanfile = recipeFiles("zlib").get("conanfile.py");
        mvc.perform(put(URL + revision(ZLIB, RREV) + "/files/conanfile.py").header("Authorization", token)
                        .header("X-Checksum-Deploy", "true").header("X-Checksum-Sha1", sha1(conanfile)))
                .andExpect(status().isCreated());
        mvc.perform(put(URL + revision(ZLIB, RREV) + "/files/conanfile.py").header("Authorization", token)
                        .header("X-Checksum-Deploy", "true").header("X-Checksum-Sha1", sha1(utf8("other"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void userAndChannelRecipes() throws Exception {
        String acme = recipe("acme", "1.0", "team", "stable");
        publish(acme, RREV, PID, PREV, "acme");
        mvc.perform(get(URL + acme + "/latest")).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(RREV));
        mvc.perform(get(URL + recipe("acme", "1.0") + "/latest")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/v2/conans/search").param("q", "acme/*@team/*"))
                .andExpect(jsonPath("$.results[0]").value("acme/1.0@team/stable"));
        mvc.perform(get("/api/v1/repositories/" + REPO + "/components").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.items[0].namespace").value("team/stable"));
    }

    @Test
    void newerRevisionsComeFirst() throws Exception {
        publish(ZLIB, RREV, PID, PREV, "one");
        Thread.sleep(15);
        publish(ZLIB, RREV2, PID, PREV2, "two");
        mvc.perform(get(URL + ZLIB + "/latest")).andExpect(jsonPath("$.revision").value(RREV2));
        mvc.perform(get(URL + ZLIB + "/revisions"))
                .andExpect(jsonPath("$.revisions[0].revision").value(RREV2))
                .andExpect(jsonPath("$.revisions[1].revision").value(RREV));
    }

    @Test
    void anUnfinishedUploadIsInvisibleUntilTheManifestArrives() throws Exception {
        Map<String, byte[]> files = recipeFiles("zlib");
        mvc.perform(upload(URL + revision(ZLIB, RREV) + "/files/conan_export.tgz", files.get("conan_export.tgz"), token))
                .andExpect(status().isCreated());
        mvc.perform(get(URL + ZLIB + "/latest")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/v2/conans/search").param("q", "*")).andExpect(jsonPath("$.results.length()").value(0));
        // the client retries the whole upload; the file already there is accepted again
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            mvc.perform(upload(URL + revision(ZLIB, RREV) + "/files/" + file.getKey(), file.getValue(), token))
                    .andExpect(status().is2xxSuccessful());
        }
        mvc.perform(get(URL + ZLIB + "/latest")).andExpect(status().isOk());
    }

    @Test
    void reUploadRules() throws Exception {
        publish(ZLIB, RREV, PID, PREV, "zlib");
        byte[] conanfile = recipeFiles("zlib").get("conanfile.py");
        String url = URL + revision(ZLIB, RREV) + "/files/conanfile.py";
        mvc.perform(upload(url, conanfile, token)).andExpect(status().isOk());
        mvc.perform(upload(url, utf8("tampered"), token))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(jsonPath("$.errors[0].status").value(409))
                .andExpect(jsonPath("$.code").value("repository.conan.revisionExists"));
        mvc.perform(put(url).header("Authorization", token).header("X-Checksum-Sha1", sha1(utf8("x"))).content(utf8("y")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.conan.checksumMismatch"));
        mvc.perform(get(url)).andExpect(content().bytes(conanfile));

        // metadata files may change without a new revision
        String log = URL + revision(ZLIB, RREV) + "/files/metadata/logs/build.log";
        mvc.perform(upload(log, utf8("first"), token)).andExpect(status().isCreated());
        mvc.perform(upload(log, utf8("second"), token)).andExpect(status().isCreated());
        mvc.perform(get(log)).andExpect(content().string("second"));
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/files")).andExpect(jsonPath("$.files['metadata/logs/build.log']").isMap());

        Repository relaxed = repositories.require(REPO);
        relaxed.setAllowRedeploy(true);
        repositories.update(REPO, relaxed);
        mvc.perform(upload(url, utf8("replaced"), token)).andExpect(status().isCreated());
        mvc.perform(get(url)).andExpect(content().string("replaced"));
    }

    @Test
    void conditionalAndHeadRequests() throws Exception {
        publish(ZLIB, RREV, PID, PREV, "zlib");
        String file = URL + revision(ZLIB, RREV) + "/files/conan_export.tgz";
        MvcResult first = mvc.perform(head(file)).andExpect(status().isOk())
                .andExpect(header().exists("Content-Length")).andReturn();
        assertThat(first.getResponse().getContentAsByteArray()).isEmpty();
        String etag = first.getResponse().getHeader("ETag");
        mvc.perform(get(file).header("If-None-Match", etag)).andExpect(status().isNotModified());

        String latestEtag = mvc.perform(get(URL + ZLIB + "/latest")).andReturn().getResponse().getHeader("ETag");
        assertThat(latestEtag).isNotBlank();
        mvc.perform(get(URL + ZLIB + "/latest").header("If-None-Match", latestEtag)).andExpect(status().isNotModified());
        mvc.perform(head(URL + ZLIB + "/latest")).andExpect(status().isOk()).andExpect(content().string(""));
    }

    @Test
    void conanRemove() throws Exception {
        publish(ZLIB, RREV, PID, PREV, "one");
        publishPackage(ZLIB, RREV, PID, PREV2, "one-b");
        publish(ZLIB, RREV2, PID, PREV, "two");

        // conan remove zlib/1.3.1#rrev:pid#prev
        mvc.perform(delete(URL + packageRevision(ZLIB, RREV, PID, PREV2)).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/packages/" + PID + "/latest")).andExpect(jsonPath("$.revision").value(PREV));
        // conan remove zlib/1.3.1#rrev:*
        mvc.perform(delete(URL + revision(ZLIB, RREV) + "/packages").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/search")).andExpect(status().isOk()).andExpect(content().json("{}"));
        mvc.perform(delete(URL + revision(ZLIB, RREV) + "/packages").header("Authorization", token)).andExpect(status().isNotFound());
        // conan remove zlib/1.3.1#rrev
        mvc.perform(delete(URL + revision(ZLIB, RREV2)).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get(URL + ZLIB + "/latest")).andExpect(jsonPath("$.revision").value(RREV));
        mvc.perform(delete(URL + revision(ZLIB, RREV2)).header("Authorization", token)).andExpect(status().isNotFound());
        // conan remove zlib/1.3.1 (all revisions)
        mvc.perform(delete(URL + revision(ZLIB, RREV)).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get(URL + ZLIB + "/latest")).andExpect(status().isNotFound());
        assertThat(storage.list(repo, "conans/")).isEmpty();
        mvc.perform(get("/api/v1/repositories/" + REPO + "/components").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void notFoundAnswersAreJsonErrorsTheClientPrints() throws Exception {
        mvc.perform(get(URL + ZLIB + "/latest"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(jsonPath("$.errors[0].status").value(404))
                .andExpect(jsonPath("$.errors[0].message").value("Recipe not found: 'zlib/1.3.1'"));
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/files/conanfile.py")).andExpect(status().isNotFound());
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/packages/" + PID + "/latest")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/v1/conans/zlib/1.3.1/_/_/download_urls")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/v2/conans/search")).andExpect(status().isOk()).andExpect(jsonPath("$.results").isEmpty());
        mvc.perform(get(URL + "/")).andExpect(status().isOk()).andExpect(jsonPath("$.format").value("conan"));
    }

    @Test
    void traversalAndOddSegmentsNeverReachTheBlobStore() throws Exception {
        for (String path : List.of(
                "/v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/..%2F..%2Fescape",
                "/v2/conans/zlib/1.3.1/_/_/revisions/..%2F" + RREV + "/files/conanfile.py",
                "/v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/a%5Cb",
                "/v2/conans/zl%20ib/1.3.1/_/_/revisions/" + RREV + "/files/conanfile.py",
                "/v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/%C3%BC.py")) {
            MvcResult result = mvc.perform(put(URI.create(URL + path)).header("Authorization", token)
                    .header("X-Checksum-Sha1", sha1(utf8("x"))).content(utf8("x"))).andReturn();
            assertThat(result.getResponse().getStatus()).as(path).isEqualTo(400);
        }
        assertThat(storage.list(repo, "")).isEmpty();
        mvc.perform(get(URI.create(URL + "/v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/..%2F..%2Fx")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void methodsOutsideTheProtocol() throws Exception {
        mvc.perform(put(URL + ZLIB + "/latest").header("Authorization", token)).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(URL + revision(ZLIB, RREV) + "/files/conanfile.py").header("Authorization", token))
                .andExpect(status().isMethodNotAllowed());
    }

    private void publish(String recipe, String rrev, String pid, String prev, String seed) throws Exception {
        for (Map.Entry<String, byte[]> file : recipeFiles(seed).entrySet()) {
            mvc.perform(upload(URL + revision(recipe, rrev) + "/files/" + file.getKey(), file.getValue(), token))
                    .andExpect(status().is2xxSuccessful());
        }
        publishPackage(recipe, rrev, pid, prev, seed);
    }

    private void publishPackage(String recipe, String rrev, String pid, String prev, String seed) throws Exception {
        for (Map.Entry<String, byte[]> file : packageFiles(seed).entrySet()) {
            mvc.perform(upload(URL + packageRevision(recipe, rrev, pid, prev) + "/files/" + file.getKey(), file.getValue(), token))
                    .andExpect(status().is2xxSuccessful());
        }
    }
}
