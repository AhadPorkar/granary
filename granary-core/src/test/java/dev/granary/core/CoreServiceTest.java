package dev.granary.core;

import dev.granary.CoreTestApplication;
import dev.granary.core.GranaryException;
import dev.granary.core.dao.RepositoryDao;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.repository.RepositoryService;
import dev.granary.core.settings.SettingsService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Base class for tests that need the real services on an in-memory database. */
@SpringBootTest(classes = CoreTestApplication.class)
public abstract class CoreServiceTest {

    @Autowired
    protected RepositoryService repositories;

    @Autowired
    protected RepositoryDao repositoryDao;

    @Autowired
    protected SettingsService settings;

    @BeforeEach
    void cleanRepositories() {
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
        settings.setGlobalOffline(false);
        settings.setAnonymousReadEnabled(true);
    }

    protected Repository hosted(String name) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat(CoreTestApplication.FAKE_FORMAT);
        r.setType(RepositoryType.HOSTED);
        return repositories.create(r);
    }

    protected Repository proxy(String name, String remoteUrl) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat(CoreTestApplication.FAKE_FORMAT);
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(remoteUrl);
        return repositories.create(r);
    }

    protected Repository group(String name, String... members) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat(CoreTestApplication.FAKE_FORMAT);
        r.setType(RepositoryType.GROUP);
        r.setMembers(List.of(members));
        return repositories.create(r);
    }
}
