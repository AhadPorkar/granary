package dev.granary.format.go;

import static dev.granary.format.go.GoTestSupport.MAPPER;
import static dev.granary.format.go.GoTestSupport.hosted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoHostedWriterTest {

    private static final String MODULE = "example.com/acme/hello";

    @TempDir
    Path dataDir;

    private final FakeStorage storage = new FakeStorage();
    private final Repository repo = hosted("go-hosted");
    private GoHostedWriter writer;

    @BeforeEach
    void setUp() {
        GranaryProperties properties = new GranaryProperties();
        properties.setDataDir(dataDir);
        writer = new GoHostedWriter(storage.mock, MAPPER, properties);
    }

    @Test
    void publishStoresZipDerivedModAndInfoWithCoordinates() throws IOException {
        byte[] zip = GoTestZips.module(MODULE, "v0.1.0");
        GoHostedWriter.Published published = publish("v0.1.0", zip);

        assertThat(published.module()).isEqualTo(MODULE);
        assertThat(published.version()).isEqualTo("v0.1.0");
        assertThat(published.files()).containsExactly(MODULE + "/@v/v0.1.0.zip", MODULE + "/@v/v0.1.0.mod", MODULE + "/@v/v0.1.0.info");
        assertThat(storage.bytes(repo, MODULE + "/@v/v0.1.0.zip")).isEqualTo(zip);
        assertThat(storage.text(repo, MODULE + "/@v/v0.1.0.mod")).isEqualTo("module example.com/acme/hello\n\ngo 1.21\n");
        GoInfo info = GoInfo.parse(MAPPER, storage.bytes(repo, MODULE + "/@v/v0.1.0.info")).orElseThrow();
        assertThat(info.version()).isEqualTo("v0.1.0");
        assertThat(info.time()).isNotNull().isBeforeOrEqualTo(Instant.now());

        StorageService.ComponentCoordinates expected = new StorageService.ComponentCoordinates("example.com/acme", "hello", "v0.1.0");
        for (String ext : new String[] {"zip", "mod", "info"}) {
            assertThat(storage.coordinates.get(repo.getId() + "|" + MODULE + "/@v/v0.1.0." + ext)).isEqualTo(expected);
        }
        assertThat(storage.find(repo, MODULE + "/@v/v0.1.0.zip").orElseThrow().asset().getContentType()).isEqualTo("application/zip");
        assertThat(storage.find(repo, MODULE + "/@v/v0.1.0.mod").orElseThrow().asset().getContentType()).isEqualTo("text/plain; charset=utf-8");
        assertThat(storage.find(repo, MODULE + "/@v/v0.1.0.info").orElseThrow().asset().getContentType()).isEqualTo("application/json");
        assertThat(storage.find(repo, MODULE + "/@v/v0.1.0.zip").orElseThrow().asset().isMetadata()).isFalse();
        assertThat(Files.list(dataDir.resolve("tmp").resolve("go"))).isEmpty();
    }

    @Test
    void directoryEntriesThatZipRWritesAreDroppedBeforeStoring() throws IOException {
        String prefix = MODULE + "@v0.1.0/";
        byte[] zip = GoTestZips.zip(GoTestZips.entries(
                "example.com/", "",
                "example.com/acme/", "",
                prefix, "",
                prefix + "go.mod", "module " + MODULE + "\n",
                prefix + "pkg/", "",
                prefix + "pkg/a.go", "package pkg\n"));
        assertThatThrownBy(() -> publish("v0.1.0", zip)).as("parents of the prefix are outside it")
                .isInstanceOf(GoError.class).hasMessageContaining("does not have prefix");

        byte[] zipR = GoTestZips.zip(GoTestZips.entries(
                prefix, "",
                prefix + "go.mod", "module " + MODULE + "\n",
                prefix + "pkg/", "",
                prefix + "pkg/a.go", "package pkg\n"));
        publish("v0.1.0", zipR);
        byte[] stored = storage.bytes(repo, MODULE + "/@v/v0.1.0.zip");
        assertThat(stored).isNotEqualTo(zipR);
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(stored))) {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (java.util.zip.ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
            }
            assertThat(names).containsExactly(prefix + "go.mod", prefix + "pkg/a.go");
        }
        assertThat(Files.list(dataDir.resolve("tmp").resolve("go"))).isEmpty();
    }

    @Test
    void caseEncodedModulesAreStoredEncodedAndRecordedDecoded() throws IOException {
        GoPath path = GoPath.parse("github.com/!acme/!hello/@v/v1.0.0-!r!c1.zip");
        byte[] zip = GoTestZips.module("github.com/Acme/Hello", "v1.0.0-RC1");
        writer.publishZip(repo, path, new ByteArrayInputStream(zip));
        assertThat(storage.paths(repo)).containsExactly(
                "github.com/!acme/!hello/@v/v1.0.0-!r!c1.info",
                "github.com/!acme/!hello/@v/v1.0.0-!r!c1.mod",
                "github.com/!acme/!hello/@v/v1.0.0-!r!c1.zip");
        assertThat(storage.coordinates.get(repo.getId() + "|github.com/!acme/!hello/@v/v1.0.0-!r!c1.zip"))
                .isEqualTo(new StorageService.ComponentCoordinates("github.com/Acme", "Hello", "v1.0.0-RC1"));
    }

    @Test
    void republishingIsAConflictUnlessRedeployIsAllowed() throws IOException {
        publish("v0.1.0", GoTestZips.module(MODULE, "v0.1.0"));
        byte[] again = GoTestZips.module(MODULE, "v0.1.0", Map.of("go.mod", "module " + MODULE + "\n", "other.go", "package hello\n"));
        assertThatThrownBy(() -> publish("v0.1.0", again)).isInstanceOf(GoError.class)
                .hasMessageContaining("already exists")
                .satisfies(e -> assertThat(((GoError) e).status().value()).isEqualTo(409));
        assertThat(storage.bytes(repo, MODULE + "/@v/v0.1.0.zip")).isEqualTo(GoTestZips.module(MODULE, "v0.1.0"));

        repo.setAllowRedeploy(true);
        publish("v0.1.0", again);
        assertThat(storage.bytes(repo, MODULE + "/@v/v0.1.0.zip")).isEqualTo(again);
        assertThat(storage.text(repo, MODULE + "/@v/v0.1.0.mod")).isEqualTo("module " + MODULE + "\n");
    }

    @Test
    void invalidZipsLeaveNothingBehind() {
        assertThatThrownBy(() -> publish("v0.1.0", "garbage".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(GoError.class).hasMessageContaining("not a valid zip file");
        assertThatThrownBy(() -> publish("v0.1.0", GoTestZips.module("example.com/other", "v0.1.0")))
                .isInstanceOf(GoError.class).hasMessageContaining("does not have prefix");
        assertThatThrownBy(() -> publish("v0.1.0", new byte[0]))
                .isInstanceOf(GoError.class).hasMessageContaining("body is empty");
        assertThat(storage.paths(repo)).isEmpty();
    }

    @Test
    void goModMustExistAndDeclareTheModule() throws IOException {
        assertThatThrownBy(() -> publish("v0.1.0", GoTestZips.module(MODULE, "v0.1.0", Map.of("hello.go", "package hello\n"))))
                .isInstanceOf(GoError.class).hasMessageContaining("has no " + MODULE + "@v0.1.0/go.mod");
        assertThatThrownBy(() -> publish("v0.1.0", GoTestZips.module(MODULE, "v0.1.0", Map.of("go.mod", "module example.com/acme/other\n"))))
                .isInstanceOf(GoError.class).hasMessageContaining("declares its path as \"example.com/acme/other\"");
        assertThatThrownBy(() -> publish("v0.1.0", GoTestZips.module(MODULE, "v0.1.0", Map.of("go.mod", "go 1.21\n"))))
                .isInstanceOf(GoError.class).hasMessageContaining("no module directive");
        assertThat(storage.paths(repo)).isEmpty();

        // a module without go.mod is only publishable as +incompatible, and then gets a synthetic go.mod
        publish("v2.0.0+incompatible", GoTestZips.module(MODULE, "v2.0.0+incompatible", Map.of("hello.go", "package hello\n")));
        assertThat(storage.text(repo, MODULE + "/@v/v2.0.0+incompatible.mod")).isEqualTo("module " + MODULE + "\n");
    }

    @Test
    void majorVersionMustAgreeWithThePath() {
        assertThatThrownBy(() -> publish("v2.0.0", GoTestZips.module(MODULE, "v2.0.0")))
                .isInstanceOf(GoError.class).hasMessageContaining("should be v0 or v1, not v2");
        assertThatThrownBy(() -> publish("v1.0", GoTestZips.module(MODULE, "v1.0")))
                .isInstanceOf(GoError.class).hasMessageContaining("non-canonical");
        assertThatThrownBy(() -> publish("master", GoTestZips.module(MODULE, "master")))
                .isInstanceOf(GoError.class).hasMessageContaining("semantic version");
        assertThat(storage.paths(repo)).isEmpty();
    }

    @Test
    void uploadedInfoReplacesTheGeneratedOneButAnUploadedInfoIsKept() throws IOException {
        publish("v0.1.0", GoTestZips.module(MODULE, "v0.1.0"));
        GoPath path = GoPath.parse(MODULE + "/@v/v0.1.0.info");
        assertThat(writer.putInfo(repo, path, utf8("{\"Version\":\"v0.1.0\",\"Time\":\"2020-01-01T00:00:00Z\"}"))).isTrue();
        assertThat(storage.text(repo, MODULE + "/@v/v0.1.0.info")).isEqualTo("{\"Version\":\"v0.1.0\",\"Time\":\"2020-01-01T00:00:00Z\"}");
        assertThat(storage.find(repo, MODULE + "/@v/v0.1.0.info").orElseThrow().asset().getOrigin()).isEqualTo(Asset.Origin.UPLOADED);

        // the same document again is fine and writes nothing, a different one is a conflict
        assertThat(writer.putInfo(repo, path, utf8("{\"Time\":\"2020-01-01T00:00:00Z\",\"Version\":\"v0.1.0\"}"))).isFalse();
        assertThatThrownBy(() -> writer.putInfo(repo, path, utf8("{\"Version\":\"v0.1.0\"}")))
                .isInstanceOf(GoError.class).hasMessageContaining("does not allow redeploys")
                .satisfies(e -> assertThat(((GoError) e).status().value()).isEqualTo(409));
        assertThat(storage.text(repo, MODULE + "/@v/v0.1.0.info")).contains("2020-01-01T00:00:00Z");

        repo.setAllowRedeploy(true);
        assertThat(writer.putInfo(repo, path, utf8("{\"Version\":\"v0.1.0\"}"))).isTrue();
        assertThat(storage.text(repo, MODULE + "/@v/v0.1.0.info")).isEqualTo("{\"Version\":\"v0.1.0\"}");
        // a redeployed zip keeps what a client uploaded
        GoHostedWriter.Published republished = publish("v0.1.0", GoTestZips.module(MODULE, "v0.1.0"));
        assertThat(republished.files()).containsExactly(MODULE + "/@v/v0.1.0.zip", MODULE + "/@v/v0.1.0.mod");
        assertThat(storage.text(repo, MODULE + "/@v/v0.1.0.info")).isEqualTo("{\"Version\":\"v0.1.0\"}");
    }

    @Test
    void thePublishedGoModNeverChangesUnlessRedeployIsAllowed() throws IOException {
        publish("v0.1.0", GoTestZips.module(MODULE, "v0.1.0"));
        GoPath path = GoPath.parse(MODULE + "/@v/v0.1.0.mod");
        String derived = storage.text(repo, MODULE + "/@v/v0.1.0.mod");

        // the go.mod the go command keeps in its module cache is the one served: accepted, nothing written
        assertThat(writer.putMod(repo, path, utf8(derived))).isFalse();
        assertThat(storage.find(repo, MODULE + "/@v/v0.1.0.mod").orElseThrow().asset().getOrigin()).isEqualTo(Asset.Origin.GENERATED);
        // a different go.mod would break every go.sum that recorded the served one
        assertThatThrownBy(() -> writer.putMod(repo, path, utf8("module " + MODULE + "\n\ngo 1.22\n")))
                .isInstanceOf(GoError.class).hasMessageContaining("go.sum")
                .satisfies(e -> assertThat(((GoError) e).status().value()).isEqualTo(409));
        assertThat(storage.text(repo, MODULE + "/@v/v0.1.0.mod")).isEqualTo(derived);

        repo.setAllowRedeploy(true);
        assertThat(writer.putMod(repo, path, utf8("module " + MODULE + "\n\ngo 1.22\n"))).isTrue();
        assertThat(storage.text(repo, MODULE + "/@v/v0.1.0.mod")).isEqualTo("module " + MODULE + "\n\ngo 1.22\n");
        // an uploaded go.mod survives a redeployed zip
        GoHostedWriter.Published republished = publish("v0.1.0", GoTestZips.module(MODULE, "v0.1.0"));
        assertThat(republished.files()).containsExactly(MODULE + "/@v/v0.1.0.zip", MODULE + "/@v/v0.1.0.info");
        assertThat(storage.text(repo, MODULE + "/@v/v0.1.0.mod")).isEqualTo("module " + MODULE + "\n\ngo 1.22\n");
    }

    @Test
    void infoAndModNeedThePublishedZip() {
        GoPath path = GoPath.parse(MODULE + "/@v/v0.1.0.mod");
        assertThatThrownBy(() -> writer.putMod(repo, path, utf8("module " + MODULE + "\n")))
                .isInstanceOf(GoError.class).hasMessageContaining("upload " + MODULE + "/@v/v0.1.0.zip first")
                .satisfies(e -> {
                    assertThat(((GoError) e).status().value()).isEqualTo(409);
                    assertThat(((GoError) e).code()).isEqualTo("repository.go.versionMissing");
                });
        assertThatThrownBy(() -> writer.putInfo(repo, path, utf8("{\"Version\":\"v0.1.0\"}")))
                .isInstanceOf(GoError.class).hasMessageContaining("has not been published");
        assertThat(storage.paths(repo)).isEmpty();
    }

    @Test
    void concurrentUploadsOfOneVersionEndInOneWinnerAndConflicts() throws Exception {
        int uploaders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(uploaders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < uploaders; i++) {
                byte[] zip = GoTestZips.module(MODULE, "v0.1.0", Map.of("go.mod", "module " + MODULE + "\n", "u" + i + ".go", "package hello\n"));
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        publish("v0.1.0", zip);
                        return 201;
                    } catch (GoError e) {
                        return e.status().value();
                    }
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(storage.paths(repo)).containsExactly(MODULE + "/@v/v0.1.0.info", MODULE + "/@v/v0.1.0.mod", MODULE + "/@v/v0.1.0.zip");
    }

    @Test
    void uploadedInfoAndModAreValidated() {
        GoPath path = GoPath.parse(MODULE + "/@v/v0.1.0.mod");
        assertThatThrownBy(() -> writer.putInfo(repo, path, "{\"Version\":\"v0.2.0\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(GoError.class).hasMessageContaining("names version v0.2.0");
        assertThatThrownBy(() -> writer.putInfo(repo, path, "nope".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(GoError.class).hasMessageContaining("JSON object");
        assertThatThrownBy(() -> writer.putMod(repo, path, "module example.com/other\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(GoError.class).hasMessageContaining("declares its path as");
        assertThatThrownBy(() -> writer.putMod(repo, path, new byte[0]))
                .isInstanceOf(GoError.class).hasMessageContaining("body is empty");
        assertThatThrownBy(() -> writer.putMod(repo, GoPath.parse(MODULE + "/@v/master.mod"), "module x\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(GoError.class).hasMessageContaining("semantic version");
        assertThat(storage.paths(repo)).isEmpty();
    }

    @Test
    void deleteRemovesTheVersionAndOnlyThatVersion() throws IOException {
        publish("v0.1.0", GoTestZips.module(MODULE, "v0.1.0"));
        publish("v0.2.0", GoTestZips.module(MODULE, "v0.2.0"));
        assertThat(writer.delete(repo, GoPath.parse(MODULE + "/@v/v0.1.0")))
                .containsExactly(MODULE + "/@v/v0.1.0.zip", MODULE + "/@v/v0.1.0.mod", MODULE + "/@v/v0.1.0.info");
        assertThat(storage.paths(repo)).containsExactly(MODULE + "/@v/v0.2.0.info", MODULE + "/@v/v0.2.0.mod", MODULE + "/@v/v0.2.0.zip");
        assertThatThrownBy(() -> writer.delete(repo, GoPath.parse(MODULE + "/@v/v0.1.0")))
                .isInstanceOf(GoError.class).satisfies(e -> assertThat(((GoError) e).status().value()).isEqualTo(404));
        assertThatThrownBy(() -> writer.delete(repo, GoPath.parse(MODULE + "/@v/latest")))
                .isInstanceOf(GoError.class).satisfies(e -> assertThat(((GoError) e).status().value()).isEqualTo(400));
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private GoHostedWriter.Published publish(String version, byte[] zip) throws IOException {
        GoPath path = GoPath.parse(MODULE + "/@v/" + GoModulePaths.escape(version) + ".zip");
        return writer.publishZip(repo, path, new ByteArrayInputStream(zip));
    }
}
