package dev.granary.server.terraform;

import static dev.granary.server.terraform.TerraformFixtures.HASHICORP_KEY_ID;
import static dev.granary.server.terraform.TerraformFixtures.archiveName;
import static dev.granary.server.terraform.TerraformFixtures.gpgKey;
import static dev.granary.server.terraform.TerraformFixtures.moduleTgz;
import static dev.granary.server.terraform.TerraformFixtures.object;
import static dev.granary.server.terraform.TerraformFixtures.providerZip;
import static dev.granary.server.terraform.TerraformFixtures.repositoryUrl;
import static dev.granary.server.terraform.TerraformFixtures.sha256;
import static dev.granary.server.terraform.TerraformFixtures.shaSums;
import static dev.granary.server.terraform.TerraformFixtures.signature;
import static dev.granary.server.terraform.TerraformFixtures.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.node.ObjectNode;

/**
 * The hosted half: provider releases and module archives are uploaded with plain PUTs (as curl sends them)
 * and the CLI's requests (service discovery, versions, download documents, archives, checksums, signatures,
 * network mirror) are answered from what was stored.
 */
class TerraformHostedIntegrationTest extends IntegrationTest {

    private static final String REPO = "terraform-hosted";
    private static final String URL = "/repository/" + REPO;
    private static final String ZIP = archiveName("null", "3.2.4", "linux", "amd64");
    private static final List<String> TEST_USERS = List.of("tf-reader", "tf-dev");

    @Autowired
    private ComponentDao components;

    @Autowired
    private StorageService storage;

    private Repository repo;

    @BeforeEach
    void createRepository() {
        repo = hosted(REPO, "terraform");
    }

    @AfterEach
    void removeTestUsers() {
        for (String username : TEST_USERS) {
            users.find(username).ifPresent(u -> users.delete(username));
        }
    }

