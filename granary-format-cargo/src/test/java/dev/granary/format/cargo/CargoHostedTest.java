package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.model.Repository;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.node.ObjectNode;

class CargoHostedTest {

    @TempDir
    Path temp;

    private CargoFixture fx;
    private Repository repo;

    @BeforeEach
    void setUp() {
        fx = new CargoFixture(temp);
        repo = CargoTestSupport.hosted("cargo-hosted");
    }

    private ObjectNode publish(String name, String version) throws IOException {
        return publish(CargoTestSupport.metadata(name, version), CargoTestSupport.crate(name, version), "alice");
    }

    private ObjectNode publish(ObjectNode metadata, byte[] crate, String publisher) throws IOException {
        byte[] body = CargoTestSupport.publishBody(metadata, crate);
        try (PublishRequest request = PublishRequest.read(new ByteArrayInputStream(body), temp.resolve("tmp"))) {
            return fx.hosted.publish(repo, request, PublishMetadata.of(request.metadata()), publisher);
        }
    }

    @Test
    void publishStoresCrateIndexLineAndDocument() throws IOException {
        byte[] crate = CargoTestSupport.crate("acme-hello", "0.1.0");
        ObjectNode result = publish(CargoTestSupport.metadata("acme-hello", "0.1.0"), crate, "alice");
        assertThat(CargoJson.write(result)).isEqualTo(
                "{\"warnings\":{\"invalid_categories\":[],\"invalid_badges\":[],\"other\":[]}}");

        assertThat(fx.storage.paths(repo)).containsExactlyInAnyOrder("crates/acme-hello/acme-hello-0.1.0.crate",
                "index/ac/me/acme-hello", "meta/acme-hello.json");
        assertThat(fx.storage.bytes(repo, "crates/acme-hello/acme-hello-0.1.0.crate")).isEqualTo(crate);
        assertThat(fx.storage.asset(repo, "crates/acme-hello/acme-hello-0.1.0.crate").isMetadata()).isFalse();
        assertThat(fx.storage.asset(repo, "crates/acme-hello/acme-hello-0.1.0.crate").getContentType()).isEqualTo("application/gzip");
        assertThat(fx.storage.coordinates.values())
                .containsExactly(new StorageService.ComponentCoordinates("", "acme-hello", "0.1.0"));

        List<IndexEntry> lines = IndexEntry.parseFile(fx.storage.text(repo, "index/ac/me/acme-hello"));
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().cksum()).isEqualTo(Checksums.hex("SHA-256", crate));
        assertThat(lines.getFirst().yanked()).isFalse();
        assertThat(fx.storage.asset(repo, "index/ac/me/acme-hello").isMetadata()).isTrue();

