package dev.granary.format.hex;

import static dev.granary.format.hex.HexTestSupport.admin;
import static dev.granary.format.hex.HexTestSupport.goldenKey;
import static dev.granary.format.hex.HexTestSupport.hosted;
import static dev.granary.format.hex.HexTestSupport.metadata;
import static dev.granary.format.hex.HexTestSupport.release;
import static dev.granary.format.hex.HexTestSupport.user;
import static dev.granary.format.hex.HexTestSupport.withSetting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.model.Repository;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class HexHostedTest {

    @TempDir
    Path tempDir;

    private HexFixture fx;
    private HexHosted hosted;
    private final Repository repo = hosted("hex-hosted");

    @BeforeEach
    void setUp() {
        fx = new HexFixture(goldenKey(tempDir));
        hosted = fx.hosted;
    }

    private <T> T document(HexPath.Kind kind, String name, java.util.function.Function<byte[], T> decoder) {
        StoredContent stored = hosted.registryDocument(repo, kind, name).orElseThrow();
        try {
            HexRegistry.Signed signed = HexRegistry.decodeSigned(HexRegistry.gunzip(stored.readAllBytes()));
            assertThat(fx.key.verify(signed.payload(), signed.signature())).as("signed with Granary's key").isTrue();
            return decoder.apply(signed.payload());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HexRegistry.Package pkg(String name) {
        return document(HexPath.Kind.PACKAGE, name, HexRegistry::decodePackage);
    }

    private HexRegistry.Versions versions() {
        return document(HexPath.Kind.VERSIONS, null, HexRegistry::decodeVersions);
    }

    private HexRegistry.Names names() {
        return document(HexPath.Kind.NAMES, null, HexRegistry::decodeNames);
    }

    private static HexError hexError(Runnable action) {
        try {
            action.run();
        } catch (HexError e) {
            return e;
        }
        throw new AssertionError("expected a HexError");
    }

    @Test
    void publishStoresTheTarballAndSignedDocuments() {
        byte[] tarball = release(metadata("acme", "1.0.0", "Acme ✓", "jason", "~> 1.4", "false", "telemetry", "~> 1.0", "true"));
        HexHosted.Published published = hosted.publish(repo, tarball, "acme", false, user("alice"));

        assertThat(published.replaced()).isFalse();
        assertThat(published.release().publisher()).isEqualTo("alice");
        assertThat(published.release().size()).isEqualTo(tarball.length);
        assertThat(fx.storage.paths(repo)).containsExactlyInAnyOrder("tarballs/acme-1.0.0.tar", ".hex/packages/acme.json",
                "packages/acme", "names", "versions");
        assertThat(fx.storage.bytes(repo, "tarballs/acme-1.0.0.tar")).isEqualTo(tarball);
        assertThat(fx.storage.coordinates.values()).extracting(c -> c.name() + " " + c.version()).contains("acme 1.0.0");

        HexRegistry.Package pkg = pkg("acme");
        assertThat(pkg.name()).isEqualTo("acme");
        assertThat(pkg.repository()).isEqualTo("hex-hosted");
        HexRegistry.Release release = pkg.release("1.0.0").orElseThrow();
        HexTarball parsed = HexTarball.read(tarball);
        assertThat(release.innerChecksum()).isEqualTo(parsed.innerChecksum());
        assertThat(release.outerChecksum()).isEqualTo(parsed.outerChecksum());
        assertThat(release.publishedAt()).isNotNull();
        assertThat(release.dependencies()).containsExactly(
                new HexRegistry.Dependency("jason", "~> 1.4", false, "", "hexpm"),
                new HexRegistry.Dependency("telemetry", "~> 1.0", true, "", "hexpm"));

        assertThat(versions()).isEqualTo(new HexRegistry.Versions("hex-hosted",
                List.of(new HexRegistry.VersionsEntry("acme", List.of("1.0.0"), List.of()))));
        assertThat(names().names()).containsExactly("acme");
        assertThat(names().packages().getFirst().updatedAt()).isNotNull();
    }

    @Test
    void theConfiguredRepositoryNameIsSignedIntoEveryDocumentAndOwnDependenciesLoseTheirRepository() {
        withSetting(repo, HexSettings.REPOSITORY_NAME, "hexpm");
        fx.publish(repo, "acme", "1.0.0");
        assertThat(pkg("acme").repository()).isEqualTo("hexpm");
        assertThat(pkg("acme").release("1.0.0").orElseThrow().dependencies()).isEmpty();
        assertThat(versions().repository()).isEqualTo("hexpm");
        assertThat(names().repository()).isEqualTo("hexpm");

        hosted.publish(repo, release(metadata("acme_web", "1.0.0", "d", "jason", "~> 1.0", "false")), null, false, user("a"));
        assertThat(pkg("acme_web").release("1.0.0").orElseThrow().dependencies())
                .containsExactly(new HexRegistry.Dependency("jason", "~> 1.0", false, "", "")); // same repository: implicit
    }

    @Test
    void severalPackagesAndVersionsAreIndexedInOrder() {
        fx.publish(repo, "zeta", "1.0.0");
        fx.publish(repo, "acme", "1.10.0");
        fx.publish(repo, "acme", "1.2.0");
        fx.publish(repo, "acme", "1.2.0-rc.1");
        assertThat(names().names()).containsExactly("acme", "zeta");
        assertThat(versions().packages().getFirst().versions()).containsExactly("1.2.0-rc.1", "1.2.0", "1.10.0");
        assertThat(pkg("acme").releases()).extracting(HexRegistry.Release::version)
                .containsExactly("1.2.0-rc.1", "1.2.0", "1.10.0");
        assertThat(hosted.states(repo)).extracting(HexPackageState::name).containsExactly("acme", "zeta");
    }

    @Test
    void republishingIsRefusedUnlessReplaceIsAskedAndAllowed() {
        fx.publish(repo, "acme", "1.0.0");
        String insertedAt = hosted.state(repo, "acme").orElseThrow().release("1.0.0").orElseThrow().insertedAt();

        HexError noFlag = hexError(() -> fx.publish(repo, "acme", "1.0.0"));
        assertThat(noFlag.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(noFlag.errors()).containsEntry("inserted_at", "must include the --replace flag to update an existing release");

        HexError notAllowed = hexError(() -> hosted.publish(repo, release("acme", "1.0.0"), null, true, user("a")));
        assertThat(notAllowed.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(notAllowed.errors().get("inserted_at")).contains("allowRedeploy is off");

        repo.setAllowRedeploy(true);
        hosted.retire(repo, "acme", "1.0.0", "deprecated", "old");
        byte[] changed = release(metadata("acme", "1.0.0", "a different description"));
        HexHosted.Published replaced = hosted.publish(repo, changed, "acme", true, user("bob"));
        assertThat(replaced.replaced()).isTrue();
        assertThat(replaced.release().insertedAt()).isEqualTo(insertedAt);
        assertThat(replaced.release().publisher()).isEqualTo("bob");
        assertThat(replaced.release().retirement()).isEqualTo(new HexPackageState.Retirement("deprecated", "old"));
        assertThat(fx.storage.bytes(repo, "tarballs/acme-1.0.0.tar")).isEqualTo(changed);
        assertThat(pkg("acme").release("1.0.0").orElseThrow().outerChecksum()).isEqualTo(HexTarball.sha256(changed));
    }

    @Test
    void aTarballTheStateDoesNotKnowStillCountsAsExisting() {
        fx.storage.put(repo, "tarballs/acme-1.0.0.tar", release("acme", "1.0.0"), "application/octet-stream", false);
        HexError error = hexError(() -> fx.publish(repo, "acme", "1.0.0"));
        assertThat(error.errors()).containsKey("inserted_at");
    }

    @Test
    void refusedPublishesLeaveNothingBehind() {
        assertThat(hexError(() -> hosted.publish(repo, new byte[0], null, false, user("a"))).errors().get("tar"))
                .contains("empty");
        assertThat(hexError(() -> hosted.publish(repo, "garbage".getBytes(), null, false, user("a"))).errors())
                .containsKey("tar");
        assertThat(hexError(() -> hosted.publish(repo, release("acme", "1.0.0"), "other", false, user("a"))).errors().get("name"))
                .contains("the tarball is acme, but the URL names other");
        assertThat(fx.storage.paths(repo)).isEmpty();
    }

    @Test
    void hostileMetadataIsAValidationErrorNotAServerError() {
        for (String literal : List.of("99999999999#1", "\"\\x{FFFFFFFF}\"", "\"\\x{110000}\"", "<<1114112/utf8>>",
                "<<$\\x{110000}/utf8>>")) {
            String metadata = HexTestSupport.metadata("acme", "1.0.0", "x") + "{<<\"extra\">>, " + literal + "}.\n";
            HexError error = hexError(() -> hosted.publish(repo, release(metadata), null, false, user("a")));
            assertThat(error.status().value()).as(literal).isEqualTo(422);
            assertThat(error.errors().get("tar")).as(literal).contains("metadata.config");
        }
        assertThat(fx.storage.paths(repo)).isEmpty();
    }

    @Test
    void anonymousPublishersAreNotRecorded() {
        HexHosted.Published p = hosted.publish(repo, release("acme", "1.0.0"), null, false, HexTestSupport.anonymous());
        assertThat(p.release().publisher()).isNull();
    }

    @Test
    void revertRemovesTheReleaseWithinTheWindow() {
        fx.publish(repo, "acme", "1.0.0");
        fx.publish(repo, "acme", "1.1.0");
        hosted.publishDocs(repo, "acme", "1.1.0", HexTestSupport.docsTarball());
        hosted.revert(repo, "acme", "1.1.0", user("alice"));
        assertThat(fx.storage.paths(repo)).doesNotContain("tarballs/acme-1.1.0.tar", "docs/acme-1.1.0.tar.gz");
        assertThat(pkg("acme").releases()).extracting(HexRegistry.Release::version).containsExactly("1.0.0");
        assertThat(versions().packages().getFirst().versions()).containsExactly("1.0.0");

        hosted.revert(repo, "acme", "1.0.0", user("alice"));
        assertThat(hosted.registryDocument(repo, HexPath.Kind.PACKAGE, "acme")).isEmpty();
        assertThat(names().packages()).isEmpty();
        assertThat(versions().packages()).isEmpty();
        assertThat(fx.storage.paths(repo)).containsExactlyInAnyOrder("names", "versions");
    }

    @Test
    void revertIsRefusedAfterTheWindowExceptForAdministrators() {
        withSetting(repo, HexSettings.REVERT_WINDOW, "1");
        fx.publish(repo, "acme", "1.0.0");
        HexPackageState state = hosted.state(repo, "acme").orElseThrow();
        HexPackageState.Release old = state.release("1.0.0").orElseThrow();
        HexPackageState.Release aged = new HexPackageState.Release(old.version(), old.innerChecksum(), old.outerChecksum(),
                Instant.now().minusSeconds(3600).toString(), old.updatedAt(), old.publisher(), old.dependencies(),
                old.retirement(), old.meta(), old.hasDocs(), old.size());
        fx.storage.put(repo, HexPackageState.path("acme"), state.with(aged, Instant.now()).toJson(), "application/json", true);

        HexError error = hexError(() -> hosted.revert(repo, "acme", "1.0.0", user("alice")));
        assertThat(error.errors().get("inserted_at")).contains("up to 1 minutes");

        withSetting(repo, HexSettings.REVERT_WINDOW, "0");
        hosted.revert(repo, "acme", "1.0.0", user("alice")); // 0: no limit
        assertThat(hosted.state(repo, "acme")).isEmpty();

        withSetting(repo, HexSettings.REVERT_WINDOW, "1");
        fx.publish(repo, "acme", "2.0.0");
        HexPackageState s2 = hosted.state(repo, "acme").orElseThrow();
        HexPackageState.Release r2 = s2.release("2.0.0").orElseThrow();
        fx.storage.put(repo, HexPackageState.path("acme"), s2.with(new HexPackageState.Release(r2.version(), r2.innerChecksum(),
                r2.outerChecksum(), "2000-01-01T00:00:00Z", r2.updatedAt(), null, r2.dependencies(), null, r2.meta(), false,
                r2.size()), Instant.now()).toJson(), "application/json", true);
        hosted.revert(repo, "acme", "2.0.0", admin("root"));
        assertThat(hosted.state(repo, "acme")).isEmpty();
    }

    @Test
    void revertOfWhatDoesNotExistIsNotFound() {
        assertThat(hexError(() -> hosted.revert(repo, "nope", "1.0.0", user("a"))).status()).isEqualTo(HttpStatus.NOT_FOUND);
        fx.publish(repo, "acme", "1.0.0");
        assertThat(hexError(() -> hosted.revert(repo, "acme", "9.9.9", user("a"))).getMessage())
                .isEqualTo("release acme 9.9.9 does not exist");
    }

    @Test
    void retireAndUnretire() {
        fx.publish(repo, "acme", "1.0.0");
        fx.publish(repo, "acme", "1.1.0");
        hosted.retire(repo, "acme", "1.0.0", "security", "  CVE-2024-1  ");
        HexRegistry.Release retired = pkg("acme").release("1.0.0").orElseThrow();
        assertThat(retired.retired()).isEqualTo(new HexRegistry.Retirement(HexRegistry.RetirementReason.SECURITY, "CVE-2024-1"));
        assertThat(versions().packages().getFirst().retired()).containsExactly(0);

        hosted.retire(repo, "acme", "1.1.0", "RETIRED_RENAMED", null);
        assertThat(pkg("acme").release("1.1.0").orElseThrow().retired())
                .isEqualTo(new HexRegistry.Retirement(HexRegistry.RetirementReason.RENAMED, ""));
        assertThat(versions().packages().getFirst().retired()).containsExactly(0, 1);

        hosted.unretire(repo, "acme", "1.0.0");
        assertThat(pkg("acme").release("1.0.0").orElseThrow().isRetired()).isFalse();
        assertThat(versions().packages().getFirst().retired()).containsExactly(1);

        assertThat(hexError(() -> hosted.retire(repo, "acme", "1.0.0", "bored", null)).errors()).containsKey("reason");
        assertThat(hexError(() -> hosted.retire(repo, "acme", "1.0.0", "other", "✓".repeat(141))).errors()).containsKey("message");
        hosted.retire(repo, "acme", "1.0.0", "other", "✓".repeat(140)); // counted in characters, not bytes
        assertThat(hexError(() -> hosted.retire(repo, "acme", "3.0.0", "other", null)).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void documentation() {
        fx.publish(repo, "acme", "1.0.0");
        byte[] docs = HexTestSupport.docsTarball();
        assertThat(hosted.publishDocs(repo, "acme", "1.0.0", docs).hasDocs()).isTrue();
        assertThat(fx.storage.bytes(repo, "docs/acme-1.0.0.tar.gz")).isEqualTo(docs);
        assertThat(hosted.state(repo, "acme").orElseThrow().release("1.0.0").orElseThrow().hasDocs()).isTrue();
        assertThat(hosted.docs(repo, "acme", "1.0.0")).isPresent();

        assertThat(hosted.deleteDocs(repo, "acme", "1.0.0").hasDocs()).isFalse();
        assertThat(hosted.docs(repo, "acme", "1.0.0")).isEmpty();
        assertThat(hexError(() -> hosted.deleteDocs(repo, "acme", "1.0.0")).status()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(hexError(() -> hosted.publishDocs(repo, "acme", "1.0.0", new byte[0])).errors().get("tar")).contains("empty");
        assertThat(hexError(() -> hosted.publishDocs(repo, "acme", "1.0.0", "<html>".getBytes())).errors().get("tar"))
                .contains("gzip");
        assertThat(hexError(() -> hosted.publishDocs(repo, "acme", "2.0.0", docs)).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void documentsHealWhenATarballDisappears() {
        fx.publish(repo, "acme", "1.0.0");
        fx.publish(repo, "acme", "1.1.0");
        fx.storage.remove(repo, "tarballs/acme-1.1.0.tar"); // deleted from the component browser, say
        assertThat(pkg("acme").releases()).extracting(HexRegistry.Release::version).containsExactly("1.0.0");
        assertThat(versions().packages().getFirst().versions()).containsExactly("1.0.0");
        assertThat(hosted.state(repo, "acme").orElseThrow().versions()).containsExactly("1.0.0");

        fx.storage.remove(repo, "tarballs/acme-1.0.0.tar");
        assertThat(versions().packages()).isEmpty();
        assertThat(names().packages()).isEmpty();
        assertThat(hosted.registryDocument(repo, HexPath.Kind.PACKAGE, "acme")).isEmpty();
    }

    @Test
    void documentsHealWhenTheStateIsLost() {
        fx.publish(repo, "acme", "1.0.0");
        fx.storage.remove(repo, ".hex/packages/acme.json");
        fx.storage.remove(repo, "packages/acme");
        fx.storage.remove(repo, "names");
        assertThat(names().names()).containsExactly("acme");
        assertThat(pkg("acme").release("1.0.0")).isPresent();
        assertThat(hosted.state(repo, "acme")).isPresent();
    }

    @Test
    void documentsAreResignedWhenTheKeyOrNameChanges() throws IOException {
        fx.publish(repo, "acme", "1.0.0");
        HexSigningKey otherKey = new HexSigningKey(tempDir.resolve("other"));
        HexHosted reSigner = new HexHosted(fx.storage.mock, otherKey); // same storage, another key
        StoredContent doc = reSigner.registryDocument(repo, HexPath.Kind.PACKAGE, "acme").orElseThrow();
        HexRegistry.Signed signed = HexRegistry.decodeSigned(HexRegistry.gunzip(doc.readAllBytes()));
        assertThat(otherKey.verify(signed.payload(), signed.signature())).isTrue();

        withSetting(repo, HexSettings.REPOSITORY_NAME, "renamed");
        StoredContent names = reSigner.registryDocument(repo, HexPath.Kind.NAMES, null).orElseThrow();
        assertThat(HexRegistry.unpack(names.readAllBytes(), HexRegistry::decodeNames).repository()).isEqualTo("renamed");
    }

    @Test
    void anUnknownPackageIsAnsweredWithoutWritingAnything() {
        fx.publish(repo, "acme", "1.0.0");
        int before = fx.storage.stores;
        assertThat(hosted.registryDocument(repo, HexPath.Kind.PACKAGE, "jason")).isEmpty();
        assertThat(fx.storage.stores).isEqualTo(before);
        assertThat(hosted.registryDocument(repo, HexPath.Kind.PACKAGE, "acme")).isPresent();
        assertThat(fx.storage.stores).isEqualTo(before); // current documents are served as stored
    }

    @Test
    void anEmptyRepositoryStillHasNamesAndVersions() {
        assertThat(names()).isEqualTo(new HexRegistry.Names("hex-hosted", List.of()));
        assertThat(versions()).isEqualTo(new HexRegistry.Versions("hex-hosted", List.of()));
    }

    @Test
    void unreadableStoredFilesAreIgnoredAndHealed() {
        fx.publish(repo, "acme", "1.0.0");
        fx.storage.put(repo, ".hex/packages/acme.json", "{broken".getBytes(), "application/json", true);
        fx.storage.put(repo, "packages/acme", "not gzip".getBytes(), "application/octet-stream", true);
        assertThat(hosted.state(repo, "acme")).isEmpty();
        assertThat(pkg("acme").release("1.0.0")).isPresent(); // rebuilt from the tarball
        assertThat(hosted.state(repo, "acme")).isPresent();
    }

    @Test
    void notHostedMessages() {
        Repository group = HexTestSupport.group("hex-group");
        assertThat(HexHosted.notHosted(group, java.util.Optional.of("hex-hosted")).getMessage())
                .isEqualTo("'hex-group' is a group repository and does not accept writes; publish to the hosted "
                        + "repository 'hex-hosted' instead");
        assertThat(HexHosted.notHosted(group, java.util.Optional.empty()).status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
