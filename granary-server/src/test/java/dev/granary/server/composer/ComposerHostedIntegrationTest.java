package dev.granary.server.composer;

import static dev.granary.server.composer.ComposerFixtures.NOTIFY_BODY;
import static dev.granary.server.composer.ComposerFixtures.USER_AGENT;
import static dev.granary.server.composer.ComposerFixtures.archive;
import static dev.granary.server.composer.ComposerFixtures.composerJson;
import static dev.granary.server.composer.ComposerFixtures.githubArchive;
import static dev.granary.server.composer.ComposerFixtures.nextSecond;
import static dev.granary.server.composer.ComposerFixtures.object;
import static dev.granary.server.composer.ComposerFixtures.repositoryUrl;
import static dev.granary.server.composer.ComposerFixtures.sha1;
import static dev.granary.server.composer.ComposerFixtures.str;
import static dev.granary.server.composer.ComposerFixtures.utf8;
import static dev.granary.server.composer.ComposerFixtures.versions;
import static dev.granary.server.composer.ComposerFixtures.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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

import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** A hosted Composer repository driven the way curl uploads to it and Composer 2 installs from it. */
class ComposerHostedIntegrationTest extends IntegrationTest {

    private static final String REPO = "composer-hosted";
    private static final String URL = "/repository/" + REPO;

    @Autowired
    private StorageService storage;

    @Autowired
    private ComponentDao components;

