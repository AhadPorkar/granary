package dev.granary.format.cocoapods;

import static dev.granary.format.cocoapods.CocoaPodsTestSupport.httpPodspec;
import static dev.granary.format.cocoapods.CocoaPodsTestSupport.podspec;
import static dev.granary.format.cocoapods.CocoaPodsTestSupport.utf8;
import static dev.granary.format.cocoapods.CocoaPodsTestSupport.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.storage.Checksums;
import dev.granary.format.cocoapods.CocoaPodsTestSupport.FakeStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

class CocoaPodsHostedTest {

    private static final String BASE = "http://granary.test/repository/cocoapods-hosted";

    @TempDir
    Path temp;

    private FakeStorage storage;
    private CocoaPodsHosted hosted;
    private Repository repo;

    @BeforeEach
    void setUp() {
        storage = new FakeStorage();
        hosted = new CocoaPodsHosted(storage.mock);
        repo = CocoaPodsTestSupport.repository("cocoapods-hosted", RepositoryType.HOSTED);
    }

    private CocoaPodsHosted.Pushed push(String json) throws IOException {
        return hosted.push(repo, utf8(json), null, null, null, BASE);
    }

    private CocoaPodsHosted.Archive archive(String filename, byte[] bytes) throws IOException {
        Path file = Files.createTempFile(temp, "archive", ".bin");
        Files.write(file, bytes);
        return new CocoaPodsHosted.Archive(file, filename);
    }

    private static CocoaPodsError error(Throwable t) {
        return (CocoaPodsError) t;
    }

    @Test
    void storesAPodspecAtItsShardedPathExactlyAsPushed() throws IOException {
        String json = podspec("MyPod", "1.0.0");
        CocoaPodsHosted.Pushed pushed = hosted.push(repo, utf8(json), "MyPod", "1.0.0", null, BASE);
        String path = ShardLayout.STANDARD.podspecPath("MyPod", "1.0.0");
        assertThat(pushed.podspecPath()).isEqualTo(path);
        assertThat(pushed.sourcePath()).isNull();
        assertThat(storage.text(repo, path)).isEqualTo(json);
        assertThat(storage.find(repo, path).orElseThrow().asset().getContentType()).isEqualTo("application/json");
        assertThat(storage.find(repo, path).orElseThrow().asset().isMetadata()).isFalse();
        assertThat(storage.coordinates.values()).containsExactly(new dev.granary.core.storage.StorageService.ComponentCoordinates("", "MyPod", "1.0.0"));
    }

    @Test
    void indexesAreBuiltFromTheStoredPodspecs() throws IOException {
        push(podspec("MyPod", "1.0.0"));
        push(podspec("MyPod", "1.10.0"));
        push(podspec("MyPod", "1.2.0-beta.1"));
        push(podspec("Other", "0.1"));
        List<String> fragment = ShardLayout.STANDARD.fragment("MyPod");
        Map<String, List<String>> shard = hosted.shard(repo, fragment);
        assertThat(shard).containsKey("MyPod");
        assertThat(shard.get("MyPod")).containsExactlyInAnyOrder("1.0.0", "1.10.0", "1.2.0-beta.1");
        assertThat(new String(PodIndex.renderShard(shard))).startsWith("MyPod/1.0.0/1.2.0-beta.1/1.10.0\n");
        assertThat(hosted.podNames(repo)).containsExactly("MyPod", "Other");
        // other shards stay empty
        assertThat(hosted.shard(repo, List.of("f", "f", "f"))).doesNotContainKey("MyPod");
        // an unrelated asset under Specs/ is ignored
        storage.put(repo, "Specs/a/b/c/stray.txt", "x");
        assertThat(hosted.podNames(repo)).containsExactly("MyPod", "Other");
    }