        ObjectNode meta = fx.hosted.meta(repo, "acme-hello").orElseThrow();
        assertThat(CargoHosted.ownersOf(meta)).containsExactly("alice");
        assertThat(meta.get("description").stringValue()).isEqualTo("Hello from acme-hello");
        assertThat(meta.path("versions").path("0.1.0").path("published_by").stringValue()).isEqualTo("alice");
        assertThat(fx.storage.asset(repo, "meta/acme-hello.json").isMetadata()).isTrue();
    }

    @Test
    void laterVersionsAppendInPublishOrder() throws IOException {
        publish("acme", "1.0.0");
        publish("acme", "0.9.0");
        publish("acme", "1.1.0-rc.1");
        assertThat(fx.hosted.liveEntries(repo, "acme")).extracting(IndexEntry::vers).containsExactly("1.0.0", "0.9.0", "1.1.0-rc.1");
        assertThat(fx.storage.paths(repo)).filteredOn(p -> p.endsWith(".crate")).hasSize(3);
    }

    @Test
    void anExistingVersionIsRefusedWithCargosMessage() throws IOException {
        publish("acme", "1.0.0");
        assertThatThrownBy(() -> publish("acme", "1.0.0"))
                .isInstanceOf(CargoError.class)
                .hasMessage("crate version `1.0.0` is already uploaded")
                .satisfies(e -> assertThat(((CargoError) e).status()).isEqualTo(HttpStatus.CONFLICT));
        // differing only in build metadata is the same version
        assertThatThrownBy(() -> publish("acme", "1.0.0+linux"))
                .isInstanceOf(CargoError.class)
                .hasMessage("crate version `1.0.0` is already uploaded");
        assertThat(fx.hosted.liveEntries(repo, "acme")).hasSize(1);
    }

    @Test
    void redeployReplacesTheVersion() throws IOException {
        repo.setAllowRedeploy(true);
        publish("acme", "1.0.0");
        fx.hosted.setYanked(repo, "acme", "1.0.0", true);
        ObjectNode changed = CargoTestSupport.metadata("acme", "1.0.0");
        changed.put("description", "second take");
        byte[] crate = CargoTestSupport.tarGz(java.util.Map.of("acme-1.0.0/Cargo.toml", "changed"));
        publish(changed, crate, "bob");
        List<IndexEntry> lines = fx.hosted.liveEntries(repo, "acme");
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().cksum()).isEqualTo(Checksums.hex("SHA-256", crate));
        assertThat(lines.getFirst().yanked()).isFalse();
        assertThat(fx.storage.bytes(repo, "crates/acme/acme-1.0.0.crate")).isEqualTo(crate);
        // the original publisher stays the owner
        assertThat(fx.hosted.owners(repo, "acme")).containsExactly("alice");
    }

    @Test
    void redeployWithOtherBuildMetadataDropsTheOldFile() throws IOException {
        repo.setAllowRedeploy(true);
        publish("acme", "1.0.0+a");
        publish("acme", "1.0.0+b");
        assertThat(fx.hosted.liveEntries(repo, "acme")).extracting(IndexEntry::vers).containsExactly("1.0.0+b");
        assertThat(fx.storage.paths(repo)).contains("crates/acme/acme-1.0.0+b.crate").doesNotContain("crates/acme/acme-1.0.0+a.crate");
    }

    @Test
    void aBadArchiveStoresNothing() {
        byte[] crate = CargoTestSupport.tarGz(java.util.Map.of("other-1.0.0/Cargo.toml", "x"));
        assertThatThrownBy(() -> publish(CargoTestSupport.metadata("acme", "1.0.0"), crate, "alice"))
                .isInstanceOf(CargoError.class)
                .hasMessageContaining("outside the `acme-1.0.0/` directory");
        assertThat(fx.storage.paths(repo)).isEmpty();
    }

    @Test
    void theNameKeepsItsFirstSpelling() throws IOException {
        publish("Acme", "1.0.0");
        assertThatThrownBy(() -> publish("acme", "1.1.0"))
                .isInstanceOf(CargoError.class)
                .hasMessageContaining("crate `Acme` already exists")
                .satisfies(e -> assertThat(((CargoError) e).status()).isEqualTo(HttpStatus.CONFLICT));
        publish("Acme", "1.1.0");
        assertThat(fx.hosted.liveEntries(repo, "acme")).extracting(IndexEntry::name).containsExactly("Acme", "Acme");
    }

    @Test
    void namesDifferingOnlyInSeparatorsAreTheSameCrate() throws IOException {
        publish("foo-bar", "1.0.0");
        assertThatThrownBy(() -> publish("foo_bar", "1.0.0"))
                .isInstanceOf(CargoError.class)
                .hasMessageContaining("too similar to the existing crate `foo-bar`");
        publish("a-b-c-d", "1.0.0");
        // the separator in the first four characters moves the crate to another index directory
        assertThatThrownBy(() -> publish("a_b_c-d", "1.0.0"))
                .isInstanceOf(CargoError.class)
                .hasMessageContaining("too similar to the existing crate `a-b-c-d`");
        // an unrelated crate in the same directory is fine
        publish("foo-baz", "1.0.0");
    }

    @Test
    void aVersionWhoseCrateFileWasDeletedDisappearsAndCanBePublishedAgain() throws IOException {
        publish("acme", "1.0.0");
        publish("acme", "1.1.0");
        fx.storage.mock.delete(repo, "crates/acme/acme-1.0.0.crate");
        assertThat(fx.hosted.liveEntries(repo, "acme")).extracting(IndexEntry::vers).containsExactly("1.1.0");
        assertThat(fx.hosted.storedEntries(repo, "acme")).hasSize(2);
        publish("acme", "1.0.0");
        assertThat(fx.hosted.storedEntries(repo, "acme")).extracting(IndexEntry::vers).containsExactly("1.1.0", "1.0.0");
    }

    @Test
    void yankAndUnyank() throws IOException {
        publish("acme", "1.0.0");
        publish("acme", "2.0.0");
        String untouched = fx.hosted.liveEntries(repo, "acme").get(1).line();
        fx.hosted.setYanked(repo, "acme", "1.0.0", true);
        assertThat(fx.hosted.liveEntries(repo, "acme")).extracting(IndexEntry::yanked).containsExactly(true, false);
        assertThat(fx.hosted.liveEntries(repo, "acme").get(1).line()).isEqualTo(untouched);
        fx.hosted.setYanked(repo, "acme", "1.0.0", true); // idempotent
        fx.hosted.setYanked(repo, "ACME", "1.0.0", false);
        assertThat(fx.hosted.liveEntries(repo, "acme")).extracting(IndexEntry::yanked).containsExactly(false, false);
    }

    @Test
    void yankOfSomethingMissing() throws IOException {
        assertThatThrownBy(() -> fx.hosted.setYanked(repo, "nope", "1.0.0", true))
                .isInstanceOf(CargoError.class).hasMessage("crate `nope` does not exist")
                .satisfies(e -> assertThat(((CargoError) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
        publish("acme", "1.0.0");
        assertThatThrownBy(() -> fx.hosted.setYanked(repo, "acme", "9.9.9", true))
                .isInstanceOf(CargoError.class).hasMessage("crate `acme` does not have a version `9.9.9`");
    }

    @Test
    void owners() throws IOException {
        publish("acme", "1.0.0");
        assertThat(fx.hosted.owners(repo, "acme")).containsExactly("alice");
        assertThat(fx.hosted.addOwners(repo, "acme", List.of("bob"))).isEqualTo("user bob has been added as an owner of crate acme");
        assertThat(fx.hosted.addOwners(repo, "acme", List.of("carol", "bob")))
                .isEqualTo("user carol, bob have been added as owners of crate acme");
        assertThat(fx.hosted.owners(repo, "acme")).containsExactly("alice", "bob", "carol");
        assertThat(fx.hosted.removeOwners(repo, "acme", List.of("ALICE", "carol"))).isEqualTo("owners successfully removed");
        assertThat(fx.hosted.owners(repo, "acme")).containsExactly("bob");
        assertThatThrownBy(() -> fx.hosted.removeOwners(repo, "acme", List.of("bob")))
                .isInstanceOf(CargoError.class).hasMessageContaining("cannot remove all owners");
        assertThatThrownBy(() -> fx.hosted.removeOwners(repo, "acme", List.of("zed")))
                .isInstanceOf(CargoError.class).hasMessageContaining("`zed` is not an owner");
        assertThatThrownBy(() -> fx.hosted.addOwners(repo, "acme", List.of("ghost")))
                .isInstanceOf(CargoError.class).hasMessageContaining("could not find user with login `ghost`");
        assertThatThrownBy(() -> fx.hosted.owners(repo, "nope"))
                .isInstanceOf(CargoError.class).hasMessage("crate `nope` does not exist");
        // later publishes keep the owners
        publish("acme", "1.1.0");
        assertThat(fx.hosted.owners(repo, "acme")).containsExactly("bob");
    }

    @Test
    void descriptionFollowsTheHighestVersion() throws IOException {
        publish("acme", "2.0.0");
        ObjectNode older = CargoTestSupport.metadata("acme", "1.5.0");
        older.put("description", "old");
        publish(older, CargoTestSupport.crate("acme", "1.5.0"), "alice");
        assertThat(fx.hosted.meta(repo, "acme").orElseThrow().get("description").stringValue()).isEqualTo("Hello from acme");
        ObjectNode newer = CargoTestSupport.metadata("acme", "3.0.0");
        newer.put("description", "new");
        publish(newer, CargoTestSupport.crate("acme", "3.0.0"), "alice");
        assertThat(fx.hosted.meta(repo, "acme").orElseThrow().get("description").stringValue()).isEqualTo("new");
        assertThat(fx.hosted.meta(repo, "acme").orElseThrow().path("versions").properties()).hasSize(3);
    }

    @Test
    void unreadableStoredFilesAreTreatedAsEmpty() {
        fx.storage.put(repo, "index/ac/me/acme", "not json at all\n", true);
        fx.storage.put(repo, "meta/acme.json", "[]", true);
        assertThat(fx.hosted.storedEntries(repo, "acme")).isEmpty();
        assertThat(fx.hosted.meta(repo, "acme")).isEmpty();
    }

    @Test
    void concurrentPublishesOfOneCrateKeepEveryLine() throws Exception {
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String version = "1.0." + i;
            byte[] body = CargoTestSupport.publishBody("acme", version);
            futures.add(pool.submit(() -> {
                start.await();
                try (PublishRequest request = PublishRequest.read(new ByteArrayInputStream(body), temp.resolve("tmp"))) {
                    PublishMetadata meta = PublishMetadata.of(request.metadata());
                    fx.hosted.publish(repo, request, meta, "alice");
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertThat(fx.hosted.liveEntries(repo, "acme")).hasSize(n);
    }
}
