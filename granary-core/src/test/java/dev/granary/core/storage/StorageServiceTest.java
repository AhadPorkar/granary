package dev.granary.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.CoreServiceTest;
import dev.granary.core.dao.AssetDao;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StorageServiceTest extends CoreServiceTest {

    @Autowired
    StorageService storage;

    @Autowired
    AssetDao assets;

    @Autowired
    ComponentDao components;

    @Test
    void storesAndReadsBackContent() throws IOException {
        Repository repo = hosted("h");

        StoredContent stored = storage.store(repo, "/a/b/c.txt", in("hello"), null, Asset.Origin.UPLOADED, false);

        assertThat(stored.asset().getPath()).isEqualTo("a/b/c.txt");
        assertThat(stored.asset().getContentType()).isEqualTo("text/plain");
        assertThat(stored.size()).isEqualTo(5);
        assertThat(stored.asset().getOrigin()).isEqualTo(Asset.Origin.UPLOADED);
        assertThat(storage.find(repo, "a/b/c.txt").orElseThrow().readUtf8()).isEqualTo("hello");
        assertThat(storage.exists(repo, "/a/b/c.txt")).isTrue();
        assertThat(storage.find(repo, "nope")).isEmpty();
    }

    @Test
    void replacingAPathKeepsOneAssetRow() {
        Repository repo = hosted("h");
        storage.store(repo, "f", in("v1"), null, Asset.Origin.UPLOADED, false);
        StoredContent second = storage.store(repo, "f", in("v2"), "text/x-custom", Asset.Origin.CACHED, true);

        assertThat(assets.countByRepositoryId(repo.getId())).isEqualTo(1);
        assertThat(second.asset().getContentType()).isEqualTo("text/x-custom");
        assertThat(second.asset().isMetadata()).isTrue();
        assertThat(second.asset().getOrigin()).isEqualTo(Asset.Origin.CACHED);
    }

    @Test
    void samePathInDifferentRepositoriesAreIndependent() throws IOException {
        Repository a = hosted("a");
        Repository b = hosted("b");
        storage.store(a, "f", in("from a"), null, Asset.Origin.UPLOADED, false);
        storage.store(b, "f", in("from b"), null, Asset.Origin.UPLOADED, false);

        assertThat(storage.find(a, "f").orElseThrow().readUtf8()).isEqualTo("from a");
        assertThat(storage.find(b, "f").orElseThrow().readUtf8()).isEqualTo("from b");
    }

    @Test
    void identicalBytesShareOneBlob() {
        Repository repo = hosted("h");
        StoredContent x = storage.store(repo, "x", in("same"), null, Asset.Origin.UPLOADED, false);
        StoredContent y = storage.store(repo, "y", in("same"), null, Asset.Origin.UPLOADED, false);
        assertThat(x.asset().getBlobSha256()).isEqualTo(y.asset().getBlobSha256());
        assertThat(assets.countByBlobSha256(x.asset().getBlobSha256())).isEqualTo(2);
    }

    @Test
    void componentsAreCreatedOnDemandAndRemovedWithTheirLastAsset() {
        Repository repo = hosted("h");
        var coords = new StorageService.ComponentCoordinates("org.example", "lib", "1.0");
        StoredContent jar = storage.store(repo, "org/example/lib/1.0/lib-1.0.jar", in("jar"), null, Asset.Origin.UPLOADED, false, coords);
        StoredContent pom = storage.store(repo, "org/example/lib/1.0/lib-1.0.pom", in("pom"), null, Asset.Origin.UPLOADED, false, coords);

        assertThat(jar.asset().getComponentId()).isNotNull().isEqualTo(pom.asset().getComponentId());
        assertThat(components.countByRepositoryId(repo.getId())).isEqualTo(1);
        Component c = components.findById(jar.asset().getComponentId()).orElseThrow();
        assertThat(c.getNamespace()).isEqualTo("org.example");
        assertThat(c.getName()).isEqualTo("lib");
        assertThat(c.getVersion()).isEqualTo("1.0");
        assertThat(c.getFormat()).isEqualTo(repo.getFormat());

        assertThat(storage.delete(repo, "org/example/lib/1.0/lib-1.0.jar")).isTrue();
        assertThat(components.countByRepositoryId(repo.getId())).isEqualTo(1);
        assertThat(storage.delete(repo, "org/example/lib/1.0/lib-1.0.pom")).isTrue();
        assertThat(components.countByRepositoryId(repo.getId())).isZero();
        assertThat(storage.delete(repo, "org/example/lib/1.0/lib-1.0.pom")).isFalse();
    }

    @Test
    void deleteComponentRemovesAllItsAssets() {
        Repository repo = hosted("h");
        var coords = new StorageService.ComponentCoordinates("", "pkg", "2.0");
        StoredContent a = storage.store(repo, "pkg/2.0/a", in("a"), null, Asset.Origin.UPLOADED, false, coords);
        storage.store(repo, "pkg/2.0/b", in("b"), null, Asset.Origin.UPLOADED, false, coords);
        storage.store(repo, "other", in("o"), null, Asset.Origin.UPLOADED, false);

        assertThat(storage.deleteComponent(a.asset().getComponentId())).isEqualTo(2);
        assertThat(assets.countByRepositoryId(repo.getId())).isEqualTo(1);
        assertThat(components.countByRepositoryId(repo.getId())).isZero();
    }

    @Test
    void listsByPrefixInOrder() {
        Repository repo = hosted("h");
        storage.store(repo, "a/2", in("x"), null, Asset.Origin.UPLOADED, false);
        storage.store(repo, "a/1", in("x"), null, Asset.Origin.UPLOADED, false);
        storage.store(repo, "b/1", in("x"), null, Asset.Origin.UPLOADED, false);

        List<Asset> list = storage.list(repo, "a/");
        assertThat(list).extracting(Asset::getPath).containsExactly("a/1", "a/2");
        assertThat(storage.list(repo, "")).hasSize(3);
    }

    @Test
    void recordsDownloads() {
        Repository repo = hosted("h");
        StoredContent c = storage.store(repo, "f", in("x"), null, Asset.Origin.UPLOADED, false);
        storage.recordDownload(c.asset());
        storage.recordDownload(c.asset());
        Asset reloaded = assets.findById(c.asset().getId()).orElseThrow();
        assertThat(reloaded.getDownloadCount()).isEqualTo(2);
        assertThat(reloaded.getLastDownloadedAt()).isNotNull();
    }

    @Test
    void deletingARepositoryDropsItsAssetsAndComponents() {
        Repository repo = hosted("h");
        storage.store(repo, "f", in("x"), null, Asset.Origin.UPLOADED, false,
                new StorageService.ComponentCoordinates("", "n", "1"));
        repositories.delete("h");
        assertThat(assets.countByRepositoryId(repo.getId())).isZero();
        assertThat(components.countByRepositoryId(repo.getId())).isZero();
    }

    @Test
    void normalisationRejectsTraversal() {
        assertThat(StorageService.normalise("///a/b")).isEqualTo("a/b");
        assertThat(StorageService.normalise(null)).isEmpty();
        assertThatThrownBy(() -> StorageService.normalise("a/../b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageService.normalise("../b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageService.normalise("a\\b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageService.normalise("..")).isInstanceOf(IllegalArgumentException.class);
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
