package dev.granary.server.cocoapods;

import static dev.granary.server.cocoapods.CocoaPodsFixtures.gitPodspec;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.httpPodspec;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.json;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.podspecPath;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.pushWithArchive;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.putPodspec;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.repositoryUrl;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.sha256;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.shardFile;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.str;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.text;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.zip;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** A hosted CDN source: publishing with curl, the indexes pod reads, archives, deprecation and deletion. */
class CocoaPodsHostedIntegrationTest extends IntegrationTest {

    private static final String REPO = "cocoapods-hosted";
    private static final String URL = "/repository/" + REPO;

    @Autowired
    private StorageService storage;

    @Autowired
    private ComponentDao components;

    private Repository repo;

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "cocoapods");
    }

    private void publish(String name, String version, String json) throws Exception {
        mvc.perform(putPodspec(REPO, name, version, json).header("Authorization", adminBasic())).andExpect(status().isCreated());
    }

    private String fetch(String path) throws Exception {
        return text(mvc.perform(get(URL + "/" + path)).andExpect(status().isOk()).andReturn());
    }

    @Test
    void aPublishedPodIsInTheIndexesPodReads() throws Exception {
        String spec = gitPodspec("AFNetworking", "4.0.1");
        mvc.perform(putPodspec(REPO, "AFNetworking", "4.0.1", spec).header("Authorization", adminBasic()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("AFNetworking"))
                .andExpect(jsonPath("$.version").value("4.0.1"))
                .andExpect(jsonPath("$.podspec").value(repositoryUrl(REPO) + "/Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json"));

        mvc.perform(get(URL + "/CocoaPods-version.yml")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/yaml"))
                .andExpect(content().string(containsString("prefix_lengths:\n- 1\n- 1\n- 1\n")))
                .andExpect(content().string(containsString("last: ")));
        mvc.perform(get(URL + "/all_pods_versions_a_7_5.txt")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("AFNetworking/4.0.1\n"));
        assertThat(fetch("all_pods.txt")).isEqualTo("AFNetworking\n");
        assertThat(fetch("deprecated_podspecs.txt")).isEmpty();
        // shards nothing hashes into are empty, not missing: pod treats a 404 shard as an error
        assertThat(fetch("all_pods_versions_0_0_0.txt")).isEmpty();

        // the podspec is served exactly as it was published
        MvcResult podspec = mvc.perform(get(URL + "/Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(text(podspec)).isEqualTo(spec);
        assertThat(fetch("Specs/AFNetworking/4.0.1/AFNetworking.podspec.json")).isEqualTo(spec);
        assertThat(storage.find(repo, "Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json").orElseThrow().asset().isMetadata())
                .isFalse();
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "AFNetworking", "4.0.1")).isPresent();

        mvc.perform(get(URL + "/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("pod repo add-cdn granary " + repositoryUrl(REPO) + "/")));
    }

    @Test
    void indexesAnswerTheETagPodSendsBack() throws Exception {
        publish("Alamofire", "5.9.1", gitPodspec("Alamofire", "5.9.1"));
        String shard = "/" + shardFile("Alamofire");
        MvcResult first = mvc.perform(get(URL + shard)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache")).andReturn();
        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        mvc.perform(get(URL + shard).header("If-None-Match", etag)).andExpect(status().isNotModified());
        mvc.perform(get(URL + shard).header("If-None-Match", "W/" + etag)).andExpect(status().isNotModified());
        mvc.perform(get(URL + shard).header("If-None-Match", "\"stale\", " + etag)).andExpect(status().isNotModified());
        mvc.perform(get(URL + shard).header("If-None-Match", "*")).andExpect(status().isNotModified());
        mvc.perform(get(URL + shard).header("If-None-Match", "\"stale\"")).andExpect(status().isOk());

        publish("Alamofire", "5.10.0", gitPodspec("Alamofire", "5.10.0"));
        MvcResult changed = mvc.perform(get(URL + shard).header("If-None-Match", etag)).andExpect(status().isOk()).andReturn();
        assertThat(text(changed)).isEqualTo("Alamofire/5.9.1/5.10.0\n");
        assertThat(changed.getResponse().getHeader("ETag")).isNotEqualTo(etag);

        MvcResult podspecHead = mvc.perform(head(URL + "/" + podspecPath("Alamofire", "5.9.1"))).andExpect(status().isOk()).andReturn();
        assertThat(podspecHead.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(podspecHead.getResponse().getContentLength()).isEqualTo(gitPodspec("Alamofire", "5.9.1").length());
        String podspecEtag = mvc.perform(get(URL + "/" + podspecPath("Alamofire", "5.9.1"))).andReturn().getResponse().getHeader("ETag");
        mvc.perform(get(URL + "/" + podspecPath("Alamofire", "5.9.1")).header("If-None-Match", podspecEtag))
                .andExpect(status().isNotModified());
    }

    @Test
    void versionsAreListedInCocoaPodsOrderAndNamesInCodePointOrder() throws Exception {
        for (String version : new String[] {"1.10.0", "1.2.0", "1.0.0", "1.2.0-beta.1", "1.2.0-beta.10", "1.2.0-beta.2"}) {
            publish("Kit", version, gitPodspec("Kit", version));
        }
        assertThat(fetch(shardFile("Kit"))).isEqualTo("Kit/1.0.0/1.2.0-beta.1/1.2.0-beta.2/1.2.0-beta.10/1.2.0/1.10.0\n");

        publish("kit-lower", "1.0", gitPodspec("kit-lower", "1.0"));
        publish("Zeta", "1.0", gitPodspec("Zeta", "1.0"));
        publish("cocoapods制作", "1.0", gitPodspec("cocoapods制作", "1.0"));
        publish("Floater💩", "1.0", gitPodspec("Floater💩", "1.0"));
        assertThat(fetch("all_pods.txt")).isEqualTo("Floater💩\nKit\nZeta\ncocoapods制作\nkit-lower\n");

        // pod percent-encodes the UTF-8 of such names in the URL
        URI encoded = URI.create(URL + "/Specs/2/8/c/cocoapods%E5%88%B6%E4%BD%9C/1.0/cocoapods%E5%88%B6%E4%BD%9C.podspec.json");
        MvcResult cjk = mvc.perform(get(encoded)).andExpect(status().isOk()).andReturn();
        assertThat(str(json(cjk), "name")).isEqualTo("cocoapods制作");
        assertThat(fetch("all_pods_versions_2_8_c.txt")).isEqualTo("cocoapods制作/1.0\n");
        assertThat(fetch("all_pods_versions_d_6_c.txt")).isEqualTo("Floater💩/1.0\n");
    }

    @Test
    void republishingIsRefusedUnlessTheRepositoryAllowsRedeploys() throws Exception {
        publish("Redo", "1.0.0", gitPodspec("Redo", "1.0.0"));
        mvc.perform(putPodspec(REPO, "Redo", "1.0.0", gitPodspec("Redo", "1.0.0").replace("The Redo pod.", "Changed."))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("repository.cocoapods.versionExists"));
        assertThat(fetch(podspecPath("Redo", "1.0.0"))).contains("The Redo pod.");

        Repository changes = repositories.require(REPO);
        changes.setAllowRedeploy(true);
        repositories.update(REPO, changes);
        publish("Redo", "1.0.0", gitPodspec("Redo", "1.0.0").replace("The Redo pod.", "Changed."));
        assertThat(fetch(podspecPath("Redo", "1.0.0"))).contains("Changed.");
        assertThat(fetch(shardFile("Redo"))).isEqualTo("Redo/1.0.0\n");
    }

    @Test
    void aPodPushedWithItsArchiveDownloadsTheArchiveFromHere() throws Exception {
        byte[] archive = zip("Zipped.xcframework/Info.plist", "<plist version=\"1.0\"/>");
        String spec = httpPodspec("Zipped", "1.2.0", "https://downloads.example.com/Zipped-1.2.0.zip", "\"flatten\": true");
        String sourceUrl = repositoryUrl(REPO) + "/sources/Zipped/1.2.0/Zipped-1.2.0.zip";
        mvc.perform(pushWithArchive(REPO, spec, "Zipped-1.2.0.zip", archive).header("Authorization", adminBasic()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.podspec").value(repositoryUrl(REPO) + "/" + podspecPath("Zipped", "1.2.0")))
                .andExpect(jsonPath("$.source").value(sourceUrl));

        JsonNode served = CocoaPodsFixtures.MAPPER.readTree(fetch(podspecPath("Zipped", "1.2.0")));
        assertThat(str(served, "source", "http")).isEqualTo(sourceUrl);
        assertThat(str(served, "source", "sha256")).isEqualTo(sha256(archive));
        assertThat(served.path("source").path("flatten").asBoolean()).isTrue();
        assertThat(str(served, "vendored_frameworks")).isEqualTo("Zipped.xcframework");

        MvcResult download = mvc.perform(get(URL + "/sources/Zipped/1.2.0/Zipped-1.2.0.zip")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"Zipped-1.2.0.zip\""))
                .andExpect(content().contentTypeCompatibleWith("application/zip"))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(archive);

        // the archive URL follows the address clients use, e.g. after a base URL is configured
        settings.setBaseUrl("https://pods.example.com");
        JsonNode moved = CocoaPodsFixtures.MAPPER.readTree(fetch(podspecPath("Zipped", "1.2.0")));
        assertThat(str(moved, "source", "http")).isEqualTo("https://pods.example.com/repository/" + REPO
                + "/sources/Zipped/1.2.0/Zipped-1.2.0.zip");
    }

    @Test
    void archivesAreCheckedAgainstThePodspec() throws Exception {
        byte[] archive = zip("a.txt", "a");
        String wrongSum = httpPodspec("Sum", "1.0", "https://x.example/Sum.zip", "\"sha256\": \"" + "0".repeat(64) + "\"");
        mvc.perform(pushWithArchive(REPO, wrongSum, "Sum.zip", archive).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("does not match the uploaded archive")));
        String rightSum = httpPodspec("Sum", "1.0", "https://x.example/Sum.zip", "\"sha256\": \"" + sha256(archive).toUpperCase() + "\"");
        mvc.perform(pushWithArchive(REPO, rightSum, "Sum.zip", archive).header("Authorization", adminBasic()))
                .andExpect(status().isCreated());

        mvc.perform(pushWithArchive(REPO, httpPodspec("Fake", "1.0", "https://x.example/Fake.zip", null), "Fake.zip",
                        "<html>not found</html>".getBytes(StandardCharsets.UTF_8)).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("not a valid zip file")));
        mvc.perform(pushWithArchive(REPO, httpPodspec("Odd", "1.0", "https://x.example/download", null), "download", archive)
                        .header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot tell the archive type")));
        // a podspec without a source needs the archive
        mvc.perform(pushWithArchive(REPO, "{\"name\": \"Bare\", \"version\": \"1.0\"}", null, null).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.cocoapods.invalidPodspec"));
        assertThat(fetch("all_pods.txt")).isEqualTo("Sum\n");
    }

    @Test
    void anArchiveUploadedOnItsOwnCanBeReferencedByAPodspec() throws Exception {
        byte[] archive = zip("Bin.xcframework/Info.plist", "<plist/>");
        mvc.perform(put(URL + "/sources/Bin/2.0.0/Bin-2.0.0.zip").header("Authorization", adminBasic()).content(archive))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value(repositoryUrl(REPO) + "/sources/Bin/2.0.0/Bin-2.0.0.zip"));
        mvc.perform(put(URL + "/sources/Bin/2.0.0/Bin-2.0.0.zip").header("Authorization", adminBasic()).content(archive))
                .andExpect(status().isConflict());
        mvc.perform(put(URL + "/sources/Bin/2.0.0/notes.txt").header("Authorization", adminBasic()).content(archive))
                .andExpect(status().isBadRequest());

        publish("Bin", "2.0.0", httpPodspec("Bin", "2.0.0", repositoryUrl(REPO) + "/sources/Bin/2.0.0/Bin-2.0.0.zip", null));
        assertThat(str(CocoaPodsFixtures.MAPPER.readTree(fetch(podspecPath("Bin", "2.0.0"))), "source", "http"))
                .isEqualTo(repositoryUrl(REPO) + "/sources/Bin/2.0.0/Bin-2.0.0.zip");
        assertThat(mvc.perform(get(URL + "/sources/Bin/2.0.0/Bin-2.0.0.zip")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray()).isEqualTo(archive);
    }

    @Test
    void deprecatedPodspecsAreListedForPodRepoUpdate() throws Exception {
        publish("OldKit", "1.0", CocoaPodsFixtures.deprecatedPodspec("OldKit", "1.0", "NewKit"));
        publish("OldKit", "1.1", gitPodspec("OldKit", "1.1"));
        assertThat(fetch("deprecated_podspecs.txt")).isEqualTo(podspecPath("OldKit", "1.0") + "\n");
        assertThat(storage.find(repo, "deprecated_podspecs.txt").orElseThrow().asset().isMetadata()).isTrue();

        mvc.perform(delete(URL + "/Specs/OldKit/1.0").header("Authorization", adminBasic())).andExpect(status().isNoContent());
        assertThat(fetch("deprecated_podspecs.txt")).isEmpty();
        assertThat(fetch(shardFile("OldKit"))).isEqualTo("OldKit/1.1\n");
    }

    @Test
    void deletingAVersionRemovesItsPodspecAndArchive() throws Exception {
        byte[] archive = zip("a", "b");
        mvc.perform(pushWithArchive(REPO, httpPodspec("Gone", "1.0", "https://x.example/Gone-1.0.zip", null), "Gone-1.0.zip", archive)
                .header("Authorization", adminBasic())).andExpect(status().isCreated());
        publish("Gone", "2.0", gitPodspec("Gone", "2.0"));

        mvc.perform(delete(URL + "/" + podspecPath("Gone", "1.0")).header("Authorization", adminBasic())).andExpect(status().isNoContent());
        mvc.perform(get(URL + "/" + podspecPath("Gone", "1.0"))).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/sources/Gone/1.0/Gone-1.0.zip")).andExpect(status().isNotFound());
        assertThat(fetch(shardFile("Gone"))).isEqualTo("Gone/2.0\n");
        mvc.perform(delete(URL + "/Specs/Gone/1.0").header("Authorization", adminBasic())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("content.notFound"));

        mvc.perform(delete(URL + "/Specs/Gone/2.0").header("Authorization", adminBasic())).andExpect(status().isNoContent());
        assertThat(fetch(shardFile("Gone"))).isEmpty();
        assertThat(fetch("all_pods.txt")).isEmpty();
    }

    @Test
    void badPushesAreRefusedWithAReason() throws Exception {
        mvc.perform(putPodspec(REPO, "Other", "1.0", gitPodspec("Mine", "1.0")).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.cocoapods.invalidPodspec"))
                .andExpect(jsonPath("$.message").value(containsString("the URL names 'Other'")));
        mvc.perform(putPodspec(REPO, "Ruby", "1.0", "Pod::Spec.new do |s|\n  s.name = 'Ruby'\nend\n").header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("pod ipc spec")));
        mvc.perform(putPodspec(REPO, "Beta", "1.0 beta", gitPodspec("Beta", "1.0 beta")).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("not a valid CocoaPods version")));
        mvc.perform(pushWithArchive(REPO, gitPodspec("Two Words", "1.0"), null, null).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("whitespace")));
        mvc.perform(put(URL + "/Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(gitPodspec("Other", "4.0.1")))
                .andExpect(status().isBadRequest());
        mvc.perform(put(URL + "/all_pods.txt").header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post(URL + "/" + podspecPath("Mine", "1.0")).header("Authorization", adminBasic()).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(get(URI.create(URL + "/Specs/a/b/c/..%2F..%2F..%2Fx/1.0/x.podspec.json"))).andExpect(status().isBadRequest());
        assertThat(fetch("all_pods.txt")).isEmpty();
    }

    @Test
    void anEncodedSlashIsNeverReadAsASeparator() throws Exception {
        publish("Slash", "1.0", gitPodspec("Slash", "1.0"));
        assertThat(fetch("Specs/Slash/1.0/Slash.podspec.json")).contains("\"Slash\"");

        // decoded, these would name the podspec above; as sent they are one odd segment
        for (String path : new String[] {"/Specs/Slash%2F1.0/Slash.podspec.json", "/Specs%2FSlash/1.0/Slash.podspec.json",
                "/Specs/Slash/1.0%2fSlash.podspec.json"}) {
            MvcResult refused = mvc.perform(get(URI.create(URL + path))).andReturn();
            assertThat(refused.getResponse().getStatus() + " " + text(refused)).as(path).startsWith("400 ").contains("path.invalid");
        }
        mvc.perform(put(URI.create(URL + "/Specs/Other%2F1.0/Other.podspec.json")).header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(gitPodspec("Other", "1.0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("path.invalid"));
        assertThat(fetch("all_pods.txt")).isEqualTo("Slash\n");
    }
}
