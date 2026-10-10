package dev.granary.format.helm;

import static dev.granary.format.helm.HelmTestSupport.get;
import static dev.granary.format.helm.HelmTestSupport.multipart;
import static dev.granary.format.helm.HelmTestSupport.request;
import static dev.granary.format.helm.HelmTestSupport.text;
import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.storage.StorageService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class HelmFormatHandlerTest {

    @TempDir
    Path dataDir;

    HelmTestSupport.Wiring w;

    @BeforeEach
    void wire() {
        w = new HelmTestSupport.Wiring(dataDir);
    }

    private static JsonNode json(ResponseEntity<?> response) {
        return HelmTestSupport.MAPPER.readTree(HelmTestSupport.body(response));
    }

    private static HelmIndex index(ResponseEntity<?> response) {
        return HelmIndex.parse(HelmTestSupport.body(response));
    }

    @Test
    void describesItself() {
        HelmFormatHandler h = w.handler;
        assertThat(h.format()).isEqualTo("helm");
        assertThat(h.displayName()).isEqualTo("Helm charts");
        assertThat(h.templates()).extracting(RepositoryTemplate::name).containsExactly("helm-hosted", "helm-group");
        assertThat(h.templates().get(1).members()).containsExactly("helm-hosted");
        assertThat(h.templates()).extracting(RepositoryTemplate::type).containsExactly(RepositoryType.HOSTED, RepositoryType.GROUP);
        assertThat(h.settings()).hasSize(2);
        assertThat(h.isMetadataPath("index.yaml")).isTrue();
        assertThat(h.isMetadataPath("entries/demo/1.0.0.json")).isTrue();
        assertThat(h.isMetadataPath("charts/demo-1.0.0.tgz")).isFalse();
        assertThat(h.isMetadataPath(null)).isFalse();
        assertThat(h.tempDir()).isEqualTo(dataDir.resolve("tmp").resolve("helm"));
    }

    @Test
    void accessLevels() {
        Repository repo = HelmTestSupport.hosted("h");
        assertThat(w.handler.requiredAccess(get(repo, "health"))).isEqualTo(AccessLevel.NONE);
        assertThat(w.handler.requiredAccess(get(repo, "index.yaml"))).isEqualTo(AccessLevel.READ);
        assertThat(w.handler.requiredAccess(request(repo, "HEAD", "charts/a-1.0.0.tgz", null, null))).isEqualTo(AccessLevel.READ);
        assertThat(w.handler.requiredAccess(get(repo, "api/charts"))).isEqualTo(AccessLevel.READ);
        assertThat(w.handler.requiredAccess(request(repo, "POST", "api/charts", null, null))).isEqualTo(AccessLevel.WRITE);
        assertThat(w.handler.requiredAccess(request(repo, "DELETE", "api/charts/a/1.0.0", null, null))).isEqualTo(AccessLevel.WRITE);
        assertThat(w.handler.requiredAccess(request(repo, "POST", "health", null, null))).isEqualTo(AccessLevel.WRITE);
    }

    @Nested
    class Hosted {

        Repository repo = HelmTestSupport.hosted("helm-hosted");

        @Test
        void emptyRepositoryServesAnEmptyIndex() {
            ResponseEntity<?> response = w.handle(get(repo, "index.yaml"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getContentType()).hasToString("application/x-yaml");
            assertThat(text(response)).startsWith("apiVersion: v1\nentries: {}\ngenerated: ")
                    .contains("serverInfo:\n  contextPath: /repository/helm-hosted\n");
        }

        @Test
        void uploadedChartIsListedAndDownloadable() {
            byte[] tgz = TarFixtures.resource("charts/demo-app-0.1.0.tgz");
            ResponseEntity<?> upload = w.upload(repo, tgz);
            assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(json(upload).path("saved").asBoolean()).isTrue();

            HelmIndex index = index(w.handle(get(repo, "index.yaml")));
            ChartEntry entry = index.find("demo-app", "0.1.0").orElseThrow();
            assertThat(entry.urls()).containsExactly("http://granary.test/repository/helm-hosted/charts/demo-app-0.1.0.tgz");
            assertThat(entry.digest()).isEqualTo(HelmTestSupport.sha256(tgz));
            assertThat(entry.created()).isNotBlank();
            assertThat(entry.string("apiVersion")).isEqualTo("v2");
            assertThat(entry.string("appVersion")).isEqualTo("1.16.0");

            ResponseEntity<?> download = w.handle(get(repo, "charts/demo-app-0.1.0.tgz"));
            assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(HelmTestSupport.body(download)).isEqualTo(tgz);
            assertThat(download.getHeaders().getContentType()).hasToString("application/gzip");
            assertThat(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("demo-app-0.1.0.tgz");
            assertThat(download.getHeaders().getETag()).isEqualTo("\"" + HelmTestSupport.sha256(tgz) + "\"");
            assertThat(w.storage.downloads).hasSize(1);

            // the Nexus layout (archive at the repository root) is the same file
            assertThat(HelmTestSupport.body(w.handle(get(repo, "demo-app-0.1.0.tgz")))).isEqualTo(tgz);
            // so is the address by name and version
            assertThat(HelmTestSupport.body(w.handle(get(repo, "charts/demo-app/0.1.0/demo-app-0.1.0.tgz")))).isEqualTo(tgz);

            assertThat(w.storage.coordinates.values()).allSatisfy(c -> {
                assertThat(c.name()).isEqualTo("demo-app");
                assertThat(c.version()).isEqualTo("0.1.0");
            });
            assertThat(w.storage.paths(repo)).containsExactly("charts/demo-app-0.1.0.tgz", "entries/demo-app/0.1.0.json");
        }

        @Test
        void headAndConditionalRequests() {
            w.upload(repo, TarFixtures.chart("demo", "1.0.0"));
            ResponseEntity<?> index = w.handle(get(repo, "index.yaml"));
            String etag = index.getHeaders().getETag();
            assertThat(etag).isNotBlank();
            ResponseEntity<?> head = w.handle(request(repo, "HEAD", "index.yaml", null, null));
            assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(head.getBody()).isNull();
            assertThat(head.getHeaders().getContentLength()).isEqualTo(HelmTestSupport.body(index).length);
            ResponseEntity<?> notModified = w.handle(HelmTestSupport.withHeader(get(repo, "index.yaml"), "If-None-Match", etag));
            assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

            ResponseEntity<?> chart = w.handle(get(repo, "charts/demo-1.0.0.tgz"));
            ResponseEntity<?> chartNotModified = w.handle(HelmTestSupport.withHeader(get(repo, "charts/demo-1.0.0.tgz"),
                    "If-None-Match", chart.getHeaders().getETag()));
            assertThat(chartNotModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
            ResponseEntity<?> chartHead = w.handle(request(repo, "HEAD", "charts/demo-1.0.0.tgz", null, null));
            assertThat(chartHead.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(w.storage.downloads).hasSize(1); // only the GET counts

            // a new upload changes the index and its ETag
            w.upload(repo, TarFixtures.chart("demo", "1.1.0"));
            ResponseEntity<?> changed = w.handle(HelmTestSupport.withHeader(get(repo, "index.yaml"), "If-None-Match", etag));
            assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(index(changed).versions("demo")).extracting(ChartEntry::version).containsExactly("1.1.0", "1.0.0");
        }

        @Test
        void multipartUploadWithProvenance() {
            byte[] tgz = TarFixtures.resource("charts/demo-app-0.2.0.tgz");
            byte[] prov = TarFixtures.resource("charts/demo-app-0.2.0.tgz.prov");
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("chart", tgz);
            files.put("prov", prov);
            ResponseEntity<?> response = w.handle(multipart(repo, "api/charts", files));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(HelmTestSupport.body(w.handle(get(repo, "charts/demo-app-0.2.0.tgz.prov")))).isEqualTo(prov);
            ResponseEntity<?> provResponse = w.handle(get(repo, "charts/demo-app-0.2.0.tgz.prov"));
            assertThat(provResponse.getHeaders().getContentType()).hasToString("application/pgp-signature");
            assertThat(w.storage.paths(repo)).contains("charts/demo-app-0.2.0.tgz.prov");
        }

        @Test
        void provenanceUploadedSeparately() {
            byte[] tgz = TarFixtures.resource("charts/demo-app-0.2.0.tgz");
            byte[] prov = TarFixtures.resource("charts/demo-app-0.2.0.tgz.prov");
            w.upload(repo, tgz);
            ResponseEntity<?> response = w.handle(request(repo, "POST", "api/prov", prov, "application/octet-stream"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(HelmTestSupport.body(w.handle(get(repo, "charts/demo-app-0.2.0.tgz.prov")))).isEqualTo(prov);
            // and again: the provenance file exists now
            assertThat(w.handle(request(repo, "POST", "api/prov", prov, "application/octet-stream")).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
            // multipart field prov works too
            ResponseEntity<?> multi = w.handle(multipart(repo, "api/prov", Map.of("prov", prov)));
            assertThat(multi.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void provenanceOfAnotherArchiveIsRefused() {
            w.upload(repo, TarFixtures.chart("demo-app", "0.2.0"));
            byte[] prov = TarFixtures.resource("charts/demo-app-0.2.0.tgz.prov");
            ResponseEntity<?> response = w.handle(request(repo, "POST", "api/prov", prov, "application/octet-stream"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(response).path("error").asString()).contains("does not match the chart archive");

            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("chart", TarFixtures.chart("other", "1.0.0"));
            files.put("prov", prov);
            ResponseEntity<?> mismatch = w.handle(multipart(repo, "api/charts", files));
            assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(mismatch).path("error").asString()).contains("is for demo-app 0.2.0, not for other 1.0.0");
            assertThat(w.storage.paths(repo)).noneMatch(p -> p.contains("other"));
        }

        @Test
        void reuploadIsAConflictUnlessRedeployIsAllowed() {
            byte[] first = TarFixtures.chart("demo", "1.0.0");
            byte[] second = TarFixtures.chart("demo", "1.0.0", "keywords: [changed]\n");
            assertThat(w.upload(repo, first).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            ResponseEntity<?> conflict = w.upload(repo, second);
            assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(json(conflict).path("error").asString()).contains("already exists").contains("redeployment is disabled");
            ResponseEntity<?> forced = w.handle(HelmTestSupport.withQuery(request(repo, "POST", "api/charts", second, "application/gzip"), "force", ""));
            assertThat(forced.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(HelmTestSupport.body(w.handle(get(repo, "charts/demo-1.0.0.tgz")))).isEqualTo(first);

            repo.setAllowRedeploy(true);
            assertThat(w.upload(repo, second).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(HelmTestSupport.body(w.handle(get(repo, "charts/demo-1.0.0.tgz")))).isEqualTo(second);
            assertThat(index(w.handle(get(repo, "index.yaml"))).find("demo", "1.0.0").orElseThrow().digest())
                    .isEqualTo(HelmTestSupport.sha256(second));
        }

        @Test
        void aChartVersionCannotTakeOverTheArchiveFileNameOfAnother() {
            // "a-1" 2.0.0 and "a" 1-2.0.0 (a valid loose semantic version) are both packaged as a-1-2.0.0.tgz
            byte[] owner = TarFixtures.chart("a-1", "2.0.0");
            byte[] impostor = TarFixtures.chart("a", "1-2.0.0");
            assertThat(w.upload(repo, owner).getStatusCode()).isEqualTo(HttpStatus.CREATED);

            ResponseEntity<?> refused = w.upload(repo, impostor);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(json(refused).path("error").asString())
                    .isEqualTo("chart a 1-2.0.0 would be stored as a-1-2.0.0.tgz, which belongs to chart a-1 2.0.0 in 'helm-hosted'");
            // not even a repository that allows redeployment lets one chart replace another's archive
            repo.setAllowRedeploy(true);
            assertThat(w.upload(repo, impostor).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(w.handle(request(repo, "POST", "api/prov",
                    HelmTestSupport.provenance("a", "1-2.0.0", HelmTestSupport.sha256(owner)).getBytes(StandardCharsets.UTF_8),
                    "application/octet-stream")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            assertThat(HelmTestSupport.body(w.handle(get(repo, "charts/a-1-2.0.0.tgz")))).isEqualTo(owner);
            assertThat(w.storage.paths(repo)).doesNotContain("entries/a/1-2.0.0.json", "charts/a-1-2.0.0.tgz.prov");
            HelmIndex index = index(w.handle(get(repo, "index.yaml")));
            assertThat(index.find("a-1", "2.0.0").orElseThrow().digest()).isEqualTo(HelmTestSupport.sha256(owner));
            assertThat(index.versions("a")).isEmpty();
            // the other reading of the file name is not an address of the archive either
            assertThat(w.handle(get(repo, "charts/a/1-2.0.0/a-1-2.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(w.handle(get(repo, "charts/a-1/2.0.0/a-1-2.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void deletingAVersionThatDoesNotExistLeavesAnotherVersionsArchiveAlone() {
            byte[] owner = TarFixtures.chart("a-1", "2.0.0");
            assertThat(w.upload(repo, owner).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(w.handle(request(repo, "DELETE", "api/charts/a/1-2.0.0", null, null)).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(HelmTestSupport.body(w.handle(get(repo, "charts/a-1-2.0.0.tgz")))).isEqualTo(owner);
            assertThat(index(w.handle(get(repo, "index.yaml"))).find("a-1", "2.0.0")).isPresent();

            // the owner itself is deleted as usual, after which the other chart version may use the name
            assertThat(w.handle(request(repo, "DELETE", "api/charts/a-1/2.0.0", null, null)).getStatusCode()).isEqualTo(HttpStatus.OK);
            byte[] other = TarFixtures.chart("a", "1-2.0.0");
            assertThat(w.upload(repo, other).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(HelmTestSupport.body(w.handle(get(repo, "charts/a/1-2.0.0/a-1-2.0.0.tgz")))).isEqualTo(other);
        }

        @Test
        void redeployDropsTheProvenanceOfTheReplacedArchive() {
            repo.setAllowRedeploy(true);
            byte[] tgz = TarFixtures.resource("charts/demo-app-0.2.0.tgz");
            byte[] prov = TarFixtures.resource("charts/demo-app-0.2.0.tgz.prov");
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("chart", tgz);
            files.put("prov", prov);
            w.handle(multipart(repo, "api/charts", files));
            // same archive again: its provenance stays valid
            w.upload(repo, tgz);
            assertThat(w.storage.paths(repo)).contains("charts/demo-app-0.2.0.tgz.prov");
            // a different archive with the same version: the old signature no longer applies
            w.upload(repo, TarFixtures.chart("demo-app", "0.2.0"));
            assertThat(w.storage.paths(repo)).doesNotContain("charts/demo-app-0.2.0.tgz.prov");
        }

        @Test
        void putUploadsLikeNexusAndArtifactory() {
            byte[] tgz = TarFixtures.chart("putchart", "2.0.0");
            assertThat(w.handle(request(repo, "PUT", "putchart-2.0.0.tgz", tgz, null)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(w.handle(request(repo, "PUT", "charts/whatever.tgz", TarFixtures.chart("putchart", "2.1.0"), null))
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(index(w.handle(get(repo, "index.yaml"))).versions("putchart")).hasSize(2);
            byte[] prov = HelmTestSupport.provenance("putchart", "2.0.0", HelmTestSupport.sha256(tgz)).getBytes(StandardCharsets.UTF_8);
            assertThat(w.handle(request(repo, "PUT", "putchart-2.0.0.tgz.prov", prov, null)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(w.storage.paths(repo)).contains("charts/putchart-2.0.0.tgz.prov");
        }

        @Test
        void invalidUploadsAreRefusedWith400() {
            assertThat(json(w.upload(repo, "not a chart".getBytes())).path("error").asString()).startsWith("invalid chart:");
            assertThat(w.upload(repo, "not a chart".getBytes()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(w.upload(repo, new byte[0]).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ResponseEntity<?> badVersion = w.upload(repo, TarFixtures.chartWithYaml("demo", "apiVersion: v2\nname: demo\nversion: one\n"));
            assertThat(json(badVersion).path("error").asString()).contains("chart.metadata.version \"one\" is invalid");
            ResponseEntity<?> noFields = w.handle(multipart(repo, "api/charts", Map.of("other", new byte[] {1})));
            assertThat(noFields.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // a lone file under another name is taken as the chart
            ResponseEntity<?> emptyChart = w.handle(multipart(repo, "api/charts", Map.of("chart", new byte[0])));
            assertThat(emptyChart.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ResponseEntity<?> provOnly = w.handle(request(repo, "POST", "api/prov", new byte[0], null));
            assertThat(provOnly.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ResponseEntity<?> chartToProv = w.handle(multipart(repo, "api/prov", Map.of("chart", TarFixtures.chart("x", "1.0.0"))));
            assertThat(chartToProv.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(w.storage.paths(repo)).isEmpty();
            try (var tmp = Files.list(dataDir.resolve("tmp").resolve("helm"))) {
                assertThat(tmp).as("temporary upload files are removed").isEmpty();
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        }

        @Test
        void sizeLimitIsEnforced() {
            repo.setFormatSettings(Map.of("helm.maxChartSizeMb", "1"));
            byte[] big = new byte[1024 * 1024 + 1];
            new java.util.Random(1).nextBytes(big);
            ResponseEntity<?> response = w.upload(repo, big);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
            assertThat(json(response).path("error").asString()).contains("1 MiB");
        }

        @Test
        void chartMuseumApiReads() {
            w.upload(repo, TarFixtures.chart("demo", "1.0.0"));
            w.upload(repo, TarFixtures.chart("demo", "1.1.0-rc.1"));
            w.upload(repo, TarFixtures.chart("nginx", "2.0.0"));

            JsonNode all = json(w.handle(get(repo, "api/charts")));
            assertThat(all.propertyNames()).containsExactly("demo", "nginx");
            assertThat(all.path("demo").get(0).path("version").asString()).isEqualTo("1.1.0-rc.1");
            assertThat(all.path("demo").get(0).path("urls").get(0).asString())
                    .isEqualTo("http://granary.test/repository/helm-hosted/charts/demo-1.1.0-rc.1.tgz");

            JsonNode versions = json(w.handle(get(repo, "api/charts/demo")));
            assertThat(versions.size()).isEqualTo(2);
            assertThat(json(w.handle(get(repo, "api/charts/demo/1.0.0"))).path("version").asString()).isEqualTo("1.0.0");
            assertThat(json(w.handle(get(repo, "api/charts/demo/latest"))).path("version").asString()).isEqualTo("1.0.0");
            assertThat(w.handle(get(repo, "api/charts/demo/9.9.9")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(json(w.handle(get(repo, "api/charts/nope"))).path("error").asString()).isEqualTo("chart not found: nope");
            ResponseEntity<?> head = w.handle(request(repo, "HEAD", "api/charts/demo/1.0.0", null, null));
            assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(head.getBody()).isNull();
            assertThat(w.handle(request(repo, "HEAD", "api/charts/nope", null, null)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(json(w.handle(get(repo, "health"))).path("healthy").asBoolean()).isTrue();
        }

        @Test
        void deleteThroughTheApiAndByFile() {
            w.upload(repo, TarFixtures.chart("demo", "1.0.0"));
            w.upload(repo, TarFixtures.chart("demo", "2.0.0"));
            ResponseEntity<?> deleted = w.handle(request(repo, "DELETE", "api/charts/demo/1.0.0", null, null));
            assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(deleted).path("deleted").asBoolean()).isTrue();
            assertThat(w.handle(request(repo, "DELETE", "api/charts/demo/1.0.0", null, null)).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(index(w.handle(get(repo, "index.yaml"))).versions("demo")).extracting(ChartEntry::version).containsExactly("2.0.0");
            assertThat(w.handle(get(repo, "charts/demo-1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            assertThat(w.handle(request(repo, "DELETE", "charts/demo-2.0.0.tgz", null, null)).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(w.storage.paths(repo)).isEmpty();
            assertThat(w.handle(request(repo, "DELETE", "charts/demo-2.0.0.tgz", null, null)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(w.handle(request(repo, "DELETE", "charts/demo-2.0.0.tgz.prov", null, null)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void republishAfterDeleteIsAllowed() {
            w.upload(repo, TarFixtures.chart("demo", "1.0.0"));
            w.handle(request(repo, "DELETE", "api/charts/demo/1.0.0", null, null));
            assertThat(w.upload(repo, TarFixtures.chart("demo", "1.0.0", "keywords: [again]\n")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        @Test
        void entryWithoutArchiveIsNotListedAndOrphanArchiveCanBeReplaced() {
            w.upload(repo, TarFixtures.chart("demo", "1.0.0"));
            w.storage.mock.delete(repo, "charts/demo-1.0.0.tgz"); // as if the asset was removed in the UI
            assertThat(index(w.handle(get(repo, "index.yaml"))).size()).isZero();
            assertThat(w.upload(repo, TarFixtures.chart("demo", "1.0.0")).getStatusCode()).isEqualTo(HttpStatus.CREATED);

            w.storage.mock.delete(repo, "entries/demo/1.0.0.json"); // an upload that died before publishing its entry
            assertThat(index(w.handle(get(repo, "index.yaml"))).size()).isZero();
            assertThat(w.upload(repo, TarFixtures.chart("demo", "1.0.0")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(index(w.handle(get(repo, "index.yaml"))).size()).isEqualTo(1);
        }

        @Test
        void unreadableEntryIsLeftOutOfTheIndex() {
            w.upload(repo, TarFixtures.chart("demo", "1.0.0"));
            w.upload(repo, TarFixtures.chart("good", "1.0.0"));
            w.storage.put(repo, "entries/demo/1.0.0.json", "{not json".getBytes(), "application/json", true);
            HelmIndex index = index(w.handle(get(repo, "index.yaml")));
            assertThat(index.entries().keySet()).containsExactly("good");
        }

        @Test
        void relativeUrlsSetting() {
            repo.setFormatSettings(Map.of("helm.relativeUrls", "true"));
            w.upload(repo, TarFixtures.chart("demo", "1.0.0+build.7"));
            HelmIndex index = index(w.handle(get(repo, "index.yaml")));
            assertThat(index.find("demo", "1.0.0+build.7").orElseThrow().urls()).containsExactly("charts/demo-1.0.0+build.7.tgz");
            assertThat(json(w.handle(get(repo, "api/charts/demo/1.0.0+build.7"))).path("urls").get(0).asString())
                    .isEqualTo("charts/demo-1.0.0+build.7.tgz");
        }

        @Test
        void artifactHubMetadata() {
            String yaml = "repositoryID: 0b7d2b6c\nowners:\n  - name: Jane\n    email: jane@example.com\n";
            assertThat(w.handle(get(repo, "artifacthub-repo.yml")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(w.handle(request(repo, "PUT", "artifacthub-repo.yml", yaml.getBytes(), "application/x-yaml"))
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(text(w.handle(get(repo, "artifacthub-repo.yml")))).isEqualTo(yaml);
            assertThat(w.handle(request(repo, "PUT", "artifacthub-repo.yml", "a: [".getBytes(), null)).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(w.handle(request(repo, "DELETE", "artifacthub-repo.yml", null, null)).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(w.handle(request(repo, "DELETE", "artifacthub-repo.yml", null, null)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void pathsAndMethodsThatDoNotExist() {
            assertThat(w.handle(get(repo, "charts/a%20b.tgz")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(w.handle(get(repo, "charts/..")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(w.handle(get(repo, "entries/demo/1.0.0.json")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(w.handle(get(repo, "charts/missing-1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(json(w.handle(get(repo, "charts/missing-1.0.0.tgz.prov"))).path("error").asString())
                    .isEqualTo("provenance file not found: missing-1.0.0.tgz.prov");
            assertThat(w.handle(get(repo, "charts/missing/1.0.0/missing-1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(w.handle(request(repo, "POST", "index.yaml", null, null)).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(w.handle(request(repo, "DELETE", "", null, null)).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(w.handle(request(repo, "PATCH", "charts/a-1.0.0.tgz", null, null)).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(w.handle(request(repo, "PUT", "charts/a/1.0.0/a-1.0.0.tgz", null, null)).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(w.handle(request(repo, "PUT", "api/charts", null, null)).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(w.handle(request(repo, "DELETE", "api/charts/demo", null, null)).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(w.handle(request(repo, "GET", "api/prov", null, null)).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(w.handle(request(repo, "POST", "health", null, null)).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(w.handle(request(repo, "POST", "artifacthub-repo.yml", null, null)).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(w.handle(get(repo, "api/charts/de%20mo")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(w.handle(get(repo, "nothing/here")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void methodNotAllowedNamesTheMethodsThePathSupports() {
            assertThat(w.handle(request(repo, "POST", "index.yaml", null, null)).getHeaders().getFirst(HttpHeaders.ALLOW))
                    .isEqualTo("GET, HEAD");
            assertThat(w.handle(request(repo, "PATCH", "charts/a-1.0.0.tgz", null, null)).getHeaders().getFirst(HttpHeaders.ALLOW))
                    .isEqualTo("GET, HEAD, PUT, DELETE");
            assertThat(w.handle(request(repo, "PUT", "api/charts", null, null)).getHeaders().getFirst(HttpHeaders.ALLOW))
                    .isEqualTo("GET, HEAD, POST");
            assertThat(w.handle(request(repo, "PUT", "api/charts/a/1.0.0", null, null)).getHeaders().getFirst(HttpHeaders.ALLOW))
                    .isEqualTo("GET, HEAD, DELETE");
            assertThat(w.handle(request(repo, "GET", "api/prov", null, null)).getHeaders().getFirst(HttpHeaders.ALLOW))
                    .isEqualTo("POST");
            assertThat(w.handle(request(repo, "PUT", "charts/a/1.0.0/a-1.0.0.tgz", null, null)).getHeaders().getFirst(HttpHeaders.ALLOW))
                    .isEqualTo("GET, HEAD");
            for (HelmPath.Kind kind : HelmPath.Kind.values()) {
                assertThat(HelmFormatHandler.allowedMethods(kind)).startsWith(kind == HelmPath.Kind.API_PROV ? "POST" : "GET, HEAD");
            }
        }

        @Test
        void landingPage() {
            ResponseEntity<?> page = w.handle(get(repo, ""));
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(text(page)).contains("helm repo add granary http://granary.test/repository/helm-hosted").contains("api/charts");
            Repository evil = HelmTestSupport.hosted("<script>");
            assertThat(text(w.handle(get(evil, "")))).doesNotContain("<script>");
        }

        @Test
        void indexRendersAreCachedByContent() {
            w.upload(repo, TarFixtures.chart("demo", "1.0.0"));
            byte[] first = HelmTestSupport.body(w.handle(get(repo, "index.yaml")));
            byte[] second = HelmTestSupport.body(w.handle(get(repo, "index.yaml")));
            assertThat(second).isEqualTo(first);
        }
    }

    @Nested
    class Proxy {

        static final String REMOTE = "https://charts.example.com/stable";
        static final String INDEX = REMOTE + "/index.yaml";
        Repository repo = HelmTestSupport.proxy("helm-proxy", REMOTE);

        String upstreamIndex(String... nameVersionUrl) {
            StringBuilder sb = new StringBuilder("apiVersion: v1\nentries:\n");
            for (int i = 0; i < nameVersionUrl.length; i += 3) {
                sb.append("  ").append(nameVersionUrl[i]).append(":\n  - name: ").append(nameVersionUrl[i])
                        .append("\n    version: ").append(nameVersionUrl[i + 1]).append("\n    digest: abc\n    urls:\n    - ")
                        .append(nameVersionUrl[i + 2]).append('\n');
            }
            return sb.append("generated: \"2024-01-01T00:00:00Z\"\n").toString();
        }

        @Test
        void indexUrlsAreRewrittenToTheRepository() {
            w.upstream.serve(INDEX, upstreamIndex("cert-manager", "v1.14.0", "charts/cert-manager-v1.14.0.tgz",
                    "hello-world", "0.1.0", "https://github.com/helm/examples/releases/download/hello-world-0.1.0/hello-world-0.1.0.tgz"));
            ResponseEntity<?> response = w.handle(get(repo, "index.yaml"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            HelmIndex index = index(response);
            assertThat(index.find("cert-manager", "v1.14.0").orElseThrow().urls())
                    .containsExactly("http://granary.test/repository/helm-proxy/charts/cert-manager-v1.14.0.tgz");
            assertThat(index.find("hello-world", "0.1.0").orElseThrow().urls())
                    .containsExactly("http://granary.test/repository/helm-proxy/charts/hello-world-0.1.0.tgz");
            assertThat(index.find("hello-world", "0.1.0").orElseThrow().digest()).isEqualTo("abc");
            assertThat(text(response)).doesNotContain("github.com").doesNotContain("charts.example.com");
            // the raw upstream document is what is cached
            assertThat(w.storage.text(repo, "index.yaml")).contains("github.com");
            assertThat(w.storage.asset(repo, "index.yaml").isMetadata()).isTrue();
        }

        @Test
        void chartsAreFetchedFromWhereTheIndexSaysAndCachedForever() {
            String github = "https://github.com/helm/examples/releases/download/hello-world-0.1.0/hello-world-0.1.0.tgz";
            w.upstream.serve(INDEX, upstreamIndex("hello-world", "0.1.0", github));
            byte[] tgz = TarFixtures.chart("hello-world", "0.1.0");
            w.upstream.serve(github, tgz);
            for (int i = 0; i < 3; i++) {
                ResponseEntity<?> response = w.handle(get(repo, "charts/hello-world-0.1.0.tgz"));
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(HelmTestSupport.body(response)).isEqualTo(tgz);
            }
            assertThat(w.upstream.count(github)).isEqualTo(1);
            assertThat(w.storage.asset(repo, "charts/hello-world-0.1.0.tgz").isMetadata()).isFalse();
            assertThat(w.storage.asset(repo, "charts/hello-world-0.1.0.tgz").getComponentId()).isNotNull();
            assertThat(w.storage.components.values()).anySatisfy(c -> {
                assertThat(c.getName()).isEqualTo("hello-world");
                assertThat(c.getVersion()).isEqualTo("0.1.0");
            });

            // provenance files live next to the archive upstream
            w.upstream.serve(github + ".prov", "signature".getBytes());
            assertThat(text(w.handle(get(repo, "charts/hello-world-0.1.0.tgz.prov")))).isEqualTo("signature");
        }

        @Test
        void anErrorPageInsteadOfAnArchiveIsNotCached() {
            byte[] tgz = TarFixtures.chart("demo", "1.0.0");
            String url = REMOTE + "/demo-1.0.0.tgz";
            w.upstream.serve(INDEX, upstreamIndex("demo", "1.0.0", "demo-1.0.0.tgz"));
            w.upstream.serve(url, "<html>error page</html>");
            ResponseEntity<?> response = w.handle(get(repo, "charts/demo-1.0.0.tgz"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(text(response)).contains("not a gzip-compressed chart archive");
            assertThat(w.storage.find(repo, "charts/demo-1.0.0.tgz")).isEmpty();

            // once upstream serves an archive it is cached
            w.upstream.serve(url, tgz);
            assertThat(HelmTestSupport.body(w.handle(get(repo, "charts/demo-1.0.0.tgz")))).isEqualTo(tgz);
            assertThat(w.storage.find(repo, "charts/demo-1.0.0.tgz")).isPresent();
            // provenance files are text and taken as they come
            w.upstream.serve(url + ".prov", "anything");
            assertThat(text(w.handle(get(repo, "charts/demo-1.0.0.tgz.prov")))).isEqualTo("anything");
        }

        @Test
        void aDigestThatDiffersFromTheIndexIsServedLikeHelmWouldAccept() {
            // helm does not check the index digest, and public repositories with rebuilt release assets exist
            byte[] tgz = TarFixtures.chart("demo", "1.0.0");
            w.upstream.serve(INDEX, upstreamIndex("demo", "1.0.0", "demo-1.0.0.tgz").replace("digest: abc",
                    "digest: " + "0".repeat(64)));
            w.upstream.serve(REMOTE + "/demo-1.0.0.tgz", tgz);
            assertThat(HelmTestSupport.body(w.handle(get(repo, "charts/demo-1.0.0.tgz")))).isEqualTo(tgz);
            assertThat(w.storage.find(repo, "charts/demo-1.0.0.tgz")).isPresent();
        }

        @Test
        void cachedChartsAreServedOfflineWithoutTheIndex() {
            w.upstream.serve(INDEX, upstreamIndex("demo", "1.0.0", "demo-1.0.0.tgz"));
            w.upstream.serve(REMOTE + "/demo-1.0.0.tgz", TarFixtures.chart("demo", "1.0.0"));
            w.handle(get(repo, "index.yaml"));
            assertThat(w.handle(get(repo, "charts/demo-1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.OK);

            w.upstream.offline = true;
            w.upstream.documents.clear();
            assertThat(w.handle(get(repo, "charts/demo-1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(w.handle(get(repo, "index.yaml")).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(w.handle(get(repo, "charts/other-1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void nothingCachedAndUpstreamDownIs404ForTheDispatcherToTurnInto503() {
            w.upstream.offline = true;
            assertThat(w.handle(get(repo, "index.yaml")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(w.handle(get(repo, "charts/demo-1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(w.handle(get(repo, "api/charts")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(w.handle(get(repo, "charts/demo/1.0.0/demo-1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void chartsNotInTheIndexAreNotGuessed() {
            w.upstream.serve(INDEX, upstreamIndex("demo", "1.0.0", "demo-1.0.0.tgz"));
            assertThat(w.handle(get(repo, "charts/secret-1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(w.upstream.requested).containsOnly(INDEX);
        }

        @Test
        void garbageFromUpstreamIsABadGateway() {
            w.upstream.serve(INDEX, "<html><body>Sign in</body></html>");
            ResponseEntity<?> response = w.handle(get(repo, "index.yaml"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(json(response).path("error").asString()).contains("did not serve a valid index.yaml");
            assertThat(w.handle(get(repo, "charts/demo-1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void collidingFileNamesAreServedByChartAndVersion() {
            w.upstream.serve(INDEX, upstreamIndex("a", "1.0.0", "https://a.example/1/chart.tgz",
                    "b", "1.0.0", "https://a.example/2/chart.tgz"));
            w.upstream.serve("https://a.example/1/chart.tgz", TarFixtures.chart("a", "1.0.0"));
            w.upstream.serve("https://a.example/2/chart.tgz", TarFixtures.chart("b", "1.0.0"));
            HelmIndex index = index(w.handle(get(repo, "index.yaml")));
            String urlA = index.find("a", "1.0.0").orElseThrow().urls().getFirst();
            assertThat(urlA).isEqualTo("http://granary.test/repository/helm-proxy/charts/a/1.0.0/chart.tgz");
            assertThat(HelmTestSupport.body(w.handle(get(repo, "charts/a/1.0.0/chart.tgz")))).isEqualTo(TarFixtures.chart("a", "1.0.0"));
            assertThat(HelmTestSupport.body(w.handle(get(repo, "charts/b/1.0.0/chart.tgz")))).isEqualTo(TarFixtures.chart("b", "1.0.0"));
        }

        @Test
        void apiAndArtifactHubThroughTheProxy() {
            w.upstream.serve(INDEX, upstreamIndex("demo", "1.0.0", "demo-1.0.0.tgz"));
            w.upstream.serve(REMOTE + "/artifacthub-repo.yml", "repositoryID: x\n");
            assertThat(json(w.handle(get(repo, "api/charts/demo/1.0.0"))).path("urls").get(0).asString())
                    .isEqualTo("http://granary.test/repository/helm-proxy/charts/demo-1.0.0.tgz");
            assertThat(text(w.handle(get(repo, "artifacthub-repo.yml")))).isEqualTo("repositoryID: x\n");
        }

        @Test
        void writesAreRefusedWith405() {
            ResponseEntity<?> upload = w.upload(repo, TarFixtures.chart("demo", "1.0.0"));
            assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(upload.getHeaders().getFirst(HttpHeaders.ALLOW)).isEqualTo("GET, HEAD");
            assertThat(upload.getHeaders().getContentType()).hasToString("application/json");
            assertThat(json(upload).path("error").asString()).contains("proxy repository");
            assertThat(w.handle(request(repo, "DELETE", "api/charts/demo/1.0.0", null, null)).getStatusCode())
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(w.handle(request(repo, "PUT", "demo-1.0.0.tgz", new byte[] {1}, null)).getStatusCode())
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(w.upstream.requested).isEmpty();
        }

        @Test
        void aPastedIndexUrlStillWorks() {
            Repository pasted = HelmTestSupport.proxy("pasted", INDEX);
            w.upstream.serve(INDEX, upstreamIndex("demo", "1.0.0", "demo-1.0.0.tgz"));
            assertThat(index(w.handle(get(pasted, "index.yaml"))).size()).isEqualTo(1);
        }

        @Test
        void aRemoteUrlWithAQueryStringKeepsItOnTheIndexAndRelativeChartUrls() {
            Repository signed = HelmTestSupport.proxy("signed", "https://store.example.net/charts?sig=abc");
            w.upstream.serve("https://store.example.net/charts/index.yaml?sig=abc", upstreamIndex("demo", "1.0.0", "demo-1.0.0.tgz"));
            byte[] demo = TarFixtures.chart("demo", "1.0.0");
            w.upstream.serve("https://store.example.net/charts/demo-1.0.0.tgz?sig=abc", demo);

            HelmIndex index = index(w.handle(get(signed, "index.yaml")));
            assertThat(index.find("demo", "1.0.0").orElseThrow().urls())
                    .containsExactly(HelmTestSupport.SERVER + "/repository/signed/charts/demo-1.0.0.tgz");
            assertThat(HelmTestSupport.body(w.handle(get(signed, "charts/demo-1.0.0.tgz")))).isEqualTo(demo);
            assertThat(w.upstream.requested).containsOnly(
                    "https://store.example.net/charts/index.yaml?sig=abc",
                    "https://store.example.net/charts/demo-1.0.0.tgz?sig=abc");
        }
    }

    @Nested
    class Group {

        Repository hosted = HelmTestSupport.hosted("helm-hosted");
        Repository proxy = HelmTestSupport.proxy("helm-proxy", "https://charts.example.com");
        Repository group = HelmTestSupport.group("helm-group", "helm-hosted", "helm-proxy");

        @BeforeEach
        void members() {
            w.members(group, hosted, proxy);
            w.upstream.serve("https://charts.example.com/index.yaml", """
                    apiVersion: v1
                    entries:
                      demo:
                      - {name: demo, version: 1.0.0, digest: upstream, urls: [charts/demo-1.0.0.tgz]}
                      - {name: demo, version: 0.9.0, digest: upstream-old, urls: [charts/demo-0.9.0.tgz]}
                      nginx:
                      - {name: nginx, version: 2.0.0, urls: ['https://cdn.example/nginx-2.0.0.tgz']}
                    """);
        }

        @Test
        void indexIsMergedAndPointsAtTheGroup() {
            byte[] own = TarFixtures.chart("demo", "1.0.0");
            w.upload(hosted, own);
            w.upload(hosted, TarFixtures.chart("internal", "0.1.0"));
            ResponseEntity<?> response = w.handle(get(group, "index.yaml"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(text(response)).contains("contextPath: /repository/helm-group");
            HelmIndex index = index(response);
            assertThat(index.entries().keySet()).containsExactly("demo", "internal", "nginx");
            assertThat(index.versions("demo")).extracting(ChartEntry::version).containsExactly("1.0.0", "0.9.0");
            ChartEntry demo = index.find("demo", "1.0.0").orElseThrow();
            assertThat(demo.digest()).isEqualTo(HelmTestSupport.sha256(own)); // the hosted member wins
            assertThat(demo.urls()).containsExactly("http://granary.test/repository/helm-group/charts/demo-1.0.0.tgz");
            assertThat(index.find("nginx", "2.0.0").orElseThrow().urls())
                    .containsExactly("http://granary.test/repository/helm-group/charts/nginx-2.0.0.tgz");
        }

        @Test
        void chartsComeFromTheMemberThatOwnsThem() {
            byte[] own = TarFixtures.chart("demo", "1.0.0");
            w.upload(hosted, own);
            byte[] upstreamNginx = TarFixtures.chart("nginx", "2.0.0");
            w.upstream.serve("https://cdn.example/nginx-2.0.0.tgz", upstreamNginx);
            assertThat(HelmTestSupport.body(w.handle(get(group, "charts/demo-1.0.0.tgz")))).isEqualTo(own);
            assertThat(HelmTestSupport.body(w.handle(get(group, "charts/nginx-2.0.0.tgz")))).isEqualTo(upstreamNginx);
            assertThat(w.upstream.requested).doesNotContain("https://charts.example.com/charts/demo-1.0.0.tgz");
            assertThat(w.storage.paths(proxy)).contains("charts/nginx-2.0.0.tgz");
            assertThat(HelmTestSupport.body(w.handle(get(group, "charts/demo/1.0.0/demo-1.0.0.tgz")))).isEqualTo(own);
            assertThat(w.handle(get(group, "charts/nope-1.0.0.tgz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void apiOfTheGroup() {
            w.upload(hosted, TarFixtures.chart("internal", "0.1.0"));
            JsonNode all = json(w.handle(get(group, "api/charts")));
            assertThat(all.propertyNames()).containsExactly("demo", "internal", "nginx");
            assertThat(json(w.handle(get(group, "api/charts/demo/latest"))).path("version").asString()).isEqualTo("1.0.0");
        }

        @Test
        void writesAreRefusedAndNameTheHostedMember() {
            ResponseEntity<?> response = w.upload(group, TarFixtures.chart("demo", "1.0.0"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(json(response).path("error").asString()).contains("group repository").contains("use its hosted member 'helm-hosted'");
            Repository proxiesOnly = HelmTestSupport.group("proxies", "helm-proxy");
            w.members(proxiesOnly, proxy);
            assertThat(json(w.upload(proxiesOnly, TarFixtures.chart("demo", "1.0.0"))).path("error").asString())
                    .contains("it has no hosted member");
        }

        @Test
        void unavailableProxyMemberDoesNotHideHostedCharts() {
            w.upload(hosted, TarFixtures.chart("internal", "0.1.0"));
            w.upstream.offline = true;
            HelmIndex index = index(w.handle(get(group, "index.yaml")));
            assertThat(index.entries().keySet()).containsExactly("internal");
        }

        @Test
        void brokenProxyMemberDoesNotBreakTheGroup() {
            w.upstream.serve("https://charts.example.com/index.yaml", "<html>oops</html>");
            w.upload(hosted, TarFixtures.chart("internal", "0.1.0"));
            assertThat(index(w.handle(get(group, "index.yaml"))).entries().keySet()).containsExactly("internal");
        }

        @Test
        void groupWithNothingAvailableIs404() {
            Repository proxiesOnly = HelmTestSupport.group("proxies", "helm-proxy");
            w.members(proxiesOnly, proxy);
            w.upstream.offline = true;
            assertThat(w.handle(get(proxiesOnly, "index.yaml")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void collidingPathsAcrossMembersAreReaddressed() {
            Repository other = HelmTestSupport.proxy("other", "https://other.example");
            Repository three = HelmTestSupport.group("three", "helm-hosted", "other");
            w.members(three, hosted, other);
            w.upload(hosted, TarFixtures.chart("demo", "1.0.0"));
            w.upstream.serve("https://other.example/index.yaml", """
                    apiVersion: v1
                    entries:
                      impostor:
                      - {name: impostor, version: 3.0.0, urls: [demo-1.0.0.tgz]}
                    """);
            byte[] impostor = TarFixtures.chart("impostor", "3.0.0");
            w.upstream.serve("https://other.example/demo-1.0.0.tgz", impostor);
            HelmIndex index = index(w.handle(get(three, "index.yaml")));
            assertThat(index.find("impostor", "3.0.0").orElseThrow().urls())
                    .containsExactly("http://granary.test/repository/three/charts/impostor/3.0.0/demo-1.0.0.tgz");
            assertThat(HelmTestSupport.body(w.handle(get(three, "charts/impostor/3.0.0/demo-1.0.0.tgz")))).isEqualTo(impostor);
            assertThat(HelmTestSupport.body(w.handle(get(three, "charts/demo-1.0.0.tgz")))).isEqualTo(TarFixtures.chart("demo", "1.0.0"));
        }

        @Test
        void artifactHubMetadataFromTheFirstMemberThatHasIt() {
            w.upstream.serve("https://charts.example.com/artifacthub-repo.yml", "repositoryID: upstream\n");
            assertThat(text(w.handle(get(group, "artifacthub-repo.yml")))).isEqualTo("repositoryID: upstream\n");
        }
    }

    @Test
    void storageCoordinatesAreNamespaceless() {
        Repository repo = HelmTestSupport.hosted("h");
        w.upload(repo, TarFixtures.chart("demo", "1.0.0"));
        assertThat(w.storage.coordinates.values()).extracting(StorageService.ComponentCoordinates::namespace).containsOnly("");
        assertThat(w.storage.components).hasSize(1);
        assertThat(List.copyOf(w.storage.components.values()).getFirst().getFormat()).isEqualTo("helm");
    }
}