    @Test
    void theSameVersionIsRefusedUnlessRedeployIsAllowed() throws IOException {
        push(podspec("MyPod", "1.0.0"));
        assertThatThrownBy(() -> push(podspec("MyPod", "1.0.0").replace("The MyPod pod.", "changed")))
                .isInstanceOf(CocoaPodsError.class)
                .satisfies(e -> {
                    assertThat(error(e).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error(e).code()).isEqualTo("repository.cocoapods.versionExists");
                });
        repo.setAllowRedeploy(true);
        push(podspec("MyPod", "1.0.0").replace("The MyPod pod.", "changed"));
        assertThat(storage.text(repo, ShardLayout.STANDARD.podspecPath("MyPod", "1.0.0"))).contains("changed");
    }

    @Test
    void thePathMustNameThePodspecsNameAndVersion() {
        assertThatThrownBy(() -> hosted.push(repo, utf8(podspec("MyPod", "1.0.0")), "Other", "1.0.0", null, BASE))
                .hasMessageContaining("'MyPod' but the URL names 'Other'");
        assertThatThrownBy(() -> hosted.push(repo, utf8(podspec("MyPod", "1.0.0")), "MyPod", "2.0.0", null, BASE))
                .hasMessageContaining("version 1.0.0 but the URL names 2.0.0");
        // case matters: CDN shards are case-sensitive
        assertThatThrownBy(() -> hosted.push(repo, utf8(podspec("MyPod", "1.0.0")), "mypod", "1.0.0", null, BASE))
                .isInstanceOf(CocoaPodsError.class);
        assertThat(storage.assets).isEmpty();
    }

    @Test
    void invalidPodspecsAreRefusedWithAReason() {
        assertThatThrownBy(() -> push("Pod::Spec.new do |s| end")).hasMessageContaining("pod ipc spec");
        assertThatThrownBy(() -> push("{\"version\": \"1.0\", \"source\": {}}")).hasMessageContaining("no name");
        assertThatThrownBy(() -> push("{\"name\": \"A\", \"source\": {}}")).hasMessageContaining("no version");
        assertThatThrownBy(() -> push("{\"name\": \"A\", \"version\": 1.0, \"source\": {}}")).hasMessageContaining("must be a string");
        assertThatThrownBy(() -> push("{\"name\": \"A\", \"version\": \"latest\", \"source\": {}}")).hasMessageContaining("not a valid CocoaPods version");
        assertThatThrownBy(() -> push("{\"name\": \"My Pod\", \"version\": \"1.0\", \"source\": {}}")).hasMessageContaining("whitespace");
        assertThatThrownBy(() -> push("{\"name\": \".Pod\", \"version\": \"1.0\", \"source\": {}}")).hasMessageContaining("period");
        assertThatThrownBy(() -> push("{\"name\": \"A/B\", \"version\": \"1.0\", \"source\": {}}")).hasMessageContaining("slash");
        assertThatThrownBy(() -> push("{\"name\": \"A\", \"version\": \"1.0\"}")).hasMessageContaining("no source");
        assertThatThrownBy(() -> push("{\"name\": \"A\", \"version\": \"1.0\", \"source\": \"git\"}")).hasMessageContaining("no source");
        assertThat(storage.assets).isEmpty();
    }

    @Test
    void deprecatedVersionsAreListedAndDeletingOrRepushingUnlistsThem() throws IOException {
        repo.setAllowRedeploy(true);
        push(podspec("Old", "1.0").replace("\"license\"", "\"deprecated\": true, \"license\""));
        push(podspec("Old", "2.0").replace("\"license\"", "\"deprecated_in_favor_of\": \"New\", \"license\""));
        push(podspec("New", "1.0"));
        String old1 = ShardLayout.STANDARD.podspecPath("Old", "1.0");
        String old2 = ShardLayout.STANDARD.podspecPath("Old", "2.0");
        assertThat(hosted.deprecated(repo)).containsExactlyInAnyOrder(old1, old2);
        assertThat(storage.find(repo, CocoaPodsPath.DEPRECATED).orElseThrow().asset().isMetadata()).isTrue();

        push(podspec("Old", "2.0"));
        assertThat(hosted.deprecated(repo)).containsExactly(old1);
        assertThat(hosted.deleteVersion(repo, "Old", "1.0")).isEqualTo(1);
        assertThat(hosted.deprecated(repo)).isEmpty();
        assertThat(hosted.deprecatedFile(repo)).isEmpty();
    }

