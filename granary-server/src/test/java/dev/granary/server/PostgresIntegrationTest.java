package dev.granary.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.model.RepositoryType;
import dev.granary.core.repository.RepositoryService;
import dev.granary.core.security.UserService;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
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
}
