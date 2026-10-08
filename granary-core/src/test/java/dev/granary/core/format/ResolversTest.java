package dev.granary.core.format;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.CoreServiceTest;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ResolversTest extends CoreServiceTest {

    @Autowired
    GroupResolver groups;

    @Autowired
    ContentResolver resolver;

    @Autowired
    StorageService storage;

    @Autowired
    FormatRegistry formats;

    @Test
    void flattensNestedGroupsInOrderSkippingOfflineMembers() {
        hosted("a");
        hosted("b");
        Repository c = hosted("c");
        group("inner", "b", "c");
        Repository outer = group("outer", "a", "inner");
        c.setOnline(false);
        repositories.update("c", c);

        assertThat(groups.leafMembers(repositories.require("outer")))
                .extracting(Repository::getName).containsExactly("a", "b");
        assertThat(groups.leafMembers(repositories.require("a"))).extracting(Repository::getName).containsExactly("a");
        assertThat(outer.isGroup()).isTrue();
    }

    @Test
    void groupReturnsTheFirstMemberThatHasThePath() throws IOException {
        Repository a = hosted("a");
        Repository b = hosted("b");
        group("g", "a", "b");
        storage.store(b, "only-b", bytes("B"), null, Asset.Origin.UPLOADED, false);
        storage.store(a, "both", bytes("A"), null, Asset.Origin.UPLOADED, false);
        storage.store(b, "both", bytes("B"), null, Asset.Origin.UPLOADED, false);

        Repository g = repositories.require("g");
        assertThat(resolver.resolve(g, "only-b").orElseThrow().readUtf8()).isEqualTo("B");
        assertThat(resolver.resolve(g, "both").orElseThrow().readUtf8()).isEqualTo("A");
        assertThat(resolver.resolve(g, "none")).isEmpty();
    }

    @Test
    void offlineRepositoriesResolveNothing() {
        Repository a = hosted("a");
        storage.store(a, "f", bytes("x"), null, Asset.Origin.UPLOADED, false);
        a.setOnline(false);
        a = repositories.update("a", a);
        assertThat(resolver.resolve(a, "f")).isEmpty();
    }

    @Test
    void registryKnowsTheFakeFormat() {
        assertThat(formats.names()).contains("fake");
        assertThat(formats.find("fake")).isPresent();
        assertThat(formats.find("nope")).isEmpty();
        assertThat(formats.require("fake").displayName()).isEqualTo("Fake");
        assertThat(formats.require("fake").isMetadataPath("x/index.json")).isTrue();
        assertThat(formats.require("fake").supportedTypes()).hasSize(3);
    }

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
