package dev.granary.server;

import dev.granary.core.GranaryException;
import dev.granary.core.dao.RepositoryDao;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.repository.RepositoryService;
import dev.granary.core.security.ApiTokenService;
import dev.granary.core.security.UserService;
import dev.granary.core.settings.SettingsService;
import dev.granary.server.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for end-to-end tests that drive the real HTTP surface with MockMvc against an in-memory
 * database and a temporary blob store. Each test starts with no repositories and the default settings.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    public static final String ADMIN = "admin";
    public static final String ADMIN_PASSWORD = "admin123";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected RepositoryService repositories;

    @Autowired
    protected RepositoryDao repositoryDao;

    @Autowired
    protected UserService users;

    @Autowired
    protected ApiTokenService tokens;

    @Autowired
    protected SettingsService settings;

    @Autowired
    protected JwtService jwt;

    @BeforeEach
    void resetState() {
        // groups may nest, so delete whatever is not a member of something else until nothing is left
        List<Repository> remaining = repositoryDao.findAll();
        while (!remaining.isEmpty()) {
            int before = remaining.size();
            for (Repository r : remaining) {
                try {
                    repositories.delete(r.getName());
                } catch (GranaryException inUse) {
                    // still referenced by a group; retried in the next round
                }
            }
            remaining = repositoryDao.findAll();
            if (remaining.size() == before) {
                throw new IllegalStateException("could not clean repositories: " + remaining);
            }
        }
        settings.setAnonymousReadEnabled(true);
        settings.setGlobalOffline(false);
        settings.setBaseUrl("");
    }

    /** {@code Authorization: Basic} value for a username/password or username/token pair. */
    public static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    public String adminBasic() {
        return basic(ADMIN, ADMIN_PASSWORD);
    }

    /** {@code Authorization: Bearer} value of a web session for the given user. */
    public String bearer(String username) {
        return "Bearer " + jwt.issue(username).token();
    }

    public String adminBearer() {
        return bearer(ADMIN);
    }

    public Repository hosted(String name, String format) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat(format);
        r.setType(RepositoryType.HOSTED);
        r.setAllowRedeploy(false);
        return repositories.create(r);
    }

    public Repository proxy(String name, String format, String remoteUrl) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat(format);
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(remoteUrl);
        return repositories.create(r);
    }

    public Repository group(String name, String format, String... members) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat(format);
        r.setType(RepositoryType.GROUP);
        r.setMembers(List.of(members));
        return repositories.create(r);
    }
}
