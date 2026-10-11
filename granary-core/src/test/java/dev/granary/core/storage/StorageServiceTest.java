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

    @Test
    void normalisationRejectsPathsLongerThanTheColumn() {
        String max = "a/" + "b".repeat(AssetSanitiser.MAX_PATH_LENGTH - 2);
        assertThat(StorageService.normalise("/" + max)).isEqualTo(max);
        assertThatThrownBy(() -> StorageService.normalise(max + "c"))
                .isInstanceOf(InvalidStorageInputException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(((InvalidStorageInputException) e).code()).isEqualTo("path.invalid"))
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(300));
        assertThatThrownBy(() -> StorageService.normalise("a/../b")).isInstanceOf(InvalidStorageInputException.class);
    }

    /**
     * 2,000 characters fit the column, but a PostgreSQL index entry holds at most 2704 bytes, so a path of mostly
     * multi-byte characters is limited by its UTF-8 length as well.
     */
    @Test
    void normalisationRejectsMultiBytePathsTooLongForTheIndex() {
        String max = "ab" + "中".repeat((AssetSanitiser.MAX_INDEXED_BYTES - 2) / 3);
        assertThat(max.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(AssetSanitiser.MAX_INDEXED_BYTES);
        assertThat(StorageService.normalise(max)).isEqualTo(max);
        assertThatThrownBy(() -> StorageService.normalise(max + "x"))
                .isInstanceOf(InvalidStorageInputException.class)
                .satisfies(e -> assertThat(((InvalidStorageInputException) e).code()).isEqualTo("path.invalid"))
                .hasMessageContaining("bytes in UTF-8");
        // four-byte characters count as two chars and four bytes
        String emoji = "\uD83D\uDE00".repeat(AssetSanitiser.MAX_INDEXED_BYTES / 4);
        assertThat(StorageService.normalise(emoji)).isEqualTo(emoji);
        assertThatThrownBy(() -> StorageService.normalise(emoji + "é")).isInstanceOf(InvalidStorageInputException.class);
        // ASCII paths are limited by the column alone
        String ascii = "a".repeat(AssetSanitiser.MAX_PATH_LENGTH);
        assertThat(StorageService.normalise(ascii)).isEqualTo(ascii);
    }

    @Test
    void pathOfExactlyTheColumnSizeIsStoredAndOneMoreIsRefused() throws IOException {
        Repository repo = hosted("h");
        String max = "d/" + "p".repeat(AssetSanitiser.MAX_PATH_LENGTH - 2);

        storage.store(repo, max, in("fits"), null, Asset.Origin.UPLOADED, false);

        assertThat(storage.find(repo, max).orElseThrow().readUtf8()).isEqualTo("fits");
        assertThatThrownBy(() -> storage.store(repo, max + "x", in("too long"), null, Asset.Origin.UPLOADED, false))
                .isInstanceOf(InvalidStorageInputException.class);
        assertThat(assets.countByRepositoryId(repo.getId())).isEqualTo(1);
    }

    @Test
    void oversizedContentTypeIsReplacedByTheGuessInsteadOfFailingTheWrite() {
        Repository repo = hosted("h");
        String huge = "application/" + "z".repeat(400);

        StoredContent stored = storage.store(repo, "lib/x-1.0.jar", in("jar"), huge, Asset.Origin.CACHED, false);

        assertThat(stored.asset().getContentType()).isEqualTo("application/java-archive");
        assertThat(assets.findById(stored.asset().getId()).orElseThrow().getContentType()).isEqualTo("application/java-archive");
    }

    @Test
    void contentTypeWithOversizedParametersKeepsItsType() {
        Repository repo = hosted("h");
        String value = "text/plain; note=\"" + "n".repeat(400) + "\"";

        StoredContent stored = storage.store(repo, "notes", in("n"), value, Asset.Origin.UPLOADED, false);

        assertThat(stored.asset().getContentType()).isEqualTo("text/plain");
    }

    @Test
    void oversizedValidatorsAreDroppedAndShortOnesReplaceThem() {
        Repository repo = hosted("h");
        StoredContent stored = storage.store(repo, "f.json", in("{}"), null, Asset.Origin.CACHED, true);

        Asset withValidators = storage.touchVerified(stored.asset(), "\"v1\"", "Wed, 01 Jan 2026 00:00:00 GMT");
        assertThat(withValidators.getUpstreamEtag()).isEqualTo("\"v1\"");

        Asset oversized = storage.touchVerified(withValidators, "\"" + "e".repeat(600) + "\"", "L".repeat(150));
        assertThat(oversized.getUpstreamEtag()).isNull();
        assertThat(oversized.getUpstreamLastModified()).isNull();
        Asset reloaded = assets.findById(stored.asset().getId()).orElseThrow();
        assertThat(reloaded.getUpstreamEtag()).isNull();
        assertThat(reloaded.getUpstreamLastModified()).isNull();

        Asset renewed = storage.touchVerified(reloaded, null, null);
        assertThat(renewed.getUpstreamEtag()).isNull();
    }

    @Test
    void recordingUpstreamValidatorsReplacesBothValues() {
        Repository repo = hosted("h");
        StoredContent stored = storage.store(repo, "g.json", in("{}"), null, Asset.Origin.CACHED, true);
        Asset first = storage.recordUpstreamValidators(stored.asset(), "\"a\"", "Thu, 01 Jan 2026 00:00:00 GMT");

        Asset second = storage.recordUpstreamValidators(first, null, "x".repeat(101));

        assertThat(second.getUpstreamEtag()).isNull();
        assertThat(second.getUpstreamLastModified()).isNull();
        assertThat(second.getLastVerifiedAt()).isNotNull();
    }

    @Test
    void saveSanitisesWhatACallerPutIntoTheAsset() {
        Repository repo = hosted("h");
        Asset asset = storage.store(repo, "s.zip", in("z"), null, Asset.Origin.UPLOADED, false).asset();
        asset.setContentType("application/" + "q".repeat(250));
        asset.setUpstreamEtag("e".repeat(501));
        asset.setUpstreamLastModified("now");

        Asset saved = storage.save(asset);

        assertThat(saved.getContentType()).isEqualTo("application/zip");
        assertThat(saved.getUpstreamEtag()).isNull();
        assertThat(saved.getUpstreamLastModified()).isEqualTo("now");
    }

    @Test
    void componentCoordinatesLongerThanTheirColumnsAreRefusedAsInvalidInput() {
        Repository repo = hosted("h");
        var longVersion = new StorageService.ComponentCoordinates("", "pkg", "1.".repeat(101));
        var longName = new StorageService.ComponentCoordinates("", "n".repeat(501), "1");
        var longNamespace = new StorageService.ComponentCoordinates("s".repeat(501), "n", "1");
        var missingVersion = new StorageService.ComponentCoordinates("", "n", null);

        for (var coordinates : List.of(longVersion, longName, longNamespace, missingVersion)) {
            assertThatThrownBy(() -> storage.ensureComponent(repo, coordinates))
                    .isInstanceOf(InvalidStorageInputException.class)
                    .satisfies(e -> assertThat(((InvalidStorageInputException) e).code()).isEqualTo("component.invalid"));
        }
        assertThat(components.countByRepositoryId(repo.getId())).isZero();
        var max = new StorageService.ComponentCoordinates("s".repeat(500), "n".repeat(500), "v".repeat(200));
        assertThat(storage.ensureComponent(repo, max).getId()).isNotNull();
    }

    @Test
    void nulCharactersArePathAndCoordinateErrors() {
        Repository repo = hosted("h");
        assertThatThrownBy(() -> StorageService.normalise("a/b\u0000.txt"))
                .isInstanceOf(InvalidStorageInputException.class)
                .satisfies(e -> assertThat(((InvalidStorageInputException) e).code()).isEqualTo("path.invalid"));
        assertThatThrownBy(() -> storage.find(repo, "a\u0000")).isInstanceOf(InvalidStorageInputException.class);
        assertThatThrownBy(() -> storage.ensureComponent(repo, new StorageService.ComponentCoordinates("", "n\u0000", "1")))
                .isInstanceOf(InvalidStorageInputException.class)
                .satisfies(e -> assertThat(((InvalidStorageInputException) e).code()).isEqualTo("component.invalid"));
        // other control characters are legal in a path (format handlers refuse them where their protocol does)
        assertThat(StorageService.normalise("a/b\u0001c")).isEqualTo("a/b\u0001c");
    }

    @Test
    void componentCoordinatesTooLongForTheIndexInUtf8AreRefused() {
        Repository repo = hosted("h");
        // every column fits, but 500 + 500 + 200 three-byte characters are 3,600 bytes for one index entry
        var wide = new StorageService.ComponentCoordinates("中".repeat(500), "文".repeat(500), "版".repeat(200));
        assertThatThrownBy(() -> storage.ensureComponent(repo, wide))
                .isInstanceOf(InvalidStorageInputException.class)
                .satisfies(e -> assertThat(((InvalidStorageInputException) e).code()).isEqualTo("component.invalid"));
        assertThatThrownBy(() -> storage.store(repo, "w/1", in("x"), null, Asset.Origin.UPLOADED, false, wide))
                .isInstanceOf(InvalidStorageInputException.class);
        assertThat(components.countByRepositoryId(repo.getId())).isZero();
        assertThat(assets.countByRepositoryId(repo.getId())).isZero();
        // 2,600 bytes together are fine
        var fits = new StorageService.ComponentCoordinates("中".repeat(400), "文".repeat(400), "1".repeat(200));
        assertThat(storage.ensureComponent(repo, fits).getId()).isNotNull();
    }

    @Test
    void linkComponentLinksAnUnlinkedAssetOnceAndLeavesLinkedOnesAlone() {
        Repository repo = proxy("p", "https://upstream.example/");
        var coords = new StorageService.ComponentCoordinates("org.example", "lib", "1.0");
        storage.store(repo, "org/example/lib/1.0/lib-1.0.jar", in("jar"), null, Asset.Origin.CACHED, false);

        Asset linked = storage.linkComponent(repo, "/org/example/lib/1.0/lib-1.0.jar", coords).orElseThrow();
        Asset again = storage.linkComponent(repo, "org/example/lib/1.0/lib-1.0.jar", coords).orElseThrow();

        assertThat(linked.getComponentId()).isNotNull().isEqualTo(again.getComponentId());
        assertThat(components.countByRepositoryId(repo.getId())).isEqualTo(1);
        Component c = components.findById(linked.getComponentId()).orElseThrow();
        assertThat(List.of(c.getNamespace(), c.getName(), c.getVersion())).containsExactly("org.example", "lib", "1.0");
        assertThat(c.getFormat()).isEqualTo(repo.getFormat());

        // an asset that already belongs to a component keeps it
        var other = new StorageService.ComponentCoordinates("org.example", "lib", "2.0");
        assertThat(storage.linkComponent(repo, "org/example/lib/1.0/lib-1.0.jar", other).orElseThrow().getComponentId())
                .isEqualTo(linked.getComponentId());
        assertThat(components.countByRepositoryId(repo.getId())).isEqualTo(1);
        // the component is removed with its last asset, like one recorded at store time
        assertThat(storage.delete(repo, "org/example/lib/1.0/lib-1.0.jar")).isTrue();
        assertThat(components.countByRepositoryId(repo.getId())).isZero();
    }

    @Test
    void linkComponentOfAMissingPathCreatesNothing() {
        Repository repo = proxy("p", "https://upstream.example/");

        assertThat(storage.linkComponent(repo, "nothing/here", new StorageService.ComponentCoordinates("", "n", "1")))
                .isEmpty();
        assertThat(components.countByRepositoryId(repo.getId())).isZero();
    }

    @Test
    void linkComponentRefusesCoordinatesTheTableCannotHold() {
        Repository repo = proxy("p", "https://upstream.example/");
        storage.store(repo, "f", in("x"), null, Asset.Origin.CACHED, false);

        assertThatThrownBy(() -> storage.linkComponent(repo, "f",
                new StorageService.ComponentCoordinates("", "n", "v".repeat(AssetSanitiser.MAX_VERSION_LENGTH + 1))))
                .isInstanceOf(InvalidStorageInputException.class);
        assertThat(storage.find(repo, "f").orElseThrow().asset().getComponentId()).isNull();
        assertThat(components.countByRepositoryId(repo.getId())).isZero();
    }

    @Test
    void concurrentLinksOfFilesOfOneComponentShareOneRow() throws Exception {
        Repository repo = proxy("p", "https://upstream.example/");
        var coords = new StorageService.ComponentCoordinates("", "pkg", "1.0");
        int files = 8;
        for (int i = 0; i < files; i++) {
            storage.store(repo, "pkg/1.0/f" + i, in("f" + i), null, Asset.Origin.CACHED, false);
        }
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(files)) {
            List<java.util.concurrent.Future<Asset>> results = new java.util.ArrayList<>();
            for (int i = 0; i < files * 2; i++) {
                String path = "pkg/1.0/f" + (i % files);
                results.add(pool.submit(() -> {
                    start.await();
                    return storage.linkComponent(repo, path, coords).orElseThrow();
                }));
            }
            start.countDown();
            java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
            for (var result : results) {
                ids.add(result.get(30, java.util.concurrent.TimeUnit.SECONDS).getComponentId());
            }
            assertThat(ids).hasSize(1).doesNotContainNull();
        }
        assertThat(components.countByRepositoryId(repo.getId())).isEqualTo(1);
        assertThat(assets.findByComponentId(components.findByRepositoryIdAndNamespaceAndNameAndVersion(
                repo.getId(), "", "pkg", "1.0").orElseThrow().getId())).hasSize(files);
    }

    @Test
    void storableCoordinatesAreThoseTheStoreAccepts() {
        assertThat(StorageService.isStorable(new StorageService.ComponentCoordinates(null, "n", "1"))).isTrue();
        assertThat(StorageService.isStorable(null)).isFalse();
        assertThat(StorageService.isStorable(new StorageService.ComponentCoordinates("", null, "1"))).isFalse();
        assertThat(StorageService.isStorable(new StorageService.ComponentCoordinates("", "n", null))).isFalse();
        assertThat(StorageService.isStorable(new StorageService.ComponentCoordinates("", "n\u0000", "1"))).isFalse();
        assertThat(StorageService.isStorable(new StorageService.ComponentCoordinates("", "n",
                "1".repeat(AssetSanitiser.MAX_VERSION_LENGTH + 1)))).isFalse();
        assertThat(StorageService.isStorable(new StorageService.ComponentCoordinates("中".repeat(500), "文".repeat(500),
                "版".repeat(200)))).isFalse();
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
