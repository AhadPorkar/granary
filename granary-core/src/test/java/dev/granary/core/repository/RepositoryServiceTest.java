package dev.granary.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.CoreServiceTest;
import dev.granary.core.GranaryException;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepositoryServiceTest extends CoreServiceTest {

    @Test
    void createsAndListsRepositoriesSortedByName() {
        hosted("zeta");
        hosted("alpha");

        assertThat(repositories.list()).extracting(Repository::getName).containsExactly("alpha", "zeta");
        assertThat(repositories.require("alpha").getBlobStore()).isEqualTo("default");
        assertThat(repositories.require("alpha").getCreatedAt()).isNotNull();
    }

    @Test
    void rejectsInvalidNames() {
        for (String bad : List.of("", "-x", "a b", "a/b", "a".repeat(101), "ünïcode")) {
            Repository r = new Repository();
            r.setName(bad);
            r.setFormat("fake");
            r.setType(RepositoryType.HOSTED);
            assertThatThrownBy(() -> repositories.create(r))
                    .isInstanceOf(GranaryException.class)
                    .extracting("code").isEqualTo("repository.name.invalid");
        }
    }

    @Test
    void rejectsDuplicateNamesUnknownFormatsAndBlobStores() {
        hosted("dup");
        assertThatThrownBy(() -> hosted("dup")).extracting("code").isEqualTo("repository.name.taken");

        Repository r = new Repository();
        r.setName("x");
        r.setFormat("nope");
        r.setType(RepositoryType.HOSTED);
        assertThatThrownBy(() -> repositories.create(r)).extracting("code").isEqualTo("repository.format.unknown");

        r.setFormat("fake");
        r.setBlobStore("missing");
        assertThatThrownBy(() -> repositories.create(r)).extracting("code").isEqualTo("repository.blobstore.unknown");
    }

    @Test
    void proxyNeedsAValidRemoteUrl() {
        assertThatThrownBy(() -> proxy("p", null)).extracting("code").isEqualTo("repository.remoteUrl.missing");
        assertThatThrownBy(() -> proxy("p", "ftp://x")).extracting("code").isEqualTo("repository.remoteUrl.invalid");
        assertThatThrownBy(() -> proxy("p", "not a url")).extracting("code").isEqualTo("repository.remoteUrl.invalid");
        assertThat(proxy("p", "https://repo1.maven.org/maven2/").getRemoteUrl()).isEqualTo("https://repo1.maven.org/maven2/");
    }

    @Test
    void proxyTimeoutsMustBePositive() {
        Repository r = new Repository();
        r.setName("p");
        r.setFormat("fake");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl("https://example.org/");
        r.setConnectTimeoutSeconds(0);
        assertThatThrownBy(() -> repositories.create(r)).extracting("code").isEqualTo("repository.field.positive");
        r.setConnectTimeoutSeconds(5);
        r.setNegativeCacheMinutes(-1);
        assertThatThrownBy(() -> repositories.create(r)).extracting("code").isEqualTo("repository.field.nonNegative");
    }

    @Test
    void groupValidation() {
        hosted("a");
        proxy("b", "https://example.org/");
        assertThatThrownBy(() -> group("g")).extracting("code").isEqualTo("repository.members.empty");
        assertThatThrownBy(() -> group("g", "a", "a")).extracting("code").isEqualTo("repository.members.duplicate");
        assertThatThrownBy(() -> group("g", "g")).extracting("code").isEqualTo("repository.members.self");
        assertThatThrownBy(() -> group("g", "missing")).extracting("code").isEqualTo("repository.members.unknown");

        Repository other = new Repository();
        other.setName("other-format");
        other.setFormat("fake");
        other.setType(RepositoryType.HOSTED);
        repositories.create(other);
        other.setFormat("different");
        repositoryDao.save(other); // bypass validation to simulate a repository of another format
        assertThatThrownBy(() -> group("g", "other-format")).extracting("code").isEqualTo("repository.members.formatMismatch");

        assertThat(group("g", "a", "b").getMembers()).containsExactly("a", "b");
    }

    @Test
    void detectsCyclesThroughNestedGroups() {
        hosted("a");
        group("g1", "a");
        group("g2", "g1");
        Repository g1 = repositories.require("g1");
        g1.setMembers(List.of("a", "g2"));
        assertThatThrownBy(() -> repositories.update("g1", g1)).extracting("code").isEqualTo("repository.members.cycle");
    }

    @Test
    void membersCannotBeDeletedWhileInUse() {
        hosted("a");
        group("g", "a");
        assertThatThrownBy(() -> repositories.delete("a")).extracting("code").isEqualTo("repository.inUse");
        repositories.delete("g");
        repositories.delete("a");
        assertThat(repositories.find("a")).isEmpty();
    }

    @Test
    void updateKeepsIdentityAndSecrets() {
        Repository p = proxy("p", "https://example.org/");
        p.setRemoteUsername("u");
        p.setRemotePassword("secret");
        repositories.update("p", p);

        Repository changes = new Repository();
        changes.setRemoteUrl("https://example.org/v2/");
        changes.setRemoteUsername("u");
        changes.setRemotePassword("");        // blank = keep
        changes.setDescription("desc");
        changes.setOfflineMode(true);
        changes.setOnline(true);
        Repository updated = repositories.update("p", changes);

        assertThat(updated.getRemotePassword()).isEqualTo("secret");
        assertThat(updated.getRemoteUrl()).isEqualTo("https://example.org/v2/");
        assertThat(updated.getDescription()).isEqualTo("desc");
        assertThat(updated.isOfflineMode()).isTrue();
        assertThat(updated.getId()).isEqualTo(p.getId());

        changes.setType(RepositoryType.HOSTED);
        assertThatThrownBy(() -> repositories.update("p", changes)).extracting("code").isEqualTo("repository.type.immutable");
        changes.setType(RepositoryType.PROXY);
        changes.setFormat("other");
        assertThatThrownBy(() -> repositories.update("p", changes)).extracting("code").isEqualTo("repository.format.immutable");
    }

    @Test
    void clearingTheUsernameDropsThePassword() {
        Repository p = proxy("p", "https://example.org/");
        p.setRemoteUsername("u");
        p.setRemotePassword("secret");
        repositories.update("p", p);
        Repository changes = new Repository();
        changes.setRemoteUrl("https://example.org/");
        changes.setOnline(true);
        assertThat(repositories.update("p", changes).getRemotePassword()).isNull();
    }

    @Test
    void offlineSwitchOnlyAppliesToProxies() {
        hosted("h");
        proxy("p", "https://example.org/");
        assertThatThrownBy(() -> repositories.setOffline("h", true)).extracting("code").isEqualTo("repository.notProxy");
        assertThat(repositories.setOffline("p", true).isOfflineMode()).isTrue();
        assertThat(repositories.setOffline("p", false).isOfflineMode()).isFalse();
    }

    @Test
    void requireThrowsNotFound() {
        assertThatThrownBy(() -> repositories.require("ghost"))
                .isInstanceOf(GranaryException.class)
                .satisfies(e -> assertThat(((GranaryException) e).status()).isEqualTo(404));
    }
}
