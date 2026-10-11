package dev.granary.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.blob.BlobStoreRegistry;
import dev.granary.core.blob.FileBlobStore;
import dev.granary.core.dao.AssetDao;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.security.RoleService;
import dev.granary.server.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@code POST /api/v1/blob-stores/{name}/compact}: who may call it, what it answers, and that it reclaims space. */
class BlobCompactionApiIntegrationTest extends IntegrationTest {

    private static final String JAR = "com/acme/gone/1.0/gone-1.0.jar";

    @Autowired
    AssetDao assets;

    @Autowired
    BlobStoreRegistry blobStores;

    @Autowired
    RoleService roles;

    @Test
    void adminGetsTheReport() throws Exception {
        mvc.perform(post("/api/v1/blob-stores/default/compact").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blobStore").value("default"))
                .andExpect(jsonPath("$.blobsScanned").isNumber())
                .andExpect(jsonPath("$.blobsDeleted").isNumber())
                .andExpect(jsonPath("$.bytesReclaimed").isNumber())
                .andExpect(jsonPath("$.durationMillis").isNumber())
                .andExpect(jsonPath("$.startedAt").isString())
                .andExpect(jsonPath("$.gracePeriod").value("PT24H"));
        // an API token of the admin works as well as a web session
        String token = tokens.issue(users.find(ADMIN).orElseThrow(), "compaction", null).secret();
        mvc.perform(post("/api/v1/blob-stores/default/compact").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void usersWithoutAdministrativePrivilegesAreRefused() throws Exception {
        users.create("dev", "password1", null, null, Set.of(RoleService.DEVELOPER));
        if (roles.find("repo-managers").isEmpty()) {
            roles.create("repo-managers", null, Set.of("repositories:manage"));
        }
        users.create("manager", "password1", null, null, Set.of("repo-managers"));

        mvc.perform(post("/api/v1/blob-stores/default/compact").header("Authorization", bearer("dev")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/blob-stores/default/compact").header("Authorization", basic("manager", "password1")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/blob-stores/default/compact"))
                .andExpect(status().isUnauthorized());
        // a repository manager may see the store statistics, just not compact
        mvc.perform(get("/api/v1/system/status").header("Authorization", bearer("manager"))).andExpect(status().isOk());
    }

    @Test
    void unknownStoreIsNotFound() throws Exception {
        mvc.perform(post("/api/v1/blob-stores/nope/compact").header("Authorization", adminBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("blobStore.notFound"));
    }

    @Test
    void deletingARepositoryThenCompactingReclaimsItsBlobs() throws Exception {
        Repository repo = hosted("doomed", "maven2");
        byte[] jar = "a jar nobody will ever need again".getBytes();
        mvc.perform(put("/repository/doomed/" + JAR).header("Authorization", adminBasic()).content(jar))
                .andExpect(status().isCreated());
        mvc.perform(put("/repository/doomed/com/acme/gone/1.0/gone-1.0.pom").header("Authorization", adminBasic())
                        .content("<project/>"))
                .andExpect(status().isCreated());
        List<Asset> stored = assets.findByRepositoryIdAndPathStartingWithOrderByPathAsc(repo.getId(), "");
        assertThat(stored).isNotEmpty();
        for (Asset asset : stored) {
            age(asset.getBlobSha256(), Duration.ofDays(2));
        }
        FileBlobStore store = (FileBlobStore) blobStores.defaultStore();

        // still referenced: nothing of this repository goes
        mvc.perform(post("/api/v1/blob-stores/default/compact").header("Authorization", adminBearer()))
                .andExpect(status().isOk());
        assertThat(stored).allSatisfy(a -> assertThat(store.exists(a.getBlobSha256())).isTrue());

        mvc.perform(delete("/api/v1/repositories/doomed").header("Authorization", adminBearer()))
                .andExpect(status().is2xxSuccessful());
        Map<String, Long> blobs = stored.stream()
                .collect(Collectors.toMap(Asset::getBlobSha256, Asset::getSize, (x, y) -> x));
        long expectedBytes = blobs.values().stream().mapToLong(Long::longValue).sum();
        // other tests' blobs in this store are all younger than the grace period, so the numbers are exact
        mvc.perform(post("/api/v1/blob-stores/default/compact").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blobsDeleted").value(blobs.size()))
                .andExpect(jsonPath("$.bytesReclaimed").value(expectedBytes));
        assertThat(stored).allSatisfy(a -> assertThat(store.exists(a.getBlobSha256())).isFalse());
    }

    private void age(String sha, Duration by) throws Exception {
        Path root = ((FileBlobStore) blobStores.defaultStore()).root();
        Path file = root.resolve("content").resolve(sha.substring(0, 2)).resolve(sha.substring(2, 4)).resolve(sha);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(by)));
    }
}
