package dev.granary.format.npm;

import static dev.granary.format.npm.NpmTestSupport.MAPPER;
import static dev.granary.format.npm.NpmTestSupport.baseUrl;
import static dev.granary.format.npm.NpmTestSupport.bytes;
import static dev.granary.format.npm.NpmTestSupport.hosted;
import static dev.granary.format.npm.NpmTestSupport.json;
import static dev.granary.format.npm.NpmTestSupport.publishDocument;
import static dev.granary.format.npm.NpmTestSupport.publishRequest;
import static dev.granary.format.npm.NpmTestSupport.str;
import static dev.granary.format.npm.NpmTestSupport.tarball;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.repository.GroupResolver;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.node.ObjectNode;

class NpmHostedWriterTest {

    private final FakeStorage storage = new FakeStorage();
    private final NpmPackuments packuments = new NpmPackuments(storage.mock, mock(ProxyService.class),
            mock(UpstreamClient.class), mock(GroupResolver.class), MAPPER);
    private final NpmHostedWriter writer = new NpmHostedWriter(storage.mock, packuments);
    private final Repository repo = hosted("npm-hosted");

    @Test
    void publishStoresTarballAndPackument() {
        byte[] tgz = tarball("demo-1.0.0");
        ObjectNode doc = writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.0.0", tgz, "latest"));

        assertThat(storage.paths(repo)).containsExactly("demo", "demo/-/demo-1.0.0.tgz");
        assertThat(storage.bytes(repo, "demo/-/demo-1.0.0.tgz")).isEqualTo(tgz);
        assertThat(storage.coordinates.values()).singleElement().satisfies(c -> {
            assertThat(c.namespace()).isEmpty();
            assertThat(c.name()).isEqualTo("demo");
            assertThat(c.version()).isEqualTo("1.0.0");
        });

        ObjectNode stored = json(storage.text(repo, "demo"));
        assertThat(stored).isEqualTo(doc);
        assertThat(str(stored, "_id")).isEqualTo("demo");
        assertThat(str(stored, "name")).isEqualTo("demo");
        assertThat(str(stored, "_rev")).startsWith("1-");
        assertThat(str(stored, "description")).isEqualTo("description of demo@1.0.0");
        assertThat(str(stored, "readme")).isEqualTo("# demo 1.0.0");
        assertThat(str(stored, "dist-tags", "latest")).isEqualTo("1.0.0");
        assertThat(str(stored, "versions", "1.0.0", "dist", "tarball")).isEqualTo(baseUrl(repo) + "/demo/-/demo-1.0.0.tgz");
        assertThat(str(stored, "versions", "1.0.0", "dist", "shasum")).isEqualTo(NpmIntegrity.shasum(tgz));
        assertThat(str(stored, "versions", "1.0.0", "dist", "integrity")).isEqualTo(NpmIntegrity.integrity(tgz));
        assertThat(str(stored, "versions", "1.0.0", "_id")).isEqualTo("demo@1.0.0");
        assertThat(str(stored, "time", "created")).isNotNull();
        assertThat(str(stored, "time", "modified")).isNotNull();
        assertThat(str(stored, "time", "1.0.0")).isNotNull();
        assertThat(stored.has("_attachments")).isFalse();
        assertThat(stored.has("access")).isFalse();
    }

    @Test
    void publishComputesDigestsTheClientLeftOut() {
        byte[] tgz = tarball("demo-1.0.0");
        ObjectNode document = publishDocument("demo", "1.0.0", tgz, "latest");
        ((ObjectNode) document.path("versions").path("1.0.0")).remove("dist");
        writer.publish(repo, baseUrl(repo), NpmPublishRequest.parse(MAPPER, bytes(document)));

        ObjectNode stored = json(storage.text(repo, "demo"));
        assertThat(str(stored, "versions", "1.0.0", "dist", "shasum")).isEqualTo(NpmIntegrity.shasum(tgz));
        assertThat(str(stored, "versions", "1.0.0", "dist", "integrity")).isEqualTo(NpmIntegrity.integrity(tgz));
        assertThat(str(stored, "versions", "1.0.0", "dist", "tarball")).isEqualTo(baseUrl(repo) + "/demo/-/demo-1.0.0.tgz");
    }