    @Test
    void anArchivePushedWithThePodspecIsStoredAndBecomesItsSource() throws IOException {
        byte[] archive = zip("MyPod/MyPod.swift", "public struct MyPod {}");
        CocoaPodsHosted.Pushed pushed = hosted.push(repo, utf8(podspec("MyPod", "1.0.0")), null, null,
                archive("MyPod-1.0.0.zip", archive), BASE);
        assertThat(pushed.sourcePath()).isEqualTo("sources/MyPod/1.0.0/MyPod-1.0.0.zip");
        assertThat(storage.find(repo, pushed.sourcePath()).orElseThrow().asset().getContentType()).isEqualTo("application/zip");
        JsonNode source = Podspec.parse(utf8(storage.text(repo, pushed.podspecPath()))).node().get("source");
        assertThat(source.get("http").asString()).isEqualTo(BASE + "/sources/MyPod/1.0.0/MyPod-1.0.0.zip");
        assertThat(source.get("sha256").asString()).isEqualTo(Checksums.hex("SHA-256", archive));
        assertThat(source.has("git")).isFalse();
        assertThat(source.has("type")).isFalse();
        assertThat(hosted.isLocalSource(repo, "MyPod", "1.0.0", source.get("http").asString())).isTrue();
        assertThat(hosted.source(repo, "MyPod", "1.0.0", "MyPod-1.0.0.zip")).isPresent();
    }

    @Test
    void theArchiveTypeComesFromTheFileNameOrTheSpecAndMustMatchTheBytes() throws IOException {
        byte[] archive = zip("a.txt", "a");
        String typed = podspec("Typed", "1.0").replace("\"source\": {", "\"source\": {\"type\": \"zip\", ");
        CocoaPodsHosted.Pushed pushed = hosted.push(repo, utf8(typed), null, null, archive("blob", archive), BASE);
        assertThat(pushed.sourcePath()).isEqualTo("sources/Typed/1.0/blob");
        assertThat(Podspec.parse(utf8(storage.text(repo, pushed.podspecPath()))).sourceType()).contains("zip");

        assertThatThrownBy(() -> hosted.push(repo, utf8(podspec("NoType", "1.0")), null, null, archive("blob", archive), BASE))
                .hasMessageContaining("Cannot tell the archive type");
        assertThatThrownBy(() -> hosted.push(repo, utf8(podspec("Fake", "1.0")), null, null,
                archive("Fake.tar.gz", utf8("<html>not an archive</html>")), BASE))
                .hasMessageContaining("not a valid tgz file");
        assertThatThrownBy(() -> hosted.push(repo, utf8(podspec("Empty", "1.0")), null, null, archive("Empty.zip", new byte[0]), BASE))
                .hasMessageContaining("empty");
        String badType = podspec("BadType", "1.0").replace("\"source\": {", "\"source\": {\"type\": \"rar\", ");
        assertThatThrownBy(() -> hosted.push(repo, utf8(badType), null, null, archive("x.zip", archive), BASE))
                .hasMessageContaining("Unsupported source.type 'rar'");
    }

    @Test
    void checksumsTheSpecStatesMustMatchTheArchive() throws IOException {
        byte[] archive = zip("a.txt", "a");
        String wrong256 = httpPodspec("Sum", "1.0", "https://x/Sum.zip").replace("\"flatten\": true", "\"sha256\": \"00\"");
        assertThatThrownBy(() -> hosted.push(repo, utf8(wrong256), null, null, archive("Sum.zip", archive), BASE))
                .hasMessageContaining("source.sha256 00 does not match");
        String wrong1 = httpPodspec("Sum", "1.0", "https://x/Sum.zip").replace("\"flatten\": true", "\"sha1\": \"00\"");
        assertThatThrownBy(() -> hosted.push(repo, utf8(wrong1), null, null, archive("Sum.zip", archive), BASE))
                .hasMessageContaining("source.sha1 00 does not match");
        String right = httpPodspec("Sum", "1.0", "https://x/Sum.zip").replace("\"flatten\": true",
                "\"sha256\": \"" + Checksums.hex("SHA-256", archive).toUpperCase() + "\", \"sha1\": \"" + Checksums.hex("SHA-1", archive) + "\"");
        assertThat(hosted.push(repo, utf8(right), null, null, archive("Sum.zip", archive), BASE).sourcePath()).isNotNull();
        assertThat(storage.find(repo, "sources/Sum/1.0/Sum.zip")).isPresent();
    }