    private Repository repo;
    private final byte[] lib100 = archive(composerJson("acme/lib", null, null));

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "composer");
    }

    private ResultActions upload(String path, byte[] body) throws Exception {
        // curl --upload-file sends the archive as the raw body of a PUT, without a content type
        return mvc.perform(put(URL + "/" + path).header("Authorization", adminBasic()).content(body));
    }

    private ResultActions composerGet(String path) throws Exception {
        return mvc.perform(get(URL + "/" + path).header("User-Agent", USER_AGENT).accept(MediaType.ALL));
    }

    @Test
    void anUploadedArchiveBecomesInstallableMetadata() throws Exception {
        String sha1 = sha1(lib100);
        String dist = repositoryUrl(REPO) + "/dist/acme/lib/1.0.0/" + sha1 + ".zip";
        upload("packages/acme/lib/1.0.0", lib100)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", repositoryUrl(REPO) + "/p2/acme/lib.json"))
                .andExpect(jsonPath("$.name").value("acme/lib"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.version_normalized").value("1.0.0.0"))
                .andExpect(jsonPath("$.dist").value(dist))
                .andExpect(jsonPath("$.replaced").value(false));

        ObjectNode root = object(composerGet("packages.json")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn());
        assertThat(str(root, "metadata-url")).isEqualTo(repositoryUrl(REPO) + "/p2/%package%.json");
        assertThat(str(root, "notify-batch")).isEqualTo(repositoryUrl(REPO) + "/downloads");
        assertThat(str(root, "search")).isEqualTo(repositoryUrl(REPO) + "/search.json?q=%query%&type=%type%");
        assertThat(str(root, "list")).isEqualTo(repositoryUrl(REPO) + "/list.json");
        assertThat(str(root, "providers-api")).isEqualTo(repositoryUrl(REPO) + "/providers/%package%.json");
        assertThat(root.path("available-packages").valueStream().map(JsonNode::asString)).containsExactly("acme/lib");
        assertThat(root.path("packages").isArray()).isTrue();
        assertThat(root.has("providers-url")).isFalse();

        nextSecond();
        ObjectNode p2 = object(composerGet("p2/acme/lib.json")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().exists("ETag"))
                .andExpect(header().exists("Last-Modified"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn());
        assertThat(str(p2, "minified")).isEqualTo("composer/2.0");
        ObjectNode entry = versions(p2, "acme/lib").get("1.0.0");
        assertThat(str(entry, "name")).isEqualTo("acme/lib");
        assertThat(str(entry, "version_normalized")).isEqualTo("1.0.0.0");
        assertThat(str(entry, "description")).isEqualTo("Demo library acme/lib");
        assertThat(str(entry, "type")).isEqualTo("library");
        assertThat(str(entry, "require", "psr/log")).isEqualTo("^3.0");
        assertThat(str(entry, "require-dev", "phpunit/phpunit")).isEqualTo("^11.0");
        assertThat(str(entry, "autoload", "psr-4", "Acme\\Lib\\")).isEqualTo("src/");
        assertThat(entry.path("authors").get(0).path("name").asString()).isEqualTo("Acme Developers");
        assertThat(str(entry, "dist", "type")).isEqualTo("zip");
        assertThat(str(entry, "dist", "url")).isEqualTo(dist);
        assertThat(str(entry, "dist", "reference")).isEqualTo(sha1);
        assertThat(str(entry, "dist", "shasum")).isEqualTo(sha1);
        assertThat(str(entry, "time")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+00:00");
        assertThat(entry.has("source")).isFalse();

        // the stored document keeps a relative URL, so it survives a change of the server's address
        assertThat(storage.find(repo, "p2/acme/lib.json").orElseThrow().readUtf8())
                .contains("\"url\":\"dist/acme/lib/1.0.0/" + sha1 + ".zip\"")
                .doesNotContain("http://");
        assertThat(storage.find(repo, "p2/acme/lib.json").orElseThrow().asset().isMetadata()).isTrue();

        // branches live in their own document, which does not exist yet
        composerGet("p2/acme/lib~dev.json").andExpect(status().isNotFound());
    }

    @Test
    void distArchivesAreServedWithCacheHeadersAndCounted() throws Exception {
        upload("packages/acme/lib/1.0.0", lib100).andExpect(status().isCreated());
        String path = "dist/acme/lib/1.0.0/" + sha1(lib100) + ".zip";

        MvcResult result = composerGet(path)
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().longValue("Content-Length", lib100.length))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + sha1(lib100) + ".zip\""))
                .andExpect(header().exists("ETag"))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(lib100);
        String etag = result.getResponse().getHeader("ETag");

        mvc.perform(get(URL + "/" + path).header("If-None-Match", etag)).andExpect(status().isNotModified());
        assertThat(mvc.perform(head(URL + "/" + path)).andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", lib100.length))
                .andReturn().getResponse().getContentAsByteArray()).isEmpty();
        assertThat(storage.find(repo, path).orElseThrow().asset().getDownloadCount()).isEqualTo(1);
        assertThat(storage.find(repo, path).orElseThrow().asset().isMetadata()).isFalse();

        composerGet("dist/acme/lib/1.0.0/0000000000000000000000000000000000000000.zip").andExpect(status().isNotFound());
        composerGet("dist/acme/lib/9.9.9/" + sha1(lib100) + ".zip").andExpect(status().isNotFound());
    }

    @Test
    void metadataAnswersComposersConditionalRequests() throws Exception {
        upload("packages/acme/lib/1.0.0", lib100).andExpect(status().isCreated());
        // Last-Modified is sent once the second of the change is over (before that it would be a weak validator)
        nextSecond();
        MvcResult first = composerGet("p2/acme/lib.json").andExpect(status().isOk()).andReturn();
        String etag = first.getResponse().getHeader("ETag");
        String lastModified = first.getResponse().getHeader("Last-Modified");

        // Composer keeps Last-Modified and sends it back as If-Modified-Since
        mvc.perform(get(URL + "/p2/acme/lib.json").header("If-Modified-Since", lastModified))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag));
        mvc.perform(get(URL + "/p2/acme/lib.json").header("If-None-Match", etag)).andExpect(status().isNotModified());
        mvc.perform(get(URL + "/p2/acme/lib.json").header("If-Modified-Since", "Thu, 01 Jan 2015 00:00:00 GMT"))
                .andExpect(status().isOk());
        assertThat(mvc.perform(head(URL + "/p2/acme/lib.json")).andExpect(status().isOk())
                .andExpect(header().string("ETag", etag))
                .andReturn().getResponse().getContentAsByteArray()).isEmpty();

        // a new release changes the document
        Thread.sleep(1100);
        upload("packages/acme/lib/1.1.0", archive(composerJson("acme/lib", null, null).replace("Demo", "Newer"))).andExpect(status().isCreated());
        MvcResult second = mvc.perform(get(URL + "/p2/acme/lib.json").header("If-Modified-Since", lastModified))
                .andExpect(status().isOk()).andReturn();
        assertThat(second.getResponse().getHeader("ETag")).isNotEqualTo(etag);
        assertThat(versions(object(second), "acme/lib").keySet()).containsExactly("1.1.0", "1.0.0");
    }

    @Test
    void releasesAreSortedNewestFirstAndMinified() throws Exception {
        for (String version : List.of("1.0.0", "1.10.0", "1.2.0", "2.0.0-RC1", "v2.0.0")) {
            upload("packages/acme/lib/" + version, archive(composerJson("acme/lib", null, null))).andExpect(status().isCreated());
        }
        ObjectNode p2 = object(composerGet("p2/acme/lib.json").andExpect(status().isOk()).andReturn());
        assertThat(versions(p2, "acme/lib").keySet()).containsExactly("v2.0.0", "2.0.0-RC1", "1.10.0", "1.2.0", "1.0.0");
        assertThat(str(versions(p2, "acme/lib").get("v2.0.0"), "version_normalized")).isEqualTo("2.0.0.0");
        assertThat(str(versions(p2, "acme/lib").get("2.0.0-RC1"), "version_normalized")).isEqualTo("2.0.0.0-RC1");
        // only the first entry is complete; the others carry what differs
        JsonNode second = p2.path("packages").path("acme/lib").get(1);
        assertThat(second.has("require")).isFalse();
        assertThat(second.has("version")).isTrue();
        assertThat(second.has("dist")).isTrue();
    }

    @Test
    void branchesGoToTheDevDocument() throws Exception {
        upload("packages/acme/lib/dev-main", archive(composerJson("acme/lib", null, "\"extra\": {\"branch-alias\": {\"dev-main\": \"1.x-dev\"}}")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", repositoryUrl(REPO) + "/p2/acme/lib~dev.json"))
                .andExpect(jsonPath("$.version_normalized").value("dev-main"));
        upload("packages/acme/lib/2.x-dev", archive(composerJson("acme/lib", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version_normalized").value("2.9999999.9999999.9999999-dev"));

        ObjectNode dev = object(composerGet("p2/acme/lib~dev.json").andExpect(status().isOk()).andReturn());
        Map<String, ObjectNode> entries = versions(dev, "acme/lib");
        assertThat(entries.keySet()).containsExactly("2.x-dev", "dev-main");
        assertThat(str(entries.get("dev-main"), "extra", "branch-alias", "dev-main")).isEqualTo("1.x-dev");
        assertThat(str(entries.get("dev-main"), "dist", "url")).startsWith(repositoryUrl(REPO) + "/dist/acme/lib/dev-main/");
        composerGet("p2/acme/lib.json").andExpect(status().isNotFound());
        assertThat(mvc.perform(get(URI.create(str(entries.get("dev-main"), "dist", "url")))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isNotEmpty();
    }

    @Test
    void multipartUploadsTakeTheVersionFromTheQueryTheFormOrComposerJson() throws Exception {
        byte[] fromQuery = githubArchive("acme-lib-1a2b3c4", composerJson("acme/lib", null, null));
        mvc.perform(multipart(URL + "/packages?version=1.1.0").file(new MockMultipartFile("package", "lib.zip", "application/zip", fromQuery))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("1.1.0"));

        byte[] fromForm = archive(composerJson("acme/lib", null, null).replace("Demo", "Form"));
        mvc.perform(multipart(URL + "/packages").file(new MockMultipartFile("package", "lib.zip", "application/zip", fromForm))
                        .param("version", "1.2.0").header("Authorization", adminBasic()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("1.2.0"));

        byte[] fromJson = archive(composerJson("acme/util", "0.3.0", null));
        mvc.perform(multipart(URL + "/packages").file(new MockMultipartFile("package", "util.zip", "application/zip", fromJson))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("acme/util"))
                .andExpect(jsonPath("$.version").value("0.3.0"));

        // a PUT without a version in the URL falls back to composer.json as well, and a .zip suffix is accepted
        upload("packages", archive(composerJson("acme/tool", "2.0.0", null))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("acme/tool"));
        upload("packages/acme/tool/2.1.0.zip", archive(composerJson("acme/tool", null, null))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("2.1.0"));
        // the Nexus Repository upload URL works too
        upload("packages/upload/acme/tool/2.2.0", archive(composerJson("acme/tool", null, null))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("2.2.0"));

        assertThat(versions(object(composerGet("p2/acme/lib.json").andReturn()), "acme/lib").keySet()).containsExactly("1.2.0", "1.1.0");
        assertThat(versions(object(composerGet("p2/acme/tool.json").andReturn()), "acme/tool").keySet()).containsExactly("2.2.0", "2.1.0", "2.0.0");
        ObjectNode root = object(composerGet("packages.json").andReturn());
        assertThat(root.path("available-packages").valueStream().map(JsonNode::asString)).containsExactly("acme/lib", "acme/tool", "acme/util");
    }

    @Test
    void reuploadingAVersionIsRefusedUnlessRedeployIsAllowed() throws Exception {
        upload("packages/acme/lib/1.0.0", lib100).andExpect(status().isCreated());
        byte[] other = archive(composerJson("acme/lib", null, null).replace("Demo", "Other"));
        upload("packages/acme/lib/1.0.0", other).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("composer.versionExists"))
                .andExpect(jsonPath("$.message", containsString("already exists")));
        // the same release written differently is the same version for Composer
        upload("packages/acme/lib/v1.0", other).andExpect(status().isConflict());
        upload("packages/acme/lib/1.0.0.0", other).andExpect(status().isConflict());
        assertThat(mvc.perform(get(URL + "/dist/acme/lib/1.0.0/" + sha1(lib100) + ".zip")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(lib100);

        Repository redeploy = new Repository();
        redeploy.setName("composer-dev");
        redeploy.setFormat("composer");
        redeploy.setType(RepositoryType.HOSTED);
        redeploy.setAllowRedeploy(true);
        Repository dev = repositories.create(redeploy);
        mvc.perform(put("/repository/composer-dev/packages/acme/lib/1.0.0").header("Authorization", adminBasic()).content(lib100))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replaced").value(false));
        mvc.perform(put("/repository/composer-dev/packages/acme/lib/1.0.0").header("Authorization", adminBasic()).content(other))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replaced").value(true));
        ObjectNode p2 = object(mvc.perform(get("/repository/composer-dev/p2/acme/lib.json")).andExpect(status().isOk()).andReturn());
        assertThat(versions(p2, "acme/lib")).hasSize(1);
        assertThat(str(versions(p2, "acme/lib").get("1.0.0"), "dist", "reference")).isEqualTo(sha1(other));
        // the replaced archive is gone
        assertThat(storage.find(dev, "dist/acme/lib/1.0.0/" + sha1(lib100) + ".zip")).isEmpty();
        mvc.perform(get("/repository/composer-dev/dist/acme/lib/1.0.0/" + sha1(other) + ".zip")).andExpect(status().isOk());
    }

    @Test
    void versionsAndPackagesCanBeDeleted() throws Exception {
        upload("packages/acme/lib/1.0.0", lib100).andExpect(status().isCreated());
        byte[] lib110 = archive(composerJson("acme/lib", null, null).replace("Demo", "Next"));
        upload("packages/acme/lib/1.1.0", lib110).andExpect(status().isCreated());
        upload("packages/acme/lib/dev-main", lib110).andExpect(status().isCreated());

        mvc.perform(delete(URL + "/packages/acme/lib/1.0.0").header("Authorization", adminBasic())).andExpect(status().isNoContent());
        assertThat(versions(object(composerGet("p2/acme/lib.json").andReturn()), "acme/lib").keySet()).containsExactly("1.1.0");
        composerGet("dist/acme/lib/1.0.0/" + sha1(lib100) + ".zip").andExpect(status().isNotFound());
        mvc.perform(delete(URL + "/packages/acme/lib/1.0.0").header("Authorization", adminBasic())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("content.notFound"));

        mvc.perform(delete(URL + "/packages/acme/lib").header("Authorization", adminBasic())).andExpect(status().isNoContent());
        composerGet("p2/acme/lib.json").andExpect(status().isNotFound());
        composerGet("p2/acme/lib~dev.json").andExpect(status().isNotFound());
        composerGet("dist/acme/lib/1.1.0/" + sha1(lib110) + ".zip").andExpect(status().isNotFound());
        assertThat(object(composerGet("packages.json").andReturn()).has("available-packages")).isFalse();
        mvc.perform(delete(URL + "/packages/acme/lib").header("Authorization", adminBasic())).andExpect(status().isNotFound());

        // a version that is published again after a delete is a new upload, not a conflict
        upload("packages/acme/lib/1.0.0", lib100).andExpect(status().isCreated());
    }

    @Test
    void uploadsShowUpAsComponents() throws Exception {
        upload("packages/acme/lib/1.0.0", lib100).andExpect(status().isCreated());
        var component = components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "acme", "lib", "1.0.0");
        assertThat(component).isPresent();
        assertThat(storage.find(repo, "dist/acme/lib/1.0.0/" + sha1(lib100) + ".zip"))
                .hasValueSatisfying(c -> assertThat(c.asset().getComponentId()).isEqualTo(component.get().getId()));
        assertThat(storage.find(repo, "p2/acme/lib.json")).hasValueSatisfying(c -> assertThat(c.asset().getComponentId()).isNull());
    }

    @Test
    void brokenUploadsAreRejectedWithA400() throws Exception {
        upload("packages/acme/lib/1.0.0", new byte[0]).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("composer.badRequest"));
        upload("packages/acme/lib/1.0.0", utf8("this is not a zip")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("composer.invalidPackage"));
        upload("packages/acme/lib/1.0.0", zip(Map.of("README.md", utf8("# no composer.json")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("No composer.json")));
        upload("packages/acme/lib/1.0.0", zip(Map.of("composer.json", utf8("{ not json")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("not valid JSON")));
        upload("packages/acme/other/1.0.0", lib100).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("contains acme/lib but was uploaded as acme/other")));
        upload("packages/acme/lib/not-a-version", lib100).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("not a valid Composer version")));
        upload("packages", lib100).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("No version given")));
        upload("packages/acme/lib/2.0.0", archive(composerJson("acme/lib", "1.0.0", null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("does not match")));
        upload("packages/acme/lib/1.0.0", archive("{\"name\":\"Not A Valid Name\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("invalid name")));
        upload("packages/acme/lib/1.0.0", archive("{\"name\":\"acme/lib\",\"require\":[\"psr/log\"]}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("\"require\"")));
        upload("packages/acme/lib/1.0.0", archive("{\"name\":\"acme/lib\",\"require\":{\"psr/log\":3}}")).andExpect(status().isBadRequest());
        upload("packages/acme/lib/1.0.0", archive("[1, 2]")).andExpect(status().isBadRequest());
        mvc.perform(multipart(URL + "/packages").file(new MockMultipartFile("other", "", "text/plain", new byte[0]))
                        .param("version", "1.0.0").header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("'package'")));
        // versions that would be published verbatim but resolve as something else, or whose archive URL could
        // never be requested (the HTTP firewall rejects a ';' path parameter)
        for (String version : List.of("1.0.0%20as%202.0.0", "1.0.0@beta", "dev-fix%3Bquote")) {
            mvc.perform(put(URI.create(URL + "/packages/acme/lib?version=" + version)).header("Authorization", adminBasic()).content(lib100))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("composer.invalidPackage"));
        }

        // nothing of the above left a trace
        composerGet("p2/acme/lib.json").andExpect(status().isNotFound());
        composerGet("p2/acme/other.json").andExpect(status().isNotFound());
        assertThat(storage.list(repo, "dist/")).isEmpty();
    }

    @Test
    void oddPathsAreRefusedWithoutReachingStorage() throws Exception {
        upload("packages/acme/lib/1.0.0", lib100).andExpect(status().isCreated());
        group("composer-odd", "composer", REPO);
        for (String repository : List.of(URL, "/repository/composer-odd")) {
            for (String path : List.of("p2/acme/../acme/lib.json", "dist/acme/lib/1.0.0/../../../../p2/acme/lib.json", "p2/acme%5Clib.json",
                    "dist/acme/lib/1.0.0/..%2F..%2Fx.zip", "p2//lib.json", "dist/acme/lib/./1.0.0/x.zip", "p2/acme/lib%00.json",
                    "p2/acme/l%C3%BCb.json", "providers/..%2F..%2Fx.json", "dist/acme/lib/1.0.0%5C..%5Cx/y.zip",
                    "p2/acme/" + "a".repeat(300) + ".json")) {
                int code = mvc.perform(get(URI.create(repository + "/" + path))).andReturn().getResponse().getStatus();
                assertThat(code).as(repository + "/" + path).isEqualTo(400);
                assertThat(mvc.perform(head(URI.create(repository + "/" + path))).andReturn().getResponse().getStatus())
                        .as("HEAD " + repository + "/" + path).isEqualTo(400);
            }
        }
        mvc.perform(get(URI.create(URL + "/p2/acme/..%2Flib.json"))).andExpect(status().isBadRequest());
        mvc.perform(get(URL + "/p2/Not_A Package/x.json")).andExpect(status().isBadRequest());
        mvc.perform(put(URI.create(URL + "/packages/acme/lib/1.0.0%2F..%2F..")).header("Authorization", adminBasic()).content(lib100))
                .andExpect(status().isBadRequest());
        mvc.perform(put(URL + "/packages/-acme/lib/1.0.0").header("Authorization", adminBasic()).content(lib100))
                .andExpect(status().isBadRequest());
        // a name in another case is the same package: Composer always asks in lower case
        composerGet("p2/Acme/Lib.json").andExpect(status().isOk());
        composerGet("p2/acme/lib.txt").andExpect(status().isNotFound());
        composerGet("nothing/here").andExpect(status().isNotFound());
    }

    @Test
    void searchListAndProvidersCoverTheStoredPackages() throws Exception {
        upload("packages/acme/lib/1.0.0", lib100).andExpect(status().isCreated());
        upload("packages/acme/log-bridge/1.0.0", archive(composerJson("acme/log-bridge", null,
                "\"provide\": {\"psr/log-implementation\": \"3.0\"}").replace("\"type\": \"library\"", "\"type\": \"symfony-bundle\"")))
                .andExpect(status().isCreated());
        upload("packages/other/thing/0.1.0", archive(composerJson("other/thing", null, null).replace("Demo library other/thing", "Unrelated")))
                .andExpect(status().isCreated());

        ObjectNode search = object(composerGet("search.json?q=acme&type=").andExpect(status().isOk()).andReturn());
        assertThat(search.path("results").valueStream().map(r -> r.path("name").asString())).containsExactly("acme/lib", "acme/log-bridge");
        assertThat(search.path("total").asLong()).isEqualTo(2);
        JsonNode first = search.path("results").get(0);
        assertThat(str(first, "description")).isEqualTo("Demo library acme/lib");
        assertThat(str(first, "url")).isEqualTo(repositoryUrl(REPO) + "/p2/acme/lib.json");
        mvc.perform(get(URL + "/search.json").param("q", "acme").param("type", "symfony-bundle")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].name").value("acme/log-bridge"));
        mvc.perform(get(URL + "/search.json").param("q", "greeting unrelated")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].name").value("other/thing"));
        mvc.perform(get(URL + "/search.json").param("q", "acme").param("per_page", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.next", containsString("page=2")));

        mvc.perform(get(URL + "/list.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.packageNames.length()").value(3));
        mvc.perform(get(URL + "/list.json").param("vendor", "acme")).andExpect(status().isOk())
                .andExpect(jsonPath("$.packageNames.length()").value(2));
        mvc.perform(get(URL + "/list.json").param("filter", "*bridge")).andExpect(status().isOk())
                .andExpect(jsonPath("$.packageNames[0]").value("acme/log-bridge"));

        mvc.perform(get(URL + "/providers/psr/log-implementation.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.providers.length()").value(1))
                .andExpect(jsonPath("$.providers[0].name").value("acme/log-bridge"))
                .andExpect(jsonPath("$.providers[0].type").value("symfony-bundle"));
    }

    @Test
    void notificationsAndAuditsAreAcknowledged() throws Exception {
        upload("packages/acme/lib/1.0.0", lib100).andExpect(status().isCreated());
        // what Composer posts to notify-batch after an install
        mvc.perform(post(URL + "/downloads").contentType(MediaType.APPLICATION_JSON).content(NOTIFY_BODY).header("User-Agent", USER_AGENT))
                .andExpect(status().isCreated());
        // composer audit: hosted packages have no advisories on record, which is an answer, not an error
        mvc.perform(post(URL + "/api/security-advisories").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(ComposerFixtures.AUDIT_BODY).header("User-Agent", USER_AGENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisories").isMap());
        mvc.perform(get(URL + "/downloads")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(URL + "/p2/acme/lib.json").header("Authorization", adminBasic())).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(URL + "/dist/acme/lib/1.0.0/" + sha1(lib100) + ".zip").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
    }
}