    @Test
    void scopedPublishUsesNpmAttachmentNamingAndScopeAsNamespace() {
        byte[] tgz = tarball("@acme/ui-1.0.0");
        writer.publish(repo, baseUrl(repo), publishRequest("@acme/ui", "1.0.0", tgz, "latest"));

        assertThat(storage.paths(repo)).containsExactly("@acme/ui", "@acme/ui/-/ui-1.0.0.tgz");
        assertThat(storage.bytes(repo, "@acme/ui/-/ui-1.0.0.tgz")).isEqualTo(tgz);
        assertThat(storage.coordinates.values()).singleElement().satisfies(c -> {
            assertThat(c.namespace()).isEqualTo("acme");
            assertThat(c.name()).isEqualTo("ui");
            assertThat(c.version()).isEqualTo("1.0.0");
        });
        ObjectNode stored = json(storage.text(repo, "@acme/ui"));
        assertThat(str(stored, "versions", "1.0.0", "dist", "tarball")).isEqualTo(baseUrl(repo) + "/@acme/ui/-/ui-1.0.0.tgz");
    }

    @Test
    void republishingAnExistingVersionIsAConflictThatChangesNothing() {
        byte[] original = tarball("v1");
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.0.0", original, "latest"));
        String revBefore = str(json(storage.text(repo, "demo")), "_rev");

        assertError(() -> writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.0.0", tarball("v1-again"), "latest")),
                HttpStatus.CONFLICT, NpmHostedWriter.VERSION_EXISTS);

        assertThat(storage.bytes(repo, "demo/-/demo-1.0.0.tgz")).isEqualTo(original);
        assertThat(str(json(storage.text(repo, "demo")), "_rev")).isEqualTo(revBefore);
    }

    @Test
    void republishingIsAllowedWhenTheRepositorySaysSo() {
        repo.setAllowRedeploy(true);
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.0.0", tarball("v1"), "latest"));
        byte[] replacement = tarball("v1-again");
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.0.0", replacement, "latest"));

        assertThat(storage.bytes(repo, "demo/-/demo-1.0.0.tgz")).isEqualTo(replacement);
        assertThat(str(json(storage.text(repo, "demo")), "versions", "1.0.0", "dist", "shasum"))
                .isEqualTo(NpmIntegrity.shasum(replacement));
        assertThat(str(json(storage.text(repo, "demo")), "_rev")).startsWith("2-");
    }

    @Test
    void aLyingIntegrityIsRejectedBeforeAnythingIsStored() {
        ObjectNode document = publishDocument("demo", "1.0.0", tarball("real"), "latest");
        ((ObjectNode) document.path("versions").path("1.0.0").path("dist")).put("integrity", NpmIntegrity.integrity(tarball("other")));
        assertError(() -> writer.publish(repo, baseUrl(repo), NpmPublishRequest.parse(MAPPER, bytes(document))),
                HttpStatus.BAD_REQUEST, "integrity");
        assertThat(storage.paths(repo)).isEmpty();

        ObjectNode shasum = publishDocument("demo", "1.0.0", tarball("real"), "latest");
        ((ObjectNode) shasum.path("versions").path("1.0.0").path("dist")).put("shasum", "0000000000000000000000000000000000000000");
        assertError(() -> writer.publish(repo, baseUrl(repo), NpmPublishRequest.parse(MAPPER, bytes(shasum))),
                HttpStatus.BAD_REQUEST, "shasum");
        assertThat(storage.paths(repo)).isEmpty();
    }

    @Test
    void aVersionWithoutItsTarballIsRejected() {
        ObjectNode document = publishDocument("demo", "1.0.0", tarball("x"), "latest");
        ObjectNode second = (ObjectNode) document.path("versions").path("1.0.0").deepCopy();
        second.put("version", "1.1.0");
        second.remove("dist"); // its tarball would be demo-1.1.0.tgz, which is not attached
        ((ObjectNode) document.path("versions")).set("1.1.0", second);
        assertError(() -> writer.publish(repo, baseUrl(repo), NpmPublishRequest.parse(MAPPER, bytes(document))),
                HttpStatus.BAD_REQUEST, "no tarball attached for version 1.1.0");
        assertThat(storage.paths(repo)).isEmpty();
    }

