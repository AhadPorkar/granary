package dev.granary.server.pub;

import static dev.granary.server.pub.PubFixtures.archive;
import static dev.granary.server.pub.PubFixtures.json;
import static dev.granary.server.pub.PubFixtures.repositoryUrl;
import static dev.granary.server.pub.PubFixtures.sha256;
import static dev.granary.server.pub.PubFixtures.str;
import static dev.granary.server.pub.PubFixtures.versions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** The hosted half of the pub protocol: dart pub publish, then everything dart pub get asks for. */
class PubHostedIntegrationTest extends PubClientSupport {

    private static final String REPO = "pub-hosted";
    private static final String URL = repositoryUrl(REPO);

    @Autowired
    private ComponentDao components;

    @Autowired
    private StorageService storage;

    private Repository repo;
    private String token;

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "pub");
        token = adminToken();
    }

    @Test
    void publishThenGet() throws Exception {
        byte[] archive = archive("granary_demo", "1.0.0");
        publish(REPO, archive, token)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success.message").value(
                        "Successfully uploaded granary_demo version 1.0.0 to " + URL + "."));

        // dart pub get: the listing, then the archive it names
        MvcResult listingResult = mvc.perform(api(URL + "/api/packages/granary_demo"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.pub.v2+json;charset=utf-8"))
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn();
        JsonNode listing = json(listingResult);
        assertThat(str(listing, "name")).isEqualTo("granary_demo");
        assertThat(versions(listing)).containsExactly("1.0.0");
        JsonNode version = listing.path("versions").get(0);
        assertThat(str(version, "archive_url")).isEqualTo(URL + "/packages/granary_demo/versions/1.0.0.tar.gz");
        assertThat(str(version, "archive_sha256")).isEqualTo(sha256(archive));
        assertThat(str(version, "published")).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{6}Z");
        assertThat(str(version, "pubspec", "description")).startsWith("A tiny package");
        assertThat(str(version, "pubspec", "dependencies", "path")).isEqualTo("^1.9.0");
        assertThat(str(version, "pubspec", "environment", "sdk")).isEqualTo("^3.4.0");
        assertThat(listing.path("latest")).isEqualTo(version);
        assertThat(listing.has("advisoriesUpdated")).isFalse();
        assertThat(listing.has("isDiscontinued")).isFalse();

        MvcResult download = mvc.perform(download(str(version, "archive_url")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/gzip"))
                .andExpect(header().string("ETag", "\"" + sha256(archive) + "\""))
                .andExpect(header().longValue("Content-Length", archive.length))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(archive);

        mvc.perform(api(URL + "/api/packages/granary_demo/versions/1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.archive_url").value(URL + "/packages/granary_demo/versions/1.0.0.tar.gz"))
                .andExpect(jsonPath("$.pubspec.name").value("granary_demo"));
        mvc.perform(api(URL + "/api/packages/granary_demo/advisories"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"advisories\":[],\"advisoriesUpdated\":null}"));

        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "granary_demo", "1.0.0"))
                .isPresent();
        assertThat(storage.find(repo, "api/packages/granary_demo"))
                .hasValueSatisfying(c -> assertThat(c.asset().isMetadata()).isTrue());
        assertThat(storage.find(repo, "packages/granary_demo/versions/1.0.0.tar.gz")).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isFalse();
            assertThat(c.asset().getDownloadCount()).isEqualTo(1);
        });
    }

    @Test
    void conditionalRequestsAndHead() throws Exception {
        byte[] archive = archive("granary_demo", "1.0.0");
        publish(REPO, archive, token).andExpect(status().isOk());

        String etag = mvc.perform(api(URL + "/api/packages/granary_demo")).andReturn().getResponse().getHeader("ETag");
        mvc.perform(api(URL + "/api/packages/granary_demo").header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag));
        mvc.perform(head(URL + "/api/packages/granary_demo").header("Accept", PubFixtures.ACCEPT))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Length"))
                .andExpect(content().bytes(new byte[0]));

        String archiveUrl = URL + "/packages/granary_demo/versions/1.0.0.tar.gz";
        mvc.perform(download(archiveUrl).header("If-None-Match", "\"" + sha256(archive) + "\""))
                .andExpect(status().isNotModified());
        mvc.perform(head(archiveUrl))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", archive.length))
                .andExpect(content().bytes(new byte[0]));
        // neither the 304 nor the HEAD counts as a download
        assertThat(storage.find(repo, "packages/granary_demo/versions/1.0.0.tar.gz").orElseThrow().asset().getDownloadCount())
                .isZero();

        // a new version changes the listing and its ETag
        publish(REPO, archive("granary_demo", "1.1.0"), token).andExpect(status().isOk());
        mvc.perform(api(URL + "/api/packages/granary_demo").header("If-None-Match", etag)).andExpect(status().isOk());
    }

    @Test
    void versionsAccumulateAndLatestFollowsPubsRules() throws Exception {
        for (String v : new String[] {"1.0.0", "2.0.0-dev.1", "1.2.0", "1.10.0", "1.2.0+1"}) {
            publish(REPO, archive("granary_demo", v), token).andExpect(status().isOk());
        }
        JsonNode listing = json(mvc.perform(api(URL + "/api/packages/granary_demo")).andReturn());
        assertThat(versions(listing)).containsExactly("1.0.0", "1.2.0", "1.2.0+1", "1.10.0", "2.0.0-dev.1");
        // the newest release wins over a newer pre-release
        assertThat(str(listing, "latest", "version")).isEqualTo("1.10.0");
        assertThat(components.findByRepositoryIdAndNamespaceAndNameOrderByVersionAsc(repo.getId(), "", "granary_demo"))
                .hasSize(5);

        // a package with nothing but pre-releases has a pre-release as latest
        publish(REPO, archive("only_dev", "0.1.0-dev.2"), token).andExpect(status().isOk());
        publish(REPO, archive("only_dev", "0.1.0-dev.10"), token).andExpect(status().isOk());
        mvc.perform(api(URL + "/api/packages/only_dev")).andExpect(jsonPath("$.latest.version").value("0.1.0-dev.10"));
    }

    @Test
    void republishingAVersionIsRefusedAndReportedByTheFinishStep() throws Exception {
        byte[] first = archive("granary_demo", "1.0.0");
        publish(REPO, first, token).andExpect(status().isOk());
        publish(REPO, archive("granary_demo", "1.0.0", PubFixtures.pubspec("granary_demo", "1.0.0") + "# changed\n"), token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PackageRejected"))
                .andExpect(jsonPath("$.error.message").value(
                        "Version 1.0.0 of package granary_demo already exists in repository 'pub-hosted'. Publish a new version instead."));
        // a different spelling of the same version is the same version to the pub client
        publish(REPO, archive("granary_demo", "1.0.0+0"), token).andExpect(status().isOk());
        publish(REPO, archive("granary_demo", "1.0.0+00"), token).andExpect(status().isConflict());
        assertThat(mvc.perform(download(URL + "/packages/granary_demo/versions/1.0.0.tar.gz")).andReturn()
                .getResponse().getContentAsByteArray()).isEqualTo(first);
    }

    @Test
    void republishingIsAllowedWhenTheRepositoryPermitsRedeploy() throws Exception {
        Repository redeploy = new Repository();
        redeploy.setName("pub-snapshots");
        redeploy.setFormat("pub");
        redeploy.setType(RepositoryType.HOSTED);
        redeploy.setAllowRedeploy(true);
        repositories.create(redeploy);

        publish("pub-snapshots", archive("granary_demo", "1.0.0"), token).andExpect(status().isOk());
        byte[] second = archive("granary_demo", "1.0.0", PubFixtures.pubspec("granary_demo", "1.0.0") + "# second\n");
        publish("pub-snapshots", second, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.message").value(containsString("replacing the previous upload")));
        JsonNode listing = json(mvc.perform(api(repositoryUrl("pub-snapshots") + "/api/packages/granary_demo")).andReturn());
        assertThat(versions(listing)).containsExactly("1.0.0");
        assertThat(str(listing, "latest", "archive_sha256")).isEqualTo(sha256(second));
        assertThat(mvc.perform(download(repositoryUrl("pub-snapshots") + "/packages/granary_demo/versions/1.0.0.tar.gz"))
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(second);
    }

    @Test
    void archivesThatCannotBePublishedAreRejectedWithTheReason() throws Exception {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("not an archive", "not gzip-compressed");
        cases.put("no pubspec", "has no pubspec.yaml at its root");
        cases.put("upper case name", "must consist of lower-case letters");
        cases.put("reserved word", "is a reserved word in Dart");
        cases.put("bad version", "is not a valid version");
        cases.put("no version", "has no \"version\" field");
        cases.put("bad yaml", "pubspec.yaml is not valid YAML");
        cases.put("traversal", "outside the package");
        cases.put("absolute", "absolute path");
        cases.put("escaping link", "link pointing outside the package");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            publish(REPO, rejected(c.getKey()), token)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("PackageRejected"))
                    .andExpect(jsonPath("$.error.message").value(containsString(c.getValue())));
        }
        assertThat(storage.list(repo, "")).isEmpty();
        assertThat(components.findByRepositoryIdOrderByNamespaceAscNameAscVersionAsc(repo.getId(),
                org.springframework.data.domain.Pageable.unpaged())).isEmpty();
    }

    private static byte[] rejected(String kind) {
        String ok = PubFixtures.pubspec("granary_demo", "1.0.0");
        return switch (kind) {
            case "not an archive" -> "PK\u0003\u0004 a zip file".getBytes(StandardCharsets.ISO_8859_1);
            case "no pubspec" -> PubFixtures.targz(Map.of("lib/a.dart", "void a() {}".getBytes(StandardCharsets.UTF_8)));
            case "upper case name" -> archive("x", "1.0.0", ok.replace("name: granary_demo", "name: GranaryDemo"));
            case "reserved word" -> archive("x", "1.0.0", ok.replace("name: granary_demo", "name: class"));
            case "bad version" -> archive("x", "1.0.0", ok.replace("version: 1.0.0", "version: 1.0"));
            case "no version" -> archive("x", "1.0.0", ok.replace("version: 1.0.0\n", ""));
            case "bad yaml" -> archive("x", "1.0.0", "name: [granary_demo\nversion: 1.0.0\n");
            case "traversal" -> PubFixtures.targz(Map.of("pubspec.yaml", ok.getBytes(StandardCharsets.UTF_8),
                    "lib/../../evil.dart", "x".getBytes(StandardCharsets.UTF_8)));
            case "absolute" -> PubFixtures.targz(Map.of("pubspec.yaml", ok.getBytes(StandardCharsets.UTF_8),
                    "/etc/cron.d/evil", "x".getBytes(StandardCharsets.UTF_8)));
            case "escaping link" -> PubFixtures.targzWithSymlink(ok, "lib/secret", "../../../etc/passwd");
            default -> throw new IllegalArgumentException(kind);
        };
    }

    @Test
    void theArchiveSizeLimitIsEnforced() throws Exception {
        Repository small = new Repository();
        small.setName("pub-small");
        small.setFormat("pub");
        small.setType(RepositoryType.HOSTED);
        small.setFormatSettings(Map.of("pub.maxArchiveSizeMb", "1"));
        repositories.create(small);
        byte[] noise = new byte[1536 * 1024];
        new java.util.Random(7).nextBytes(noise);
        byte[] big = PubFixtures.targz(Map.of("pubspec.yaml",
                PubFixtures.pubspec("big_one", "1.0.0").getBytes(StandardCharsets.UTF_8), "assets/noise.bin", noise));
        publish("pub-small", big, token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(containsString("1 MiB")));
        publish("pub-small", archive("small_one", "1.0.0"), token).andExpect(status().isOk());
    }

    @Test
    void missingThingsAreNotFoundInThePubErrorShape() throws Exception {
        mvc.perform(api(URL + "/api/packages/nothing_here"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("NotFound"))
                .andExpect(jsonPath("$.error.message").value("Package nothing_here not found in repository 'pub-hosted'"));
        publish(REPO, archive("granary_demo", "1.0.0"), token).andExpect(status().isOk());
        mvc.perform(api(URL + "/api/packages/granary_demo/versions/9.9.9")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NotFound"));
        mvc.perform(download(URL + "/packages/granary_demo/versions/9.9.9.tar.gz")).andExpect(status().isNotFound());
        mvc.perform(api(URL + "/api/packages/nothing_here/advisories")).andExpect(status().isNotFound());
        mvc.perform(api(URL + "/api/somewhere/else")).andExpect(status().isNotFound());
    }

    @Test
    void oddPathsAreRefusedCleanly() throws Exception {
        for (String path : new String[] {"/api/packages/Not-A-Name", "/api/packages/a%20b", "/api/packages/granary_demo/versions/1.0",
                "/packages/granary_demo/versions/latest.tar.gz", "/api/packages/" + "x".repeat(65)}) {
            mvc.perform(api(URL + path)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("InvalidInput"));
        }
        // traversal and backslashes never reach storage with another meaning
        for (String path : new String[] {"/packages/..%2F..%2Fetc/versions/1.0.0.tar.gz", "/api/packages/..%2Fx",
                "/packages/a%5Cb/versions/1.0.0.tar.gz", "/packages/granary_demo/versions/..%2F..%2F..%2Fapi%2Fpackages%2Fx.tar.gz"}) {
            mvc.perform(api(URL + path)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void encodedSlashesAreNotReadAsPathSeparators() throws Exception {
        publish(REPO, archive("granary_demo", "1.0.0"), token).andExpect(status().isOk());
        mvc.perform(api(URL + "/api/packages/granary_demo/versions/1.0.0")).andExpect(status().isOk());
        for (String path : new String[] {"/api/packages/granary_demo%2Fversions%2F1.0.0", "/api/packages/granary_demo%2fadvisories",
                "/packages/granary_demo/versions%2F1.0.0.tar.gz", "/packages/granary_demo%2Fversions/1.0.0.tar.gz"}) {
            mvc.perform(api(URL + path)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("InvalidInput"));
        }
        // nor for writes: an encoded publishing path is not the publishing endpoint
        mvc.perform(withToken(api(URL + "/api/packages/versions%2Fnew"), token)).andExpect(status().isBadRequest());
        // a + encoded in an archive URL is still the same version
        publish(REPO, archive("granary_demo", "1.0.0+1"), token).andExpect(status().isOk());
        mvc.perform(download(URL + "/packages/granary_demo/versions/1.0.0%2B1.tar.gz")).andExpect(status().isOk());
    }

    @Test
    void wrongMethodsAreRefused() throws Exception {
        mvc.perform(put(URL + "/packages/granary_demo/versions/1.0.0.tar.gz").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.error.code").value("MethodNotAllowed"));
        mvc.perform(post(URL + "/api/packages/versions/new").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(api(URL + "/api/packages/versions/newUpload").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")));
    }

    @Test
    void finishWithoutAValidUploadIdIsAClearError() throws Exception {
        mvc.perform(withToken(api(URL + "/api/packages/versions/newUploadFinish"), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(containsString("upload id is missing or malformed")));
        mvc.perform(withToken(api(URL + "/api/packages/versions/newUploadFinish?id=00000000-0000-0000-0000-000000000000"), token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value(containsString("unknown or has expired")));
        mvc.perform(withToken(api(URL + "/api/packages/versions/newUploadFinish?id=..%2F..%2Fetc%2Fpasswd"), token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retractAndRestoreAVersion() throws Exception {
        publish(REPO, archive("granary_demo", "1.0.0"), token).andExpect(status().isOk());
        publish(REPO, archive("granary_demo", "1.1.0"), token).andExpect(status().isOk());

        mvc.perform(post(URL + "/api/packages/granary_demo/versions/1.1.0/retract").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.message").value("Retracted granary_demo 1.1.0."));
        JsonNode listing = json(mvc.perform(api(URL + "/api/packages/granary_demo")).andReturn());
        assertThat(listing.path("versions").get(1).path("retracted").asBoolean()).isTrue();
        assertThat(listing.path("versions").get(0).has("retracted")).isFalse();
        assertThat(str(listing, "latest", "version")).isEqualTo("1.0.0");
        mvc.perform(api(URL + "/api/packages/granary_demo/versions/1.1.0/options"))
                .andExpect(jsonPath("$.isRetracted").value(true));
        // the archive stays available: lock files that pin the version keep working
        mvc.perform(download(URL + "/packages/granary_demo/versions/1.1.0.tar.gz")).andExpect(status().isOk());

        mvc.perform(delete(URL + "/api/packages/granary_demo/versions/1.1.0/retract").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.message").value("Restored granary_demo 1.1.0."));
        mvc.perform(api(URL + "/api/packages/granary_demo")).andExpect(jsonPath("$.latest.version").value("1.1.0"));

        // pub.dev's options endpoint does the same
        mvc.perform(put(URL + "/api/packages/granary_demo/versions/1.0.0/options").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isRetracted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isRetracted").value(true));
        mvc.perform(post(URL + "/api/packages/granary_demo/versions/7.0.0/retract").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void discontinueAPackage() throws Exception {
        publish(REPO, archive("granary_demo", "1.0.0"), token).andExpect(status().isOk());
        mvc.perform(put(URL + "/api/packages/granary_demo/options").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isDiscontinued\":true,\"replacedBy\":\"granary_next\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDiscontinued").value(true))
                .andExpect(jsonPath("$.replacedBy").value("granary_next"));
        mvc.perform(api(URL + "/api/packages/granary_demo"))
                .andExpect(jsonPath("$.isDiscontinued").value(true))
                .andExpect(jsonPath("$.replacedBy").value("granary_next"));
        mvc.perform(put(URL + "/api/packages/granary_demo/options").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isDiscontinued\":false}"))
                .andExpect(status().isOk());
        mvc.perform(api(URL + "/api/packages/granary_demo")).andExpect(jsonPath("$.isDiscontinued").doesNotExist());
        mvc.perform(put(URL + "/api/packages/granary_demo/options").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isDiscontinued\":\"yes\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("InvalidInput"));
        mvc.perform(put(URL + "/api/packages/granary_demo/options").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteVersionsAndPackages() throws Exception {
        publish(REPO, archive("granary_demo", "1.0.0"), token).andExpect(status().isOk());
        publish(REPO, archive("granary_demo", "1.1.0"), token).andExpect(status().isOk());

        mvc.perform(delete(URL + "/api/packages/granary_demo/versions/1.0.0").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.message").value("Deleted granary_demo 1.0.0."));
        mvc.perform(api(URL + "/api/packages/granary_demo")).andExpect(jsonPath("$.versions.length()").value(1));
        mvc.perform(download(URL + "/packages/granary_demo/versions/1.0.0.tar.gz")).andExpect(status().isNotFound());
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "granary_demo", "1.0.0")).isEmpty();
        // the deleted version can be published again
        publish(REPO, archive("granary_demo", "1.0.0"), token).andExpect(status().isOk());

        mvc.perform(delete(URL + "/api/packages/granary_demo").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.message").value("Deleted package granary_demo (2 versions)."));
        mvc.perform(api(URL + "/api/packages/granary_demo")).andExpect(status().isNotFound());
        assertThat(storage.list(repo, "")).isEmpty();
        mvc.perform(delete(URL + "/api/packages/granary_demo").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void aVersionDeletedInTheComponentBrowserDisappearsFromTheListing() throws Exception {
        publish(REPO, archive("granary_demo", "1.0.0"), token).andExpect(status().isOk());
        publish(REPO, archive("granary_demo", "1.1.0"), token).andExpect(status().isOk());
        var component = components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "granary_demo", "1.1.0")
                .orElseThrow();
        mvc.perform(delete("/api/v1/repositories/" + REPO + "/components/" + component.getId()).header("Authorization", adminBearer()))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(api(URL + "/api/packages/granary_demo"))
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.latest.version").value("1.0.0"));
    }

    @Test
    void theRepositoryRootTellsHowToUseIt() throws Exception {
        mvc.perform(api(URL + "/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("pub"))
                .andExpect(jsonPath("$.type").value("hosted"))
                .andExpect(jsonPath("$.usage").value("export PUB_HOSTED_URL=" + URL));
    }

    @Test
    void theConfiguredBaseUrlIsUsedForArchiveUrls() throws Exception {
        publish(REPO, archive("granary_demo", "1.0.0"), token).andExpect(status().isOk());
        settings.setBaseUrl("https://granary.example.com");
        mvc.perform(api(URL + "/api/packages/granary_demo"))
                .andExpect(jsonPath("$.latest.archive_url").value(
                        "https://granary.example.com/repository/pub-hosted/packages/granary_demo/versions/1.0.0.tar.gz"));
        mvc.perform(withToken(api(URL + "/api/packages/versions/new"), token))
                .andExpect(jsonPath("$.url").value(startsWith("https://granary.example.com/repository/pub-hosted/")));
    }

    @Test
    void unicodeInThePubspecSurvivesTheRoundTrip() throws Exception {
        String pubspec = PubFixtures.pubspec("granary_demo", "1.0.0")
                .replace("A tiny package", "A tiny package \u2013 sizes \u2264 10 \u00b5m, 5 \u20ac, \u03c0 \u2248 3.14 \u2713 \ud83c\udfaf");
        publish(REPO, archive("granary_demo", "1.0.0", pubspec), token).andExpect(status().isOk());
        MvcResult result = mvc.perform(api(URL + "/api/packages/granary_demo")).andReturn();
        assertThat(str(json(result), "latest", "pubspec", "description")).startsWith("A tiny package \u2013 sizes \u2264 10 \u00b5m, 5 \u20ac, \u03c0 \u2248 3.14 \u2713 \ud83c\udfaf");
        assertThat(result.getResponse().getContentType()).contains("charset=utf-8");
    }

    @Test
    void concurrentPublishesLoseNothingAndAcceptEachVersionOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> versions = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                byte[] archive = archive("granary_demo", "1.0." + i);
                versions.add(pool.submit(() -> publish(REPO, archive, token).andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> status : versions) {
                assertThat(status.get()).isEqualTo(200);
            }
            // the same version from several publishers at once: one wins, the others are told it exists
            List<Future<Integer>> same = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                byte[] archive = archive("granary_demo", "2.0.0", PubFixtures.pubspec("granary_demo", "2.0.0") + "# upload " + i + "\n");
                same.add(pool.submit(() -> publish(REPO, archive, token).andReturn().getResponse().getStatus()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> status : same) {
                statuses.add(status.get());
            }
            assertThat(statuses).containsOnly(200, 409).filteredOn(s -> s == 200).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        JsonNode listing = json(mvc.perform(api(URL + "/api/packages/granary_demo")).andReturn());
        assertThat(versions(listing)).hasSize(13);
        assertThat(str(listing, "latest", "version")).isEqualTo("2.0.0");
        assertThat(components.findByRepositoryIdAndNamespaceAndNameOrderByVersionAsc(repo.getId(), "", "granary_demo")).hasSize(13);
        // the archive served for 2.0.0 is the one the listing promises
        byte[] served = mvc.perform(download(str(listing, "latest", "archive_url"))).andReturn().getResponse().getContentAsByteArray();
        assertThat(sha256(served)).isEqualTo(str(listing, "latest", "archive_sha256"));
    }

    @Test
    void basicAuthWithTheTokenAsPasswordWorksToo() throws Exception {
        URI start = URI.create(URL + "/api/packages/versions/new");
        mvc.perform(api(start.toString()).header("Authorization", basic("admin", token))).andExpect(status().isOk());
        mvc.perform(api(start.toString()).header("Authorization", adminBasic())).andExpect(status().isOk());
    }
}
