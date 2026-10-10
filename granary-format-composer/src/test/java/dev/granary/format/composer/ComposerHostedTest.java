package dev.granary.format.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.storage.StorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.node.ObjectNode;

class ComposerHostedTest {

    @TempDir
    Path dir;

    private final ComposerTestSupport.FakeStorage storage = new ComposerTestSupport.FakeStorage();
    private ComposerHosted hosted;
    private Repository repo;

    @BeforeEach
    void setUp() {
        hosted = new ComposerHosted(storage.mock, ComposerTestSupport.JSON);
        repo = ComposerTestSupport.repository("composer-hosted", RepositoryType.HOSTED);
    }

    @Test
    void publishStoresTheArchiveAndGeneratesMetadata() throws IOException {
        byte[] zip = ComposerTestSupport.packageZip(ComposerTestSupport.composerJson("acme/lib", null, "\"homepage\": \"https://acme.example\""));
        String sha1 = ComposerTestSupport.sha1(zip);

        ComposerHosted.Published published = hosted.publish(repo, upload(zip, "acme/lib", "1.0.0"));

        assertThat(published.name()).isEqualTo("acme/lib");
        assertThat(published.version()).isEqualTo("1.0.0");
        assertThat(published.normalizedVersion()).isEqualTo("1.0.0.0");
        assertThat(published.distPath()).isEqualTo("dist/acme/lib/1.0.0/" + sha1 + ".zip");
        assertThat(published.replaced()).isFalse();
        assertThat(storage.bytes(repo, published.distPath())).isEqualTo(zip);
        assertThat(storage.coordinatesOf(repo, published.distPath()))
                .isEqualTo(new StorageService.ComponentCoordinates("acme", "lib", "1.0.0"));
        assertThat(storage.originOf(repo, published.distPath())).isEqualTo(Asset.Origin.UPLOADED);

        ObjectNode stored = storage.json(repo, "p2/acme/lib.json");
        assertThat(stored.get("minified").stringValue()).isEqualTo("composer/2.0");
        ObjectNode entry = (ObjectNode) stored.get("packages").get("acme/lib").get(0);
        assertThat(entry.get("name").stringValue()).isEqualTo("acme/lib");
        assertThat(entry.get("version").stringValue()).isEqualTo("1.0.0");
        assertThat(entry.get("version_normalized").stringValue()).isEqualTo("1.0.0.0");
        assertThat(entry.get("description").stringValue()).isEqualTo("Demo library acme/lib");
        assertThat(entry.get("require").get("php").stringValue()).isEqualTo(">=8.1");
        assertThat(entry.get("homepage").stringValue()).isEqualTo("https://acme.example");
        assertThat(entry.get("autoload").get("psr-4").has("Acme\\Lib\\")).isTrue();
        assertThat(entry.get("time").stringValue()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\+00:00");
        assertThat(entry.get("dist").get("type").stringValue()).isEqualTo("zip");
        assertThat(entry.get("dist").get("url").stringValue()).isEqualTo(published.distPath());
        assertThat(entry.get("dist").get("reference").stringValue()).isEqualTo(sha1);
        assertThat(entry.get("dist").get("shasum").stringValue()).isEqualTo(sha1);
        assertThat(hosted.packageNames(repo)).containsExactly("acme/lib");
    }

    @Test
    void takesNameAndVersionFromComposerJsonWhenTheUrlHasNone() throws IOException {
        byte[] zip = ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", "v2.1.0-beta.1", null));

        ComposerHosted.Published published = hosted.publish(repo, upload(zip, null, null));

        assertThat(published.name()).isEqualTo("acme/lib");
        assertThat(published.version()).isEqualTo("v2.1.0-beta.1");
        assertThat(published.normalizedVersion()).isEqualTo("2.1.0.0-beta1");
    }

    @Test
    void branchesGoToTheDevDocument() throws IOException {
        byte[] zip = ComposerTestSupport.packageZip(ComposerTestSupport.composerJson("acme/lib", null, null));

        hosted.publish(repo, upload(zip, "acme/lib", "dev-main"));
        hosted.publish(repo, upload(zip, "acme/lib", "2.x-dev"));

        assertThat(storage.has(repo, "p2/acme/lib.json")).isFalse();
        P2Document dev = hosted.document(repo, "acme/lib", true).orElseThrow();
        assertThat(dev.versions()).extracting(P2Document::version).containsExactly("2.x-dev", "dev-main");
        assertThat(hosted.document(repo, "acme/lib", false)).isEmpty();
    }

    @Test
    void keepsVersionsNewestFirst() throws IOException {
        for (String version : new String[] {"1.0.0", "1.10.0", "1.2.0", "2.0.0-RC1"}) {
            hosted.publish(repo, upload(ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, "\"extra\": {\"v\": \"" + version + "\"}")),
                    "acme/lib", version));
        }
        assertThat(hosted.document(repo, "acme/lib", false).orElseThrow().versions())
                .extracting(P2Document::version).containsExactly("2.0.0-RC1", "1.10.0", "1.2.0", "1.0.0");
    }

    @Test
    void refusesToOverwriteAVersionUnlessRedeployIsAllowed() throws IOException {
        byte[] first = ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, null));
        byte[] second = ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, "\"homepage\": \"https://new\""));
        ComposerHosted.Published original = hosted.publish(repo, upload(first, "acme/lib", "1.0.0"));

        assertThatThrownBy(() -> hosted.publish(repo, upload(second, "acme/lib", "v1.0.0")))
                .isInstanceOfSatisfying(ComposerError.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("1.0.0").contains("already exists");
                });

        repo.setAllowRedeploy(true);
        ComposerHosted.Published replaced = hosted.publish(repo, upload(second, "acme/lib", "1.0.0"));
        assertThat(replaced.replaced()).isTrue();
        assertThat(storage.has(repo, original.distPath())).isFalse();
        assertThat(storage.has(repo, replaced.distPath())).isTrue();
        P2Document doc = hosted.document(repo, "acme/lib", false).orElseThrow();
        assertThat(doc.versions()).hasSize(1);
        assertThat(doc.versions().getFirst().get("homepage").stringValue()).isEqualTo("https://new");
    }

    @Test
    void rejectsMismatchedNamesAndVersions() {
        byte[] zip = ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", "1.0.0", null));
        assertBadRequest(() -> hosted.publish(repo, upload(zip, "acme/other", null)), "acme/other");
        assertBadRequest(() -> hosted.publish(repo, upload(zip, "acme/lib", "2.0.0")), "does not match");

        byte[] noVersion = ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, null));
        assertBadRequest(() -> hosted.publish(repo, upload(noVersion, "acme/lib", null)), "No version");
        assertBadRequest(() -> hosted.publish(repo, upload(noVersion, "acme/lib", "1.0.*")), "not a valid Composer version");
        assertBadRequest(() -> hosted.publish(repo, upload(noVersion, "acme/lib", "dev-100%")), "URL path");
        assertBadRequest(() -> hosted.publish(repo, upload(noVersion, "acme/lib", "dev-fix;quote")), "URL path");
        // constraint syntax that VersionParser::normalize would strip silently
        assertBadRequest(() -> hosted.publish(repo, upload(noVersion, "acme/lib", "1.0.0 as 2.0.0")), "inline aliases");
        assertBadRequest(() -> hosted.publish(repo, upload(noVersion, "acme/lib", "1.0.0@beta")), "stability flags");
        assertBadRequest(() -> hosted.publish(repo, upload(noVersion, "acme/lib", "dev-main@dev")), "stability flags");
        byte[] aliasInJson = ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", "1.0.0 as 2.0.0", null));
        assertBadRequest(() -> hosted.publish(repo, upload(aliasInJson, "acme/lib", null)), "inline aliases");

        byte[] noName = ComposerTestSupport.rootZip(ComposerTestSupport.composerJson(null, "1.0.0", null));
        assertBadRequest(() -> hosted.publish(repo, upload(noName, null, null)), "no name");

        byte[] badName = ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("Not A Name", "1.0.0", null));
        assertBadRequest(() -> hosted.publish(repo, upload(badName, null, null)), "invalid name");
        assertThat(storage.paths(repo)).isEmpty();
    }

    @Test
    void acceptsAMissingNameWhenTheUrlGivesOne() throws IOException {
        byte[] zip = ComposerTestSupport.rootZip(ComposerTestSupport.composerJson(null, null, null));
        assertThat(hosted.publish(repo, upload(zip, "acme/lib", "1.0.0")).name()).isEqualTo("acme/lib");
    }

    @Test
    void rejectsMetadataComposerCannotLoad() {
        assertBadRequest(() -> hosted.publish(repo, upload(ComposerTestSupport.rootZip(
                "{\"name\":\"acme/lib\",\"require\":[\"php\"]}"), null, "1.0.0")), "\"require\"");
        assertBadRequest(() -> hosted.publish(repo, upload(ComposerTestSupport.rootZip(
                "{\"name\":\"acme/lib\",\"require\":{\"php\":8}}"), null, "1.0.0")), "require");
        assertBadRequest(() -> hosted.publish(repo, upload(ComposerTestSupport.rootZip(
                "{\"name\":\"acme/lib\",\"autoload\":\"src\"}"), null, "1.0.0")), "autoload");
        assertBadRequest(() -> hosted.publish(repo, upload(ComposerTestSupport.rootZip(
                "{\"name\":\"acme/lib\",\"license\":{\"x\":1}}"), null, "1.0.0")), "license");
        assertBadRequest(() -> hosted.publish(repo, upload(ComposerTestSupport.rootZip(
                "{\"name\":\"acme/lib\",\"keywords\":\"a,b\"}"), null, "1.0.0")), "keywords");
        assertBadRequest(() -> hosted.publish(repo, upload(ComposerTestSupport.rootZip(
                "{\"name\":\"acme/lib\",\"description\":[\"x\"]}"), null, "1.0.0")), "description");
        assertBadRequest(() -> hosted.publish(repo, upload(ComposerTestSupport.rootZip(
                "{\"name\":\"acme/lib\",\"authors\":\"me\"}"), null, "1.0.0")), "authors");
    }

    @Test
    void acceptsPhpStyleEmptyMaps() throws IOException {
        byte[] zip = ComposerTestSupport.rootZip("{\"name\":\"acme/lib\",\"require\":[],\"autoload\":[],\"license\":[\"MIT\"]}");
        assertThat(hosted.publish(repo, upload(zip, null, "1.0.0")).version()).isEqualTo("1.0.0");
    }

    @Test
    void doesNotCopyRootOnlyKeys() throws IOException {
        byte[] zip = ComposerTestSupport.rootZip("""
                {"name":"acme/lib","repositories":[{"type":"vcs","url":"x"}],"config":{"secure-http":false},
                 "minimum-stability":"dev","prefer-stable":true,"dist":{"url":"https://evil"},"source":{"url":"x"},
                 "notification-url":"https://elsewhere","time":"2020-01-01T00:00:00+00:00","extra":{"a":1}}
                """);
        hosted.publish(repo, upload(zip, null, "1.0.0"));
        ObjectNode entry = hosted.document(repo, "acme/lib", false).orElseThrow().versions().getFirst();
        assertThat(entry.has("repositories")).isFalse();
        assertThat(entry.has("config")).isFalse();
        assertThat(entry.has("minimum-stability")).isFalse();
        assertThat(entry.has("prefer-stable")).isFalse();
        assertThat(entry.has("source")).isFalse();
        assertThat(entry.has("notification-url")).isFalse();
        assertThat(entry.get("dist").get("url").stringValue()).startsWith("dist/acme/lib/1.0.0/");
        assertThat(entry.get("time").stringValue()).isEqualTo("2020-01-01T00:00:00+00:00");
        assertThat(entry.get("extra").get("a").intValue()).isEqualTo(1);
    }

    @Test
    void deletesOneVersion() throws IOException {
        ComposerHosted.Published one = hosted.publish(repo, upload(ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, null)), "acme/lib", "1.0.0"));
        hosted.publish(repo, upload(ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, "\"homepage\": \"x\"")), "acme/lib", "1.1.0"));

        assertThat(hosted.deleteVersion(repo, "acme/lib", "v1.0.0")).isTrue();

        assertThat(storage.has(repo, one.distPath())).isFalse();
        assertThat(hosted.document(repo, "acme/lib", false).orElseThrow().versions()).extracting(P2Document::version).containsExactly("1.1.0");
        assertThat(hosted.deleteVersion(repo, "acme/lib", "1.0.0")).isFalse();
        assertThat(hosted.deleteVersion(repo, "acme/missing", "1.0.0")).isFalse();
    }

    @Test
    void deletingTheLastVersionRemovesTheDocument() throws IOException {
        hosted.publish(repo, upload(ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, null)), "acme/lib", "dev-main"));
        assertThat(hosted.deleteVersion(repo, "acme/lib", "dev-main")).isTrue();
        assertThat(storage.paths(repo)).isEmpty();
        assertThat(hosted.packageNames(repo)).isEmpty();
    }

    @Test
    void deletesAWholePackage() throws IOException {
        hosted.publish(repo, upload(ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, null)), "acme/lib", "1.0.0"));
        hosted.publish(repo, upload(ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, null)), "acme/lib", "dev-main"));
        hosted.publish(repo, upload(ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/other", null, null)), "acme/other", "1.0.0"));

        assertThat(hosted.deletePackage(repo, "acme/lib")).isEqualTo(4);

        assertThat(hosted.packageNames(repo)).containsExactly("acme/other");
        assertThat(hosted.deletePackage(repo, "acme/lib")).isZero();
    }

    @Test
    void versionsWhoseArchiveVanishedAreDroppedAndTheDocumentRepaired() throws IOException {
        ComposerHosted.Published one = hosted.publish(repo, upload(ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, null)), "acme/lib", "1.0.0"));
        hosted.publish(repo, upload(ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, "\"homepage\": \"x\"")), "acme/lib", "2.0.0"));
        storage.remove(repo, one.distPath()); // deleted through the web UI

        assertThat(hosted.document(repo, "acme/lib", false).orElseThrow().versions()).extracting(P2Document::version).containsExactly("2.0.0");
        assertThat(hosted.stored(repo, "acme/lib", false).orElseThrow().versions()).hasSize(1);
    }

    @Test
    void aPackageWithoutArchivesHasNoDocument() throws IOException {
        ComposerHosted.Published one = hosted.publish(repo, upload(ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, null)), "acme/lib", "1.0.0"));
        storage.remove(repo, one.distPath());
        assertThat(hosted.document(repo, "acme/lib", false)).isEmpty();
        assertThat(storage.has(repo, "p2/acme/lib.json")).isFalse();
    }

    @Test
    void sumsDownloadsOverVersions() throws IOException {
        ComposerHosted.Published one = hosted.publish(repo, upload(ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, null)), "acme/lib", "1.0.0"));
        ComposerHosted.Published two = hosted.publish(repo, upload(ComposerTestSupport.rootZip(ComposerTestSupport.composerJson("acme/lib", null, "\"homepage\": \"x\"")), "acme/lib", "2.0.0"));
        storage.find(repo, one.distPath()).orElseThrow().asset().setDownloadCount(3);
        storage.find(repo, two.distPath()).orElseThrow().asset().setDownloadCount(4);
        assertThat(hosted.downloads(repo, "acme/lib")).isEqualTo(7);
    }

    @Test
    void entryKeepsAnExplicitTimeAndStampsOneOtherwise() {
        ObjectNode json = ComposerTestSupport.object("{\"name\":\"acme/lib\"}");
        ObjectNode entry = ComposerHosted.entry(json, "acme/lib", "1.0.0", "1.0.0.0", "abc", "dist/acme/lib/1.0.0/abc.zip",
                Instant.parse("2026-03-04T05:06:07Z"));
        assertThat(entry.get("time").stringValue()).isEqualTo("2026-03-04T05:06:07+00:00");
    }

    private void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messagePart) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ComposerError.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).contains(messagePart);
        });
    }

    private ComposerHosted.Upload upload(byte[] zip, String name, String version) throws IOException {
        Path file = Files.createTempFile(dir, "upload", ".zip");
        Files.write(file, zip);
        return new ComposerHosted.Upload(file, ComposerTestSupport.sha1(zip), name, version);
    }
}