    @Test
    void metadataOnlyPutOnAnUnknownPackageIsAnError() {
        ObjectNode document = publishDocument("demo", "1.0.0", tarball("x"), "latest");
        document.remove("_attachments");
        assertError(() -> writer.publish(repo, baseUrl(repo), NpmPublishRequest.parse(MAPPER, bytes(document))),
                HttpStatus.BAD_REQUEST, "no tarball attached");
        assertError(() -> writer.publish(repo, baseUrl(repo), NpmPublishRequest.parse(MAPPER, "{\"name\":\"demo\"}".getBytes())),
                HttpStatus.NOT_FOUND, "does not exist");
    }

    @Test
    void laterVersionsMoveLatestAndTheDescriptionAlongOnlyWhenTheyAreLatest() {
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.0.0", tarball("1"), "latest"));
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.1.0", tarball("2"), "latest"));
        ObjectNode afterSecond = json(storage.text(repo, "demo"));
        assertThat(str(afterSecond, "dist-tags", "latest")).isEqualTo("1.1.0");
        assertThat(str(afterSecond, "description")).isEqualTo("description of demo@1.1.0");
        assertThat(NpmPackument.versionNames(afterSecond)).containsExactly("1.0.0", "1.1.0");

        writer.publish(repo, baseUrl(repo), publishRequest("demo", "0.9.0", tarball("0"), "legacy"));
        ObjectNode afterLegacy = json(storage.text(repo, "demo"));
        assertThat(str(afterLegacy, "dist-tags", "latest")).isEqualTo("1.1.0");
        assertThat(str(afterLegacy, "dist-tags", "legacy")).isEqualTo("0.9.0");
        assertThat(str(afterLegacy, "description")).isEqualTo("description of demo@1.1.0");
        assertThat(str(afterLegacy, "_rev")).startsWith("3-");
        assertThat(storage.paths(repo)).containsExactly("demo", "demo/-/demo-0.9.0.tgz", "demo/-/demo-1.0.0.tgz", "demo/-/demo-1.1.0.tgz");
    }

    @Test
    void deprecateMergesIntoTheStoredDocument() {
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.0.0", tarball("1"), "latest"));
        ObjectNode fetched = json(storage.text(repo, "demo"));
        ((ObjectNode) fetched.path("versions").path("1.0.0")).put("deprecated", "use 2.x");
        writer.publish(repo, baseUrl(repo), NpmPublishRequest.parse(MAPPER, bytes(fetched)));

        ObjectNode stored = json(storage.text(repo, "demo"));
        assertThat(str(stored, "versions", "1.0.0", "deprecated")).isEqualTo("use 2.x");
        assertThat(str(stored, "_rev")).startsWith("2-");
        assertThat(storage.bytes(repo, "demo/-/demo-1.0.0.tgz")).isEqualTo(tarball("1"));

        ((ObjectNode) stored.path("versions").path("1.0.0")).put("deprecated", "");
        writer.publish(repo, baseUrl(repo), NpmPublishRequest.parse(MAPPER, bytes(stored)));
        assertThat(json(storage.text(repo, "demo")).path("versions").path("1.0.0").has("deprecated")).isFalse();
    }

    @Test
    void unpublishingAVersionRemovesItsTarballAndRetargetsLatest() {
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.0.0", tarball("1"), "latest"));
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.1.0", tarball("2"), "latest"));

        ObjectNode incoming = json(storage.text(repo, "demo"));
        ((ObjectNode) incoming.path("versions")).remove("1.1.0");
        ((ObjectNode) incoming.path("dist-tags")).put("latest", "1.0.0");
        ObjectNode doc = writer.unpublishVersions(repo, "demo", incoming);

        assertThat(NpmPackument.versionNames(doc)).containsExactly("1.0.0");
        assertThat(str(doc, "dist-tags", "latest")).isEqualTo("1.0.0");
        assertThat(storage.paths(repo)).containsExactly("demo", "demo/-/demo-1.0.0.tgz");
        assertThat(json(storage.text(repo, "demo"))).isEqualTo(doc);
    }

    @Test
    void unpublishingTheLastVersionRemovesThePackage() {
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.0.0", tarball("1"), "latest"));
        ObjectNode incoming = json(storage.text(repo, "demo"));
        ((ObjectNode) incoming.path("versions")).remove("1.0.0");
        writer.unpublishVersions(repo, "demo", incoming);
        assertThat(storage.paths(repo)).isEmpty();
    }

