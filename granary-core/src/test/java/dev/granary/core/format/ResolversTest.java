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

    @Autowired
    dev.granary.core.dao.ComponentDao components;

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
    void groupLookupLinksWhatItsProxyMemberCachesAndLeavesHostedContentAlone() throws IOException {
        var upstream = new com.github.tomakehurst.wiremock.WireMockServer(
                com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
        upstream.start();
        try {
            upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get("/pkg/1.0/pkg-1.0.tgz")
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200).withBody("P")));
            Repository h = hosted("h");
            Repository p = proxy("p", upstream.baseUrl() + "/");
            group("g", "h", "p");
            storage.store(h, "own/1.0/own-1.0.tgz", bytes("H"), null, Asset.Origin.UPLOADED, false);
            var coordinates = new StorageService.ComponentCoordinates("", "pkg", "1.0");

            var fromProxy = resolver.resolve(repositories.require("g"), "pkg/1.0/pkg-1.0.tgz",
                    dev.granary.core.proxy.FetchOptions.artifact(coordinates)).orElseThrow();
            var fromHosted = resolver.resolve(repositories.require("g"), "own/1.0/own-1.0.tgz",
                    dev.granary.core.proxy.FetchOptions.artifact(new StorageService.ComponentCoordinates("", "own", "1.0")))
                    .orElseThrow();

            assertThat(fromProxy.readUtf8()).isEqualTo("P");
            assertThat(fromProxy.asset().getRepositoryId()).isEqualTo(p.getId());
            assertThat(fromProxy.asset().getComponentId()).isNotNull();
            assertThat(components.countByRepositoryId(p.getId())).isEqualTo(1);
            // hosted content is linked when it is uploaded, never by a read
            assertThat(fromHosted.asset().getComponentId()).isNull();
            assertThat(components.countByRepositoryId(h.getId())).isZero();
            assertThat(components.countByRepositoryId(repositories.require("g").getId())).isZero();
        } finally {
            upstream.stop();
        }
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