    private void uploadProvider(String ns, byte[] zip) throws Exception {
        mvc.perform(put(URL + "/v1/providers/" + ns + "/null/3.2.4/linux/amd64/" + ZIP).header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(zip))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sha256").value(sha256(zip)))
                .andExpect(jsonPath("$.filename").value(ZIP));
        mvc.perform(put(URL + "/v1/providers/" + ns + "/null/3.2.4/SHA256SUMS").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(shaSums(ZIP, zip)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.files").value(1));
        mvc.perform(put(URL + "/v1/providers/" + ns + "/null/3.2.4/SHA256SUMS.sig").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(signature()))
                .andExpect(status().isCreated());
        mvc.perform(put(URL + "/v1/providers/" + ns + "/null/gpg-key").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(gpgKey(HASHICORP_KEY_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gpg_public_keys[0].key_id").value(HASHICORP_KEY_ID));
    }

    @Test
    void uploadThenInstallTheWayTheCliAsks() throws Exception {
        byte[] zip = providerZip("3.2.4");
        uploadProvider("hashicorp", zip);

        // 1. service discovery (terraform sends Accept: application/json)
        mvc.perform(get(URL + "/.well-known/terraform.json").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$['providers.v1']").value(repositoryUrl(REPO) + "/v1/providers/"))
                .andExpect(jsonPath("$['modules.v1']").value(repositoryUrl(REPO) + "/v1/modules/"));

        // 2. versions
        MvcResult versions = mvc.perform(get(URL + "/v1/providers/hashicorp/null/versions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.versions", hasSize(1)))
                .andExpect(jsonPath("$.versions[0].version").value("3.2.4"))
                .andExpect(jsonPath("$.versions[0].protocols[0]").value("5.0"))
                .andExpect(jsonPath("$.versions[0].protocols[1]").value("6.0"))
                .andExpect(jsonPath("$.versions[0].platforms[0].os").value("linux"))
                .andExpect(jsonPath("$.versions[0].platforms[0].arch").value("amd64"))
                .andReturn();

        // 3. download document for the platform
        ObjectNode download = object(mvc.perform(get(URL + "/v1/providers/hashicorp/null/3.2.4/download/linux/amd64"))
                .andExpect(status().isOk()).andReturn());
        assertThat(str(download, "filename")).isEqualTo(ZIP);
        assertThat(str(download, "download_url")).isEqualTo(repositoryUrl(REPO) + "/files/hashicorp/null/3.2.4/" + ZIP);
        assertThat(str(download, "shasums_url")).isEqualTo(repositoryUrl(REPO) + "/files/hashicorp/null/3.2.4/SHA256SUMS");
        assertThat(str(download, "shasums_signature_url")).isEqualTo(repositoryUrl(REPO) + "/files/hashicorp/null/3.2.4/SHA256SUMS.sig");
        assertThat(str(download, "shasum")).isEqualTo(sha256(zip));
        assertThat(str(download.path("signing_keys").path("gpg_public_keys").get(0), "key_id")).isEqualTo(HASHICORP_KEY_ID);
        assertThat(str(download.path("signing_keys").path("gpg_public_keys").get(0), "ascii_armor")).contains("BEGIN PGP PUBLIC KEY BLOCK");

        // 4. the three files, with the headers package managers rely on
        MvcResult archive = mvc.perform(get(URL + "/files/hashicorp/null/3.2.4/" + ZIP))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Length", String.valueOf(zip.length)))
                .andExpect(header().string("ETag", "\"" + sha256(zip) + "\""))
                .andExpect(header().string("X-Checksum-Sha256", sha256(zip)))
                .andReturn();
        assertThat(archive.getResponse().getContentAsByteArray()).isEqualTo(zip);
        mvc.perform(head(URL + "/files/hashicorp/null/3.2.4/" + ZIP))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", String.valueOf(zip.length)))
                .andExpect(content().string(""));
        mvc.perform(get(URL + "/files/hashicorp/null/3.2.4/" + ZIP).header("If-None-Match", "\"" + sha256(zip) + "\""))
                .andExpect(status().isNotModified());
        mvc.perform(get(URL + "/files/hashicorp/null/3.2.4/SHA256SUMS"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain"))
                .andExpect(content().string(shaSums(ZIP, zip)));
        assertThat(mvc.perform(get(URL + "/files/hashicorp/null/3.2.4/SHA256SUMS.sig")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(signature());

        // conditional request on a generated document
        String etag = versions.getResponse().getHeader("ETag");
        mvc.perform(get(URL + "/v1/providers/hashicorp/null/versions").header("If-None-Match", etag)).andExpect(status().isNotModified());
        mvc.perform(head(URL + "/v1/providers/hashicorp/null/versions")).andExpect(status().isOk()).andExpect(content().string(""));

        // info documents and the network mirror view of the same data
        mvc.perform(get(URL + "/v1/providers/hashicorp/null")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("3.2.4")).andExpect(jsonPath("$.versions[0]").value("3.2.4"));
        mvc.perform(get(URL + "/v1/providers/hashicorp/null/3.2.4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("hashicorp/null/3.2.4"));
        mvc.perform(get(URL + "/mirror/registry.terraform.io/hashicorp/null/index.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.versions['3.2.4']").exists());
        mvc.perform(get(URL + "/mirror/localhost/hashicorp/null/3.2.4.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.archives.linux_amd64.url").value(repositoryUrl(REPO) + "/files/hashicorp/null/3.2.4/" + ZIP))
                .andExpect(jsonPath("$.archives.linux_amd64.hashes[0]").value("zh:" + sha256(zip)));
        mvc.perform(get(URL + "/v1/providers/hashicorp/null/gpg-key")).andExpect(status().isOk())
                .andExpect(jsonPath("$.gpg_public_keys", hasSize(1)));

        // what the component browser sees
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "hashicorp", "null", "3.2.4")).isPresent();
        assertThat(storage.find(repo, "providers/hashicorp/null/3.2.4/linux/amd64/" + ZIP))
                .hasValueSatisfying(c -> assertThat(c.asset().isMetadata()).isFalse());
        assertThat(storage.find(repo, "providers/hashicorp/null/gpg-key.json"))
                .hasValueSatisfying(c -> assertThat(c.asset().isMetadata()).isTrue());
        assertThat(storage.find(repo, "providers/hashicorp/null/3.2.4/linux/amd64/" + ZIP).orElseThrow().asset().getDownloadCount()).isEqualTo(1);
    }

    @Test
    void republishingABuildIsAConflictUnlessRedeployIsAllowed() throws Exception {
        byte[] first = providerZip("first");
        uploadProvider("hashicorp", first);
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/3.2.4/linux/amd64/" + ZIP).header("Authorization", adminBasic())
                        .content(providerZip("second")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("repository.terraform.versionExists"));
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/3.2.4/SHA256SUMS").header("Authorization", adminBasic())
                        .content(shaSums(ZIP, first)))
                .andExpect(status().isConflict());
        // the same release spelled with a leading "v" is not a way around the rule
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/v3.2.4/linux/amd64/" + ZIP).header("Authorization", adminBasic())
                        .content(providerZip("third")))
                .andExpect(status().isConflict());
        assertThat(storage.list(repo, "providers/hashicorp/null/v")).isEmpty();
        assertThat(mvc.perform(get(URL + "/files/hashicorp/null/3.2.4/" + ZIP)).andReturn().getResponse().getContentAsByteArray()).isEqualTo(first);

        Repository redeploy = new Repository();
        redeploy.setName("terraform-snapshots");
        redeploy.setFormat("terraform");
        redeploy.setType(RepositoryType.HOSTED);
        redeploy.setAllowRedeploy(true);
        repositories.create(redeploy);
        for (String seed : new String[] {"a", "b"}) {
            mvc.perform(put("/repository/terraform-snapshots/v1/providers/hashicorp/null/3.2.4/linux/amd64/" + ZIP)
                            .header("Authorization", adminBasic()).content(providerZip(seed)))
                    .andExpect(status().isCreated());
        }
        assertThat(mvc.perform(get("/repository/terraform-snapshots/files/hashicorp/null/3.2.4/" + ZIP)).andReturn()
                .getResponse().getContentAsByteArray()).isEqualTo(providerZip("b"));
    }

    @Test
    void concurrentUploadsOfOneReleaseLetExactlyOneWin() throws Exception {
        int clients = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(clients);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> archives = new java.util.ArrayList<>();
            List<java.util.concurrent.Future<Integer>> modules = new java.util.ArrayList<>();
            for (int i = 0; i < clients; i++) {
                byte[] zip = providerZip("racer-" + i);
                byte[] tgz = moduleTgz("racer-" + i);
                archives.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(put(URL + "/v1/providers/acme/race/1.0.0/linux/amd64/" + archiveName("race", "1.0.0", "linux", "amd64"))
                            .header("Authorization", adminBasic()).content(zip)).andReturn().getResponse().getStatus();
                }));
                modules.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(put(URL + "/v1/modules/acme/race/local/1.0.0.tgz")
                            .header("Authorization", adminBasic()).content(tgz)).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> archiveCodes = new java.util.ArrayList<>();
            for (var f : archives) {
                archiveCodes.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            List<Integer> moduleCodes = new java.util.ArrayList<>();
            for (var f : modules) {
                moduleCodes.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(archiveCodes).containsOnly(201, 409).filteredOn(c -> c == 201).hasSize(1);
            assertThat(moduleCodes).containsOnly(201, 409).filteredOn(c -> c == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(storage.list(repo, "providers/acme/race/1.0.0/")).hasSize(1);
        assertThat(storage.list(repo, "modules/acme/race/local/")).hasSize(1);
    }

    @Test
    void checksumsAreVerifiedAgainstSha256Sums() throws Exception {
        byte[] zip = providerZip("x");
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/3.2.4/linux/amd64/" + ZIP).header("Authorization", adminBasic()).content(zip))
                .andExpect(status().isCreated());
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/3.2.4/SHA256SUMS").header("Authorization", adminBasic())
                        .content("0".repeat(64) + "  " + ZIP + "\n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.terraform.checksumMismatch"));
        mvc.perform(get(URL + "/files/hashicorp/null/3.2.4/SHA256SUMS")).andExpect(status().isNotFound());
        // without SHA256SUMS the download document still carries the real digest
        mvc.perform(get(URL + "/v1/providers/hashicorp/null/3.2.4/download/linux/amd64")).andExpect(status().isOk())
                .andExpect(jsonPath("$.shasum").value(sha256(zip)))
                .andExpect(jsonPath("$.signing_keys.gpg_public_keys").isEmpty());
    }

    @Test
    void malformedUploadsAreRejectedWithJsonErrors() throws Exception {
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/3.2.4/linux/amd64/" + ZIP).header("Authorization", adminBasic()).content("not a zip"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("repository.terraform.invalidUpload"));
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/3.2.4/linux/amd64/" + ZIP).header("Authorization", adminBasic()))
                .andExpect(status().isBadRequest());
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/3.2.4/SHA256SUMS").header("Authorization", adminBasic()).content("garbage"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/3.2.4/SHA256SUMS.sig").header("Authorization", adminBasic()).content("text"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/gpg-key").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key_id\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/gpg-key").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(URL + "/v1/modules/acme/demo/local/1.0.0.tgz").header("Authorization", adminBasic()).content("not gzip"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/3.2.4/linux/amd64/archive.tar").header("Authorization", adminBasic()).content(providerZip("x")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("path.invalid"));
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/latest/linux/amd64/" + ZIP).header("Authorization", adminBasic()).content(providerZip("x")))
                .andExpect(status().isBadRequest());
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/versions").header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(get(URI.create(URL + "/v1/providers/..%2Fetc/passwd/versions"))).andExpect(status().is4xxClientError());
        mvc.perform(get(URL + "/v1/providers/hashicorp/null/versions")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("content.notFound"));
        mvc.perform(get(URL + "/something/else")).andExpect(status().isNotFound());
        assertThat(storage.list(repo, "")).isEmpty();
    }

    @Test
    void deletingAVersionRemovesItsFilesAndComponent() throws Exception {
        uploadProvider("hashicorp", providerZip("x"));
        mvc.perform(delete(URL + "/v1/providers/hashicorp/null/3.2.4").header("Authorization", adminBasic())).andExpect(status().isNoContent());
        mvc.perform(get(URL + "/v1/providers/hashicorp/null/versions")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/files/hashicorp/null/3.2.4/" + ZIP)).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/v1/providers/hashicorp/null/gpg-key")).andExpect(status().isOk()); // the key outlives versions
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "hashicorp", "null", "3.2.4")).isEmpty();
        mvc.perform(delete(URL + "/v1/providers/hashicorp/null/3.2.4").header("Authorization", adminBasic())).andExpect(status().isNotFound());
        mvc.perform(delete(URL + "/v1/providers/hashicorp/null").header("Authorization", adminBasic())).andExpect(status().isNoContent());
        assertThat(storage.list(repo, "")).isEmpty();
    }

    @Test
    void modulesArePublishedAsTarballsAndResolvedThroughXTerraformGet() throws Exception {
        byte[] tgz = moduleTgz("demo-1.0.0");
        mvc.perform(put(URL + "/v1/modules/acme/demo/local/1.0.0.tgz").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(tgz))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sha256").value(sha256(tgz)));
        mvc.perform(put(URL + "/v1/modules/acme/demo/local/1.1.0.tgz").header("Authorization", adminBasic()).content(moduleTgz("1.1.0")))
                .andExpect(status().isCreated());

        mvc.perform(get(URL + "/v1/modules/acme/demo/local/versions").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules[0].source").value("acme/demo/local"))
                .andExpect(jsonPath("$.modules[0].versions[0].version").value("1.0.0"))
                .andExpect(jsonPath("$.modules[0].versions[1].version").value("1.1.0"));
        mvc.perform(get(URL + "/v1/modules/acme/demo/local/1.0.0/download"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Terraform-Get", repositoryUrl(REPO) + "/files/modules/acme/demo/local/1.0.0.tgz"));
        mvc.perform(get(URL + "/v1/modules/acme/demo/local/download"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Terraform-Get", repositoryUrl(REPO) + "/files/modules/acme/demo/local/1.1.0.tgz"));
        MvcResult file = mvc.perform(get(URL + "/files/modules/acme/demo/local/1.0.0.tgz"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/gzip"))
                .andExpect(header().exists("ETag"))
                .andReturn();
        assertThat(file.getResponse().getContentAsByteArray()).isEqualTo(tgz);
        mvc.perform(get(URL + "/v1/modules/acme/demo/local")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.1.0")).andExpect(jsonPath("$.provider").value("local"));
        mvc.perform(get(URL + "/v1/modules/acme/demo/local/1.0.0")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("acme/demo/local/1.0.0"));
        mvc.perform(get(URL + "/v1/modules/acme/demo/local/9.0.0/download")).andExpect(status().isNotFound());
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "acme", "demo/local", "1.0.0")).isPresent();

        mvc.perform(put(URL + "/v1/modules/acme/demo/local/1.0.0.tgz").header("Authorization", adminBasic()).content(moduleTgz("again")))
                .andExpect(status().isConflict());
        mvc.perform(delete(URL + "/v1/modules/acme/demo/local/1.0.0").header("Authorization", adminBasic())).andExpect(status().isNoContent());
        mvc.perform(get(URL + "/files/modules/acme/demo/local/1.0.0.tgz")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/v1/modules/acme/demo/local/versions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.modules[0].versions", hasSize(1)));
    }

    @Test
    void anonymousWritesAre401ReadersAre403DevelopersMay() throws Exception {
        byte[] zip = providerZip("auth");
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/3.2.4/linux/amd64/" + ZIP).content(zip))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")))
                .andExpect(jsonPath("$.code").value("auth.required"));
        users.create("tf-reader", "readerpass", "Reader", null, Set.of("reader"));
        users.create("tf-dev", "devpassword", "Dev", null, Set.of("developer"));
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/3.2.4/linux/amd64/" + ZIP).header("Authorization", basic("tf-reader", "readerpass")).content(zip))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("auth.forbidden"));
        mvc.perform(put(URL + "/v1/providers/hashicorp/null/3.2.4/linux/amd64/" + ZIP).header("Authorization", basic("tf-dev", "devpassword")).content(zip))
                .andExpect(status().isCreated());
        mvc.perform(delete(URL + "/v1/providers/hashicorp/null/3.2.4").header("Authorization", basic("tf-reader", "readerpass")))
                .andExpect(status().isForbidden());
        mvc.perform(delete(URL + "/v1/providers/hashicorp/null/3.2.4")).andExpect(status().isUnauthorized());

        // an API token works as Bearer (credentials block / TF_TOKEN_) and as the Basic password (curl -u)
        String token = tokens.issue(users.require("tf-dev"), "terraform", null).secret();
        mvc.perform(put(URL + "/v1/modules/acme/demo/local/1.0.0.tgz").header("Authorization", "Bearer " + token).content(moduleTgz("t")))
                .andExpect(status().isCreated());
        mvc.perform(put(URL + "/v1/modules/acme/demo/local/1.1.0.tgz").header("Authorization", basic("tf-dev", token)).content(moduleTgz("t")))
                .andExpect(status().isCreated());
        mvc.perform(put(URL + "/v1/modules/acme/demo/local/1.2.0.tgz").header("Authorization", "Bearer gry_not_a_real_token").content(moduleTgz("t")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readsNeedCredentialsWhenAnonymousAccessIsOff() throws Exception {
        uploadProvider("hashicorp", providerZip("x"));
        settings.setAnonymousReadEnabled(false);
        mvc.perform(get(URL + "/.well-known/terraform.json")).andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(get(URL + "/v1/providers/hashicorp/null/versions")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/files/hashicorp/null/3.2.4/" + ZIP)).andExpect(status().isUnauthorized());
        String token = tokens.issue(users.require(ADMIN), "terraform", null).secret();
        mvc.perform(get(URL + "/.well-known/terraform.json").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get(URL + "/v1/providers/hashicorp/null/versions").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get(URL + "/files/hashicorp/null/3.2.4/" + ZIP).header("Authorization", adminBasic())).andExpect(status().isOk());
    }

    @Test
    void providerProtocolsSettingIsValidatedAndUsed() throws Exception {
        Repository bad = new Repository();
        bad.setName("terraform-bad");
        bad.setFormat("terraform");
        bad.setType(RepositoryType.HOSTED);
        bad.setFormatSettings(java.util.Map.of("terraform.providerProtocols", "six"));
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(dev.granary.core.GranaryException.class, () -> repositories.create(bad)).code())
                .isEqualTo("repository.terraform.providerProtocols.invalid");

        Repository six = new Repository();
        six.setName("terraform-six");
        six.setFormat("terraform");
        six.setType(RepositoryType.HOSTED);
        six.setFormatSettings(java.util.Map.of("terraform.providerProtocols", "6.0"));
        repositories.create(six);
        mvc.perform(put("/repository/terraform-six/v1/providers/acme/demo/1.0.0/linux/amd64/" + archiveName("demo", "1.0.0", "linux", "amd64"))
                        .header("Authorization", adminBasic()).content(providerZip("p")))
                .andExpect(status().isCreated());
        mvc.perform(get("/repository/terraform-six/v1/providers/acme/demo/versions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].protocols", hasSize(1)))
                .andExpect(jsonPath("$.versions[0].protocols[0]").value("6.0"));
        mvc.perform(get("/repository/terraform-six/v1/providers/acme/demo/1.0.0/download/linux/amd64")).andExpect(status().isOk())
                .andExpect(jsonPath("$.protocols[0]").value("6.0"));
    }

    @Test
    void connectSnippetsAreOfferedForEveryClient() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/repositories/" + REPO + "/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"required-providers\"").contains("\"terraformrc-network-mirror\"").contains("\"terraformrc-credentials\"")
                .contains("\"tf-token-env\"").contains("\"tofurc\"").contains("\"publish-provider-curl\"").contains("\"publish-module-curl\"")
                .contains("\"providers-mirror-seed\"").contains("/repository/terraform-hosted/mirror/");
    }
}