    @Test
    void redeployingWithAnotherArchiveReplacesTheOldOne() throws IOException {
        repo.setAllowRedeploy(true);
        hosted.push(repo, utf8(podspec("Re", "1.0")), null, null, archive("Re-a.zip", zip("a", "a")), BASE);
        hosted.push(repo, utf8(podspec("Re", "1.0")), null, null, archive("Re-b.zip", zip("b", "b")), BASE);
        assertThat(storage.paths(repo)).contains("sources/Re/1.0/Re-b.zip").doesNotContain("sources/Re/1.0/Re-a.zip");
    }

    @Test
    void anArchiveCanBeUploadedOnItsOwn() throws IOException {
        Path file = archive("x", zip("a", "a")).file();
        assertThat(hosted.putSource(repo, "Bin", "1.0", "Bin-1.0.zip", file)).isEqualTo("sources/Bin/1.0/Bin-1.0.zip");
        assertThatThrownBy(() -> hosted.putSource(repo, "Bin", "1.0", "Bin-1.0.zip", file))
                .satisfies(e -> assertThat(error(e).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> hosted.putSource(repo, "Bin", "1.0", "Bin-1.0.rar", file)).hasMessageContaining("Name the archive");
        assertThatThrownBy(() -> hosted.putSource(repo, "Bin", "1.0", "Bin 1.0.zip", file)).hasMessageContaining("may only contain");
        assertThatThrownBy(() -> hosted.putSource(repo, "Bin", "1.0", "Bin-1.0.tgz", file)).hasMessageContaining("not a valid tgz");
        assertThatThrownBy(() -> hosted.putSource(repo, "B n", "1.0", "Bin.zip", file)).hasMessageContaining("whitespace");
        assertThatThrownBy(() -> hosted.putSource(repo, "Bin", "x", "Bin.zip", file)).hasMessageContaining("not a valid CocoaPods version");

        // a podspec pushed afterwards that points at it is served with a local source
        String spec = httpPodspec("Bin", "1.0", BASE + "/sources/Bin/1.0/Bin-1.0.zip");
        push(spec);
        assertThat(hosted.isLocalSource(repo, "Bin", "1.0", "https://elsewhere.example/repository/x/sources/Bin/1.0/Bin-1.0.zip")).isTrue();
        assertThat(hosted.isLocalSource(repo, "Bin", "1.0", "https://elsewhere.example/Bin-1.0.zip")).isFalse();
        assertThat(hosted.isLocalSource(repo, "Bin", "1.0", "https://x/sources/Bin/1.0/Other.zip")).isFalse();
        assertThat(hosted.isLocalSource(repo, "Bin", "1.0", "git@github.com:x/y.git")).isFalse();
    }

    @Test
    void deletingAVersionRemovesThePodspecAndItsArchives() throws IOException {
        hosted.push(repo, utf8(podspec("Gone", "1.0")), null, null, archive("Gone.zip", zip("a", "a")), BASE);
        push(podspec("Gone", "1.0.1"));
        assertThat(hosted.deleteVersion(repo, "Gone", "1.0")).isEqualTo(2);
        assertThat(storage.paths(repo)).containsExactly(ShardLayout.STANDARD.podspecPath("Gone", "1.0.1"));
        assertThat(hosted.deleteVersion(repo, "Gone", "1.0")).isZero();
        assertThat(hosted.deleteSource(repo, "Gone", "1.0", "Gone.zip")).isFalse();
    }
}
