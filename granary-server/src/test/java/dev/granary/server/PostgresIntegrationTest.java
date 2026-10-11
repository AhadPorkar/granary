package dev.granary.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.repository.RepositoryService;
import dev.granary.core.security.UserService;
import dev.granary.core.storage.AssetSanitiser;
import dev.granary.core.storage.InvalidStorageInputException;
import dev.granary.core.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The production database: migrations, bootstrap and a Maven round trip on a real PostgreSQL.
 * Skipped automatically when Docker is not available on the machine running the build.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "postgres"})
class PostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("granary.test.postgresImage", "postgres:17-alpine"));

    @Autowired
    MockMvc mvc;

    @Autowired
    RepositoryService repositories;

    @Autowired
    UserService users;

    @Autowired
    StorageService storage;

    @Test
    void migratesBootstrapsAndServesOnPostgres() throws Exception {
        assertThat(users.find("admin")).isPresent();

        String admin = "Basic " + Base64.getEncoder().encodeToString("admin:admin123".getBytes());
        mvc.perform(post("/api/v1/repositories").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pg-releases\",\"format\":\"maven2\",\"type\":\"HOSTED\"}"))
                .andExpect(status().isCreated());
        assertThat(repositories.require("pg-releases").getType()).isEqualTo(RepositoryType.HOSTED);

        mvc.perform(put("/repository/pg-releases/com/acme/pg/1.0/pg-1.0.jar").header("Authorization", admin).content("pg"))
                .andExpect(status().isCreated());
        mvc.perform(get("/repository/pg-releases/com/acme/pg/1.0/pg-1.0.jar"))
                .andExpect(status().isOk()).andExpect(content().string("pg"));
        mvc.perform(get("/repository/pg-releases/com/acme/pg/maven-metadata.xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<version>1.0</version>")));
        mvc.perform(get("/api/v1/repositories/pg-releases").header("Authorization", admin))
                .andExpect(jsonPath("$.statistics.assets").value(1));
    }

    /**
     * A PostgreSQL B-tree entry holds at most 2704 bytes, so 2,000 characters that fit the column do not fit the
     * unique index once most of them take three bytes. Up to the storage layer's byte limit a path of incompressible
     * multi-byte characters is stored and served; one byte more is a 400, never the database error as a 500.
     */
    @Test
    void multiBytePathsAndCoordinatesUpToTheIndexLimitAreStoredOnPostgres() throws Exception {
        String admin = "Basic " + Base64.getEncoder().encodeToString("admin:admin123".getBytes());
        Repository repo = new Repository();
        repo.setName("pg-raw");
        repo.setFormat("raw");
        repo.setType(RepositoryType.HOSTED);
        repositories.create(repo);
        Random random = new Random(42);
        String path = "ab" + randomCjk(random, (AssetSanitiser.MAX_INDEXED_BYTES - 2) / 3);
        assertThat(path.getBytes(StandardCharsets.UTF_8)).hasSize(AssetSanitiser.MAX_INDEXED_BYTES);
        URI url = URI.create("/repository/pg-raw/" + UriUtils.encodePath(path, StandardCharsets.UTF_8));
        URI longer = URI.create(url + "x");

        mvc.perform(put(url).header("Authorization", admin).content("wide")).andExpect(status().isCreated());
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().string("wide"));
        mvc.perform(put(longer).header("Authorization", admin).content("wider"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("path.invalid"));
        // 1,000 characters fit the column; their 3,000 bytes used to fail the index insert with a 500
        URI tooWide = URI.create("/repository/pg-raw/" + UriUtils.encodePath(randomCjk(random, 1000), StandardCharsets.UTF_8));
        mvc.perform(put(tooWide).header("Authorization", admin).content("too wide"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("path.invalid"));

        var coordinates = new StorageService.ComponentCoordinates(randomCjk(random, 400), randomCjk(random, 400),
                randomCjk(random, 66) + "ab");
        assertThat(storage.ensureComponent(repositories.require("pg-raw"), coordinates).getId()).isNotNull();
    }

    /** PostgreSQL refuses a zero character in any string, which used to surface as a misleading 409 conflict. */
    @Test
    void nulCharactersAreRefusedAsInvalidInputOnPostgres() {
        Repository repo = new Repository();
        repo.setName("pg-nul");
        repo.setFormat("raw");
        repo.setType(RepositoryType.HOSTED);
        Repository created = repositories.create(repo);

        assertThatThrownBy(() -> storage.find(created, "a/b\u0000.txt")).isInstanceOf(InvalidStorageInputException.class);
        assertThatThrownBy(() -> storage.store(created, "a/b\u0000.txt", new ByteArrayInputStream(new byte[] {1}),
                null, Asset.Origin.UPLOADED, false)).isInstanceOf(InvalidStorageInputException.class);
        assertThatThrownBy(() -> storage.ensureComponent(created, new StorageService.ComponentCoordinates("", "n\u0000", "1")))
                .isInstanceOf(InvalidStorageInputException.class);
    }

    private static String randomCjk(Random random, int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append((char) (0x4E00 + random.nextInt(0x5000)));
        }
        return out.toString();
    }
}
