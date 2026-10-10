package dev.granary.format.pub;

import static dev.granary.format.pub.PubTestSupport.archive;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.storage.StorageService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PubHostedTest {

    private static final Instant NOW = Instant.parse("2026-10-10T08:00:00.123456Z");

    @TempDir
    Path tmp;

    private final PubTestSupport.FakeStorage storage = new PubTestSupport.FakeStorage();
    private PubHosted hosted;
    private Repository repo;

    @BeforeEach
    void setUp() {
        hosted = new PubHosted(storage.mock, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
        repo = PubTestSupport.repository("pub-hosted", RepositoryType.HOSTED);
    }

    private Path file(byte[] bytes) throws Exception {
        Path file = Files.createTempFile(tmp, "upload", ".tar.gz");
        Files.write(file, bytes);
        return file;
    }

    private PubHosted.Published publish(String name, String version) throws Exception {
        return hosted.publish(repo, file(archive(name, version)));
    }

    @Test
    void publishStoresTheArchiveAndTheListing() throws Exception {
        byte[] bytes = archive("demo", "1.0.0");
        PubHosted.Published published = hosted.publish(repo, file(bytes));
        assertThat(published).isEqualTo(new PubHosted.Published("demo", "1.0.0", false));

        assertThat(storage.bytes(repo, "packages/demo/versions/1.0.0.tar.gz")).isEqualTo(bytes);
        assertThat(storage.coordinates.values()).containsExactly(new StorageService.ComponentCoordinates("", "demo", "1.0.0"));
        assertThat(storage.find(repo, "packages/demo/versions/1.0.0.tar.gz").orElseThrow().asset().isMetadata()).isFalse();
        assertThat(storage.find(repo, "api/packages/demo").orElseThrow().asset().isMetadata()).isTrue();

        ObjectNode listing = hosted.listing(repo, "demo").orElseThrow();
        JsonNode version = listing.get("versions").get(0);
        assertThat(version.get("version").stringValue()).isEqualTo("1.0.0");
        assertThat(version.get("archive_sha256").stringValue()).isEqualTo(PubTestSupport.sha256(bytes));
        assertThat(version.get("published").stringValue()).isEqualTo("2026-10-10T08:00:00.123456Z");
        assertThat(version.get("pubspec").get("dependencies").get("path").stringValue()).isEqualTo("^1.9.0");
        assertThat(version.has("archive_url")).isFalse(); // added per request, never stored
    }

    @Test
    void versionsAccumulate() throws Exception {
        publish("demo", "1.0.0");
        publish("demo", "1.1.0");
        publish("demo", "2.0.0-dev.1");
        publish("other", "0.1.0");
        assertThat(hosted.entries(repo, "demo")).extracting(e -> e.version().text())
                .containsExactly("1.0.0", "1.1.0", "2.0.0-dev.1");
        assertThat(hosted.entries(repo, "other")).hasSize(1);
    }

    @Test
    void anExistingVersionIsRefusedWithoutRedeploy() throws Exception {
        publish("demo", "1.0.0");
        assertThatThrownBy(() -> publish("demo", "1.0.0"))
                .isInstanceOfSatisfying(PubError.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo(PubError.PACKAGE_REJECTED);
                    assertThat(e.getMessage()).contains("Version 1.0.0 of package demo already exists");
                });
        // build metadata makes it another version
        assertThat(publish("demo", "1.0.0+0").version()).isEqualTo("1.0.0+0");
    }

    @Test
    void equalSpellingsCountAsDuplicates() throws Exception {
        publish("demo", "1.0.0+1");
        assertThatThrownBy(() -> publish("demo", "1.0.0+01")).isInstanceOf(PubError.class)
                .hasMessageContaining("Version 1.0.0+1 of package demo already exists");
    }

    @Test
    void redeployReplacesTheVersion() throws Exception {
        repo.setAllowRedeploy(true);
        publish("demo", "1.0.0");
        hosted.setRetracted(repo, "demo", "1.0.0", true);
        byte[] second = PubTestSupport.targz(Map.of("pubspec.yaml",
                (PubTestSupport.pubspec("demo", "1.0.0") + "# changed\n").getBytes(StandardCharsets.UTF_8)));
        PubHosted.Published published = hosted.publish(repo, file(second));
        assertThat(published.replaced()).isTrue();
        assertThat(storage.bytes(repo, "packages/demo/versions/1.0.0.tar.gz")).isEqualTo(second);
        List<PubListing.Entry> entries = hosted.entries(repo, "demo");
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().sha256()).contains(PubTestSupport.sha256(second));
        assertThat(entries.getFirst().retracted()).isFalse();
    }

    @Test
    void redeployUnderAnotherSpellingMovesTheArchive() throws Exception {
        repo.setAllowRedeploy(true);
        publish("demo", "1.0.0+1");
        publish("demo", "1.0.0+01");
        assertThat(storage.paths(repo)).contains("packages/demo/versions/1.0.0+01.tar.gz")
                .doesNotContain("packages/demo/versions/1.0.0+1.tar.gz");
        assertThat(hosted.entries(repo, "demo")).extracting(e -> e.version().text()).containsExactly("1.0.0+01");
    }

    @Test
    void aVersionWhoseArchiveVanishedCanBePublishedAgain() throws Exception {
        publish("demo", "1.0.0");
        storage.assets.remove(repo.getId() + "|packages/demo/versions/1.0.0.tar.gz"); // deleted as a component in the UI
        assertThat(hosted.listing(repo, "demo")).isEmpty();
        publish("demo", "1.0.0");
        assertThat(hosted.listing(repo, "demo")).isPresent();
    }

    @Test
    void listingHidesVersionsWithoutArchives() throws Exception {
        publish("demo", "1.0.0");
        publish("demo", "1.1.0");
        storage.assets.remove(repo.getId() + "|packages/demo/versions/1.0.0.tar.gz");
        ObjectNode listing = hosted.listing(repo, "demo").orElseThrow();
        assertThat(PubListing.entries(listing)).extracting(e -> e.version().text()).containsExactly("1.1.0");
        assertThat(hosted.listing(repo, "unknown")).isEmpty();
    }

    @Test
    void rejectsArchivesThatCannotBePublished() throws Exception {
        assertThatThrownBy(() -> hosted.publish(repo, file(new byte[0])))
                .isInstanceOf(PubError.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> hosted.publish(repo, file("not an archive".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(PubError.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo(PubError.PACKAGE_REJECTED);
                    assertThat(e.getMessage()).contains("not gzip-compressed");
                });
        byte[] badPubspec = PubTestSupport.targz(Map.of("pubspec.yaml", "name: Bad\nversion: 1.0.0\n".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> hosted.publish(repo, file(badPubspec))).hasMessageContaining("lower-case");
        byte[] noVersion = PubTestSupport.targz(Map.of("pubspec.yaml", "name: ok\n".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> hosted.publish(repo, file(noVersion))).hasMessageContaining("no \"version\"");
        assertThat(storage.assets).isEmpty();
    }

    @Test
    void enforcesTheArchiveSizeLimit() throws Exception {
        repo.setFormatSettings(Map.of(PubSettings.MAX_ARCHIVE_SIZE, "1"));
        byte[] random = new byte[1536 * 1024];
        new java.util.Random(1).nextBytes(random); // incompressible
        byte[] big = PubTestSupport.targz(Map.of("pubspec.yaml", PubTestSupport.pubspec("big", "1.0.0")
                .getBytes(StandardCharsets.UTF_8), "assets/noise.bin", random));
        assertThatThrownBy(() -> hosted.publish(repo, file(big)))
                .isInstanceOf(PubError.class).hasMessageContaining("accepts at most 1.0 MiB");
    }

    @Test
    void deleteVersionRemovesArchiveAndEntry() throws Exception {
        publish("demo", "1.0.0");
        publish("demo", "1.1.0");
        assertThat(hosted.deleteVersion(repo, "demo", "1.0.0")).isTrue();
        assertThat(storage.paths(repo)).doesNotContain("packages/demo/versions/1.0.0.tar.gz");
        assertThat(hosted.entries(repo, "demo")).extracting(e -> e.version().text()).containsExactly("1.1.0");
        assertThat(hosted.deleteVersion(repo, "demo", "1.0.0")).isFalse();
        assertThat(hosted.deleteVersion(repo, "demo", "1.1.0")).isTrue();
        assertThat(storage.paths(repo)).isEmpty(); // the last version takes the listing with it
    }

    @Test
    void deletePackageRemovesEverything() throws Exception {
        publish("demo", "1.0.0");
        publish("demo", "1.1.0");
        publish("demo_two", "1.0.0");
        assertThat(hosted.deletePackage(repo, "demo")).isEqualTo(2);
        assertThat(storage.paths(repo)).containsExactlyInAnyOrder("api/packages/demo_two",
                "packages/demo_two/versions/1.0.0.tar.gz");
        assertThat(hosted.deletePackage(repo, "demo")).isZero();
    }

    @Test
    void retractAndRestore() throws Exception {
        publish("demo", "1.0.0");
        hosted.setRetracted(repo, "demo", "1.0.0", true);
        assertThat(hosted.entries(repo, "demo").getFirst().retracted()).isTrue();
        hosted.setRetracted(repo, "demo", "1.0.0", false);
        assertThat(hosted.entries(repo, "demo").getFirst().node().has("retracted")).isFalse();
        assertThatThrownBy(() -> hosted.setRetracted(repo, "demo", "9.0.0", true))
                .isInstanceOfSatisfying(PubError.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> hosted.setRetracted(repo, "nope", "1.0.0", true)).isInstanceOf(PubError.class);
    }

    @Test
    void discontinueAndContinue() throws Exception {
        publish("demo", "1.0.0");
        hosted.setDiscontinued(repo, "demo", true, "demo_next");
        ObjectNode listing = hosted.listing(repo, "demo").orElseThrow();
        assertThat(listing.get("isDiscontinued").booleanValue()).isTrue();
        assertThat(listing.get("replacedBy").stringValue()).isEqualTo("demo_next");
        hosted.setDiscontinued(repo, "demo", true, null);
        assertThat(hosted.listing(repo, "demo").orElseThrow().has("replacedBy")).isFalse();
        hosted.setDiscontinued(repo, "demo", false, null);
        assertThat(hosted.listing(repo, "demo").orElseThrow().has("isDiscontinued")).isFalse();
        assertThatThrownBy(() -> hosted.setDiscontinued(repo, "demo", true, "demo")).hasMessageContaining("itself");
        assertThatThrownBy(() -> hosted.setDiscontinued(repo, "demo", true, "not a name")).hasMessageContaining("package name");
        assertThatThrownBy(() -> hosted.setDiscontinued(repo, "nope", true, null)).isInstanceOf(PubError.class);
    }

    @Test
    void archiveLookupAcceptsAnotherSpelling() throws Exception {
        publish("demo", "1.0.0+1");
        assertThat(hosted.archive(repo, "demo", "1.0.0+1")).isPresent();
        assertThat(hosted.archive(repo, "demo", "1.0.0+01")).isPresent();
        assertThat(hosted.archive(repo, "demo", "1.0.0")).isEmpty();
        assertThat(hosted.archive(repo, "nope", "1.0.0")).isEmpty();
    }

    @Test
    void anUnreadableStoredListingIsTreatedAsMissing() throws Exception {
        storage.put(repo, "api/packages/demo", "{broken".getBytes(StandardCharsets.UTF_8), "application/json", true);
        assertThat(hosted.listing(repo, "demo")).isEmpty();
        storage.put(repo, "api/packages/demo", "[]".getBytes(StandardCharsets.UTF_8), "application/json", true);
        assertThat(hosted.listing(repo, "demo")).isEmpty();
        publish("demo", "1.0.0"); // and a publish starts it over
        assertThat(hosted.listing(repo, "demo")).isPresent();
    }

    @Test
    void concurrentPublishesOfOnePackageLoseNothing() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<PubHosted.Published>> futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                Path upload = file(archive("demo", "1.0." + i));
                futures.add(pool.submit((Callable<PubHosted.Published>) () -> hosted.publish(repo, upload)));
            }
            for (Future<PubHosted.Published> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(hosted.entries(repo, "demo")).hasSize(24);
        assertThat(hosted.listing(repo, "demo").orElseThrow().get("versions")).hasSize(24);
    }
}