    @Test
    void unpublishingThePackageDeletesEverythingItOwns() {
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.0.0", tarball("1"), "latest"));
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "2.0.0", tarball("2"), "latest"));
        writer.publish(repo, baseUrl(repo), publishRequest("demo-other", "1.0.0", tarball("o"), "latest"));

        writer.unpublishPackage(repo, "demo");

        assertThat(storage.paths(repo)).containsExactly("demo-other", "demo-other/-/demo-other-1.0.0.tgz");
        assertError(() -> writer.unpublishPackage(repo, "demo"), HttpStatus.NOT_FOUND, "does not exist");
    }

    @Test
    void deletingATarballDropsTheVersionsThatUsedIt() {
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.0.0", tarball("1"), "latest"));
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.1.0", tarball("2"), "latest"));

        writer.deleteTarball(repo, NpmPath.parse("demo/-/demo-1.1.0.tgz/-rev/2-x"));

        ObjectNode stored = json(storage.text(repo, "demo"));
        assertThat(NpmPackument.versionNames(stored)).containsExactly("1.0.0");
        assertThat(str(stored, "dist-tags", "latest")).isEqualTo("1.0.0");
        assertThat(storage.paths(repo)).containsExactly("demo", "demo/-/demo-1.0.0.tgz");

        writer.deleteTarball(repo, NpmPath.parse("demo/-/demo-1.0.0.tgz/-rev/3-x"));
        assertThat(storage.paths(repo)).isEmpty();
        assertError(() -> writer.deleteTarball(repo, NpmPath.parse("demo/-/demo-1.0.0.tgz/-rev/3-x")),
                HttpStatus.NOT_FOUND, "does not exist");
    }

    @Test
    void distTagsCanBeSetAndRemovedButLatestStays() {
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "1.0.0", tarball("1"), "latest"));
        writer.publish(repo, baseUrl(repo), publishRequest("demo", "2.0.0-beta.1", tarball("2"), "beta"));

        ObjectNode doc = writer.setDistTag(repo, "demo", "next", "2.0.0-beta.1");
        assertThat(str(doc, "dist-tags", "next")).isEqualTo("2.0.0-beta.1");
        assertThat(str(doc, "dist-tags", "latest")).isEqualTo("1.0.0");
        assertThat(str(json(storage.text(repo, "demo")), "dist-tags", "next")).isEqualTo("2.0.0-beta.1");

        assertError(() -> writer.setDistTag(repo, "demo", "next", "9.9.9"), HttpStatus.BAD_REQUEST, "does not exist");
        assertError(() -> writer.setDistTag(repo, "nope", "next", "1.0.0"), HttpStatus.NOT_FOUND, "does not exist");
        assertError(() -> writer.removeDistTag(repo, "demo", "latest"), HttpStatus.BAD_REQUEST, "latest");
        assertError(() -> writer.removeDistTag(repo, "demo", "missing"), HttpStatus.NOT_FOUND, "does not exist");

        ObjectNode removed = writer.removeDistTag(repo, "demo", "next");
        assertThat(removed.path("dist-tags").has("next")).isFalse();
        assertThat(str(removed, "dist-tags", "beta")).isEqualTo("2.0.0-beta.1");
    }

    @Test
    void writesToProxiesAndGroupsAreRefused() {
        Repository proxy = NpmTestSupport.proxy("npm-proxy", "https://registry.npmjs.org/");
        Repository group = NpmTestSupport.group("npm-all", "npm-hosted", "npm-proxy");
        assertError(() -> writer.publish(proxy, baseUrl(proxy), publishRequest("demo", "1.0.0", tarball("1"), "latest")),
                HttpStatus.METHOD_NOT_ALLOWED, "proxy repository");
        assertError(() -> writer.unpublishPackage(group, "demo"), HttpStatus.METHOD_NOT_ALLOWED, "group repository");
        assertError(() -> writer.setDistTag(group, "demo", "a", "1.0.0"), HttpStatus.METHOD_NOT_ALLOWED, "group repository");
        assertThat(storage.paths(proxy)).isEmpty();
    }

    private static void assertError(ThrowingCallable call, HttpStatus status, String messagePart) {
        assertThatThrownBy(call)
                .isInstanceOf(NpmError.class)
                .hasMessageContaining(messagePart)
                .satisfies(e -> assertThat(((NpmError) e).status()).isEqualTo(status));
    }
}
