package dev.granary.server.npm;

import static dev.granary.server.npm.NpmFixtures.bytes;
import static dev.granary.server.npm.NpmFixtures.object;
import static dev.granary.server.npm.NpmFixtures.publishBody;
import static dev.granary.server.npm.NpmFixtures.repositoryUrl;
import static dev.granary.server.npm.NpmFixtures.str;
import static dev.granary.server.npm.NpmFixtures.tarball;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.node.ObjectNode;

/** The hosted half of the npm protocol: publish, install, dist-tags, deprecate and unpublish, driven like the npm CLI does. */
class NpmHostedIntegrationTest extends IntegrationTest {

    private static final String REPO = "npm-hosted";
    private static final String URL = "/repository/" + REPO;

    @Autowired
    private ComponentDao components;

    @Autowired
    private StorageService storage;

    private Repository repo;

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "npm");
    }

    @Test
    void publishThenInstall() throws Exception {
        byte[] tgz = tarball("demo-1.0.0");
        mvc.perform(put(URL + "/demo").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody("demo", "1.0.0", tgz)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.id").value("demo"));

        MvcResult packument = mvc.perform(get(URL + "/demo")
                        .accept("application/vnd.npm.install-v1+json; q=1.0, application/json; q=0.8, */*"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().exists("ETag"))
                .andReturn();
        ObjectNode doc = object(packument);
        assertThat(str(doc, "name")).isEqualTo("demo");
        assertThat(str(doc, "dist-tags", "latest")).isEqualTo("1.0.0");
        assertThat(str(doc, "versions", "1.0.0", "dist", "tarball")).isEqualTo(repositoryUrl(REPO) + "/demo/-/demo-1.0.0.tgz");
        assertThat(str(doc, "versions", "1.0.0", "dist", "integrity")).startsWith("sha512-");
        assertThat(str(doc, "versions", "1.0.0", "dist", "shasum")).hasSize(40);
        assertThat(str(doc, "time", "1.0.0")).isNotNull();
        assertThat(str(doc, "_rev")).startsWith("1-");
        assertThat(doc.has("_attachments")).isFalse();

        MvcResult download = mvc.perform(get(URL + "/demo/-/demo-1.0.0.tgz"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Checksum-Sha256"))
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(tgz);

        mvc.perform(get(URL + "/demo/1.0.0")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.dist.tarball").value(repositoryUrl(REPO) + "/demo/-/demo-1.0.0.tgz"));
        mvc.perform(get(URL + "/demo/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.0"));
        mvc.perform(get(URL + "/demo/2.0.0")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("version not found: 2.0.0"));

        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "demo", "1.0.0")).isPresent();
        assertThat(storage.find(repo, "demo")).hasValueSatisfying(c -> assertThat(c.asset().isMetadata()).isTrue());
        assertThat(storage.find(repo, "demo/-/demo-1.0.0.tgz")).hasValueSatisfying(c -> assertThat(c.asset().isMetadata()).isFalse());
    }

    @Test
    void conditionalGetOfThePackumentIs304() throws Exception {
        publish("demo", "1.0.0");
        String etag = mvc.perform(get(URL + "/demo")).andReturn().getResponse().getHeader("ETag");
        mvc.perform(get(URL + "/demo").header("If-None-Match", etag)).andExpect(status().isNotModified());
        publish("demo", "1.1.0");
        mvc.perform(get(URL + "/demo").header("If-None-Match", etag)).andExpect(status().isOk());
    }

    @Test
    void republishingAVersionIsAConflict() throws Exception {
        publish("demo", "1.0.0");
        mvc.perform(put(URL + "/demo").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody("demo", "1.0.0", tarball("again"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("cannot modify pre-existing version"));
        assertThat(mvc.perform(get(URL + "/demo/-/demo-1.0.0.tgz")).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(tarball("demo-1.0.0"));
    }

    @Test
    void republishingIsAllowedWhenTheRepositoryPermitsRedeploy() throws Exception {
        Repository redeploy = new Repository();
        redeploy.setName("npm-snapshots");
        redeploy.setFormat("npm");
        redeploy.setType(RepositoryType.HOSTED);
        redeploy.setAllowRedeploy(true);
        repositories.create(redeploy);

        for (String seed : new String[] {"first", "second"}) {
            mvc.perform(put("/repository/npm-snapshots/demo").header("Authorization", adminBasic())
                            .contentType(MediaType.APPLICATION_JSON).content(publishBody("demo", "1.0.0", tarball(seed))))
                    .andExpect(status().isCreated());
        }
        assertThat(mvc.perform(get("/repository/npm-snapshots/demo/-/demo-1.0.0.tgz")).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(tarball("second"));
    }

    @Test
    void scopedPackagesWorkWithEncodedAndPlainSlashes() throws Exception {
        byte[] tgz = tarball("@acme/ui-1.0.0");
        mvc.perform(put(URI.create(URL + "/@acme%2Fui")).header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody("@acme/ui", "1.0.0", tgz)))
                .andExpect(status().isCreated());

        for (String path : new String[] {URL + "/@acme%2Fui", URL + "/@acme/ui", URL + "/@acme%2fui"}) {
            MvcResult result = mvc.perform(get(URI.create(path))).andExpect(status().isOk()).andReturn();
            assertThat(str(object(result), "versions", "1.0.0", "dist", "tarball"))
                    .isEqualTo(repositoryUrl(REPO) + "/@acme/ui/-/ui-1.0.0.tgz");
        }
        assertThat(mvc.perform(get(URL + "/@acme/ui/-/ui-1.0.0.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(tgz);
        mvc.perform(get(URI.create(URL + "/-/package/@acme%2Fui/dist-tags"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.latest").value("1.0.0"));
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "acme", "ui", "1.0.0")).isPresent();
    }

    @Test
    void laterVersionsMoveLatest() throws Exception {
        publish("demo", "1.0.0");
        publish("demo", "1.1.0");
        mvc.perform(put(URL + "/demo").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody("demo", "0.9.0", tarball("old"), "legacy")))
                .andExpect(status().isCreated());
        mvc.perform(get(URL + "/-/package/demo/dist-tags")).andExpect(status().isOk())
                .andExpect(jsonPath("$.latest").value("1.1.0"))
                .andExpect(jsonPath("$.legacy").value("0.9.0"));
        ObjectNode doc = object(mvc.perform(get(URL + "/demo")).andReturn());
        assertThat(doc.path("versions").propertyNames()).containsExactlyInAnyOrder("1.0.0", "1.1.0", "0.9.0");
        assertThat(str(doc, "description")).isEqualTo("description of demo@1.1.0");
    }

    @Test
    void distTagsCanBeAddedAndRemoved() throws Exception {
        publish("demo", "1.0.0");
        mvc.perform(put(URL + "/-/package/demo/dist-tags/beta").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content("\"1.0.0\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ok").value(true));
        mvc.perform(get(URL + "/-/package/demo/dist-tags")).andExpect(status().isOk())
                .andExpect(jsonPath("$.latest").value("1.0.0"))
                .andExpect(jsonPath("$.beta").value("1.0.0"));
        mvc.perform(get(URL + "/demo/beta")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("1.0.0"));

        mvc.perform(put(URL + "/-/package/demo/dist-tags/beta").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content("\"9.9.9\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        mvc.perform(delete(URL + "/-/package/demo/dist-tags/beta").header("Authorization", adminBasic()))
                .andExpect(status().isOk());
        mvc.perform(get(URL + "/-/package/demo/dist-tags")).andExpect(status().isOk())
                .andExpect(jsonPath("$.beta").doesNotExist());
        mvc.perform(delete(URL + "/-/package/demo/dist-tags/latest").header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(URL + "/-/package/nothing/dist-tags")).andExpect(status().isNotFound());
    }

    @Test
    void deprecateUpdatesTheStoredDocument() throws Exception {
        publish("demo", "1.0.0");
        ObjectNode doc = object(mvc.perform(get(URL + "/demo?write=true")).andReturn());
        ((ObjectNode) doc.path("versions").path("1.0.0")).put("deprecated", "use something else");
        mvc.perform(put(URL + "/demo").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(bytes(doc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mvc.perform(get(URL + "/demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.versions['1.0.0'].deprecated").value("use something else"))
                .andExpect(jsonPath("$._rev").value(org.hamcrest.Matchers.startsWith("2-")));
    }

    @Test
    void unpublishOneVersionTheWayNpmDoes() throws Exception {
        publish("demo", "1.0.0");
        publish("demo", "1.1.0");
        ObjectNode doc = object(mvc.perform(get(URL + "/demo?write=true")).andReturn());
        String rev = str(doc, "_rev");
        ((ObjectNode) doc.path("versions")).remove("1.1.0");
        ((ObjectNode) doc.path("dist-tags")).put("latest", "1.0.0");
        doc.remove("_attachments");

        mvc.perform(put(URL + "/demo/-rev/" + rev).header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(bytes(doc)))
                .andExpect(status().isCreated());
        String newRev = str(object(mvc.perform(get(URL + "/demo?write=true")).andReturn()), "_rev");
        mvc.perform(delete(URL + "/demo/-/demo-1.1.0.tgz/-rev/" + newRev).header("Authorization", adminBasic()))
                .andExpect(status().isOk());

        ObjectNode after = object(mvc.perform(get(URL + "/demo")).andExpect(status().isOk()).andReturn());
        assertThat(after.path("versions").propertyNames()).containsExactly("1.0.0");
        assertThat(str(after, "dist-tags", "latest")).isEqualTo("1.0.0");
        assertThat(after.path("time").has("1.1.0")).isFalse();
        mvc.perform(get(URL + "/demo/-/demo-1.1.0.tgz")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/demo/-/demo-1.0.0.tgz")).andExpect(status().isOk());
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "demo", "1.1.0")).isEmpty();
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "demo", "1.0.0")).isPresent();
    }

    @Test
    void unpublishTheWholePackage() throws Exception {
        publish("demo", "1.0.0");
        publish("demo", "2.0.0");
        publish("keep", "1.0.0");
        String rev = str(object(mvc.perform(get(URL + "/demo?write=true")).andReturn()), "_rev");

        mvc.perform(delete(URL + "/demo/-rev/" + rev).header("Authorization", adminBasic())).andExpect(status().isOk());

        mvc.perform(get(URL + "/demo")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get(URL + "/demo/-/demo-1.0.0.tgz")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/demo/-/demo-2.0.0.tgz")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/keep")).andExpect(status().isOk());
        assertThat(components.findByRepositoryIdAndNamespaceAndNameOrderByVersionAsc(repo.getId(), "", "demo")).isEmpty();
        assertThat(storage.list(repo, "demo")).extracting(a -> a.getPath()).doesNotContain("demo", "demo/-/demo-1.0.0.tgz");
        mvc.perform(delete(URL + "/demo/-rev/" + rev).header("Authorization", adminBasic())).andExpect(status().isNotFound());
    }

    @Test
    void unpublishingTheLastVersionRemovesThePackage() throws Exception {
        publish("demo", "1.0.0");
        ObjectNode doc = object(mvc.perform(get(URL + "/demo?write=true")).andReturn());
        ((ObjectNode) doc.path("versions")).remove("1.0.0");
        mvc.perform(put(URL + "/demo/-rev/" + str(doc, "_rev")).header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(bytes(doc)))
                .andExpect(status().isCreated());
        mvc.perform(get(URL + "/demo")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/demo/-/demo-1.0.0.tgz")).andExpect(status().isNotFound());
    }

    @Test
    void searchFindsPublishedPackages() throws Exception {
        publish("demo", "1.0.0");
        publish("demo", "1.1.0");
        publish("@acme/demo-ui", "2.0.0");
        publish("other", "1.0.0");

        MvcResult result = mvc.perform(get(URL + "/-/v1/search").param("text", "demo").param("size", "20").param("from", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.time").exists())
                .andReturn();
        ObjectNode json = object(result);
        assertThat(json.path("objects")).hasSize(2);
        assertThat(str(json.path("objects").get(0).path("package"), "name")).isEqualTo("demo");
        assertThat(str(json.path("objects").get(0).path("package"), "version")).isEqualTo("1.1.0");
        assertThat(str(json.path("objects").get(0).path("package"), "description")).isEqualTo("description of demo@1.1.0");
        assertThat(str(json.path("objects").get(0).path("package"), "links", "npm")).isEqualTo(repositoryUrl(REPO) + "/demo");
        assertThat(str(json.path("objects").get(1).path("package"), "name")).isEqualTo("@acme/demo-ui");
        assertThat(str(json.path("objects").get(1).path("package"), "scope")).isEqualTo("acme");

        mvc.perform(get(URL + "/-/v1/search").param("text", "zzz")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.objects").isEmpty());
    }

    @Test
    void malformedPublishBodiesAreRejectedWithJsonErrors() throws Exception {
        mvc.perform(put(URL + "/demo").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(put(URL + "/demo").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody("other", "1.0.0", tarball("x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("does not match")));
        ObjectNode lying = NpmFixtures.publishDocument("demo", "1.0.0", tarball("x"), "latest");
        ((ObjectNode) lying.path("versions").path("1.0.0").path("dist")).put("integrity", "sha512-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==");
        mvc.perform(put(URL + "/demo").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(bytes(lying)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("integrity")));
        mvc.perform(get(URL + "/demo")).andExpect(status().isNotFound());
    }

    @Test
    void unknownPathsAndMethodsAnswerInNpmStyle() throws Exception {
        mvc.perform(get(URL + "/-/something/odd")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("not found"));
        publish("demo", "1.0.0");
        mvc.perform(put(URL + "/demo/-/demo-1.0.0.tgz").header("Authorization", adminBasic()).content(tarball("x")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(get(URL + "/")).andExpect(status().isOk()).andExpect(jsonPath("$.format").value("npm"));
        mvc.perform(get(URL + "/..%2Fetc")).andExpect(status().is4xxClientError());
        assertThat(mvc.perform(get(URL + "/demo")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"name\":\"demo\"");
    }

    private void publish(String name, String version) throws Exception {
        mvc.perform(put(URI.create(URL + "/" + name.replace("/", "%2F"))).header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody(name, version, tarball(name + "-" + version))))
                .andExpect(status().isCreated());
    }
}
