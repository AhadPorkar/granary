package dev.granary.format.pypi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PypiJsonApiTest {

    private static final String BASE = "http://granary.test/repository/pypi-hosted";
    private static final Instant UPLOADED = Instant.parse("2026-01-02T03:04:05.123456Z");

    private final ObjectMapper json = JsonMapper.builder().build();
    private final PypiJsonApi api = new PypiJsonApi(json);

    private static PypiHosted.HostedFile hosted(String filename, String version, String metadata) {
        PypiNames.Distribution d = PypiNames.parseFilename(filename).orElseThrow();
        String path = PypiHosted.filePath(d.normalisedName(), version, filename);
        PypiFile file = PypiFile.of(filename, BASE + "/" + path, "sha-" + filename).withSize(123L)
                .withRequiresPython(metadata == null ? null : PypiMetadata.parse(metadata).requiresPython().orElse(null));
        return new PypiHosted.HostedFile(file, d, metadata == null ? null : PypiMetadata.parse(metadata), UPLOADED, path);
    }

    private List<PypiHosted.HostedFile> files() {
        return List.of(
                hosted("acme_demo-1.0.0-py3-none-any.whl", "1.0.0", PypiTestSupport.metadata("acme-demo", "1.0.0", ">=3.8", "First")),
                hosted("acme_demo-1.0.0.tar.gz", "1.0.0", PypiTestSupport.metadata("acme-demo", "1.0.0", ">=3.8", "First")),
                hosted("acme_demo-2.0.0rc1-py3-none-any.whl", "2.0.0rc1", PypiTestSupport.metadata("acme-demo", "2.0.0rc1", ">=3.9", "Candidate")),
                hosted("acme_demo-1.5.0.tar.gz", "1.5.0", null));
    }

    @Test
    void projectDocumentDescribesTheLatestFinalRelease() {
        ObjectNode doc = api.project("acme-demo", files(), BASE);
        JsonNode info = doc.path("info");
        assertThat(info.path("name").asString()).isEqualTo("acme-demo");
        assertThat(info.path("version").asString()).isEqualTo("1.5.0");
        assertThat(info.path("summary").isNull()).isTrue();
        assertThat(info.path("package_url").asString()).isEqualTo(BASE + "/simple/acme-demo/");
        assertThat(info.path("release_url").asString()).isEqualTo(BASE + "/pypi/acme-demo/1.5.0/json");
        assertThat(info.path("yanked").booleanValue()).isFalse();
        assertThat(doc.path("releases").propertyNames()).containsExactlyInAnyOrder("1.0.0", "1.5.0", "2.0.0rc1");
        assertThat(doc.path("releases").path("1.0.0")).hasSize(2);
        assertThat(doc.path("urls")).hasSize(1);
        assertThat(doc.path("urls").get(0).path("filename").asString()).isEqualTo("acme_demo-1.5.0.tar.gz");
        assertThat(doc.path("vulnerabilities")).isEmpty();
        assertThat(doc.path("last_serial").intValue()).isZero();

        JsonNode wheel = doc.path("releases").path("1.0.0").get(0);
        assertThat(wheel.path("packagetype").asString()).isEqualTo("bdist_wheel");
        assertThat(wheel.path("python_version").asString()).isEqualTo("py3");
        assertThat(wheel.path("requires_python").asString()).isEqualTo(">=3.8");
        assertThat(wheel.path("digests").path("sha256").asString()).isEqualTo("sha-acme_demo-1.0.0-py3-none-any.whl");
        assertThat(wheel.path("url").asString()).isEqualTo(BASE + "/packages/acme-demo/1.0.0/acme_demo-1.0.0-py3-none-any.whl");
        assertThat(wheel.path("size").longValue()).isEqualTo(123L);
        assertThat(wheel.path("upload_time").asString()).isEqualTo("2026-01-02T03:04:05");
        assertThat(wheel.path("upload_time_iso_8601").asString()).isEqualTo("2026-01-02T03:04:05.123456Z");
        assertThat(wheel.path("yanked").booleanValue()).isFalse();
        assertThat(doc.path("releases").path("1.0.0").get(1).path("packagetype").asString()).isEqualTo("sdist");
        assertThat(doc.path("releases").path("1.0.0").get(1).path("python_version").asString()).isEqualTo("source");
    }

    @Test
    void infoComesFromTheSidecarMetadata() {
        ObjectNode doc = api.release("acme-demo", files(), "1.0.0", BASE).orElseThrow();
        JsonNode info = doc.path("info");
        assertThat(info.path("version").asString()).isEqualTo("1.0.0");
        assertThat(info.path("summary").asString()).isEqualTo("First");
        assertThat(info.path("author_email").asString()).isEqualTo("Acme <dev@acme.example>");
        assertThat(info.path("requires_python").asString()).isEqualTo(">=3.8");
        assertThat(info.path("description").asString()).startsWith("# acme-demo");
        assertThat(info.path("description_content_type").asString()).isEqualTo("text/markdown");
        assertThat(info.path("classifiers")).extracting(JsonNode::asString).containsExactly("Programming Language :: Python :: 3");
        assertThat(info.path("requires_dist")).extracting(JsonNode::asString).containsExactly("six>=1.16");
        assertThat(info.path("project_urls").path("Homepage").asString()).isEqualTo("https://example.com/acme-demo");
        assertThat(doc.path("urls")).hasSize(2);
        assertThat(doc.has("releases")).isFalse();
    }

    @Test
    void releaseVersionsMatchByPep440Equality() {
        assertThat(api.release("acme-demo", files(), "1.0", BASE)).hasValueSatisfying(doc ->
                assertThat(doc.path("info").path("version").asString()).isEqualTo("1.0.0"));
        assertThat(api.release("acme-demo", files(), "2.0.0rc1", BASE)).isPresent();
        assertThat(api.release("acme-demo", files(), "3.0", BASE)).isEmpty();
        assertThat(api.release("acme-demo", files(), "junk", BASE)).isEmpty();
    }

    @Test
    void rewritesUpstreamFileUrls() {
        ObjectNode upstream = (ObjectNode) json.readTree("""
                {"info": {"name": "six", "version": "1.17.0", "package_url": "https://pypi.org/project/six/"},
                 "releases": {"1.16.0": [{"filename": "six-1.16.0.tar.gz", "url": "https://files.pythonhosted.org/packages/a/b/six-1.16.0.tar.gz"}],
                              "1.17.0": [{"filename": "six-1.17.0.tar.gz", "url": "https://files.pythonhosted.org/packages/c/d/six-1.17.0.tar.gz"}, {"nourl": 1}],
                              "odd": "not-a-list"},
                 "urls": [{"filename": "six-1.17.0.tar.gz", "url": "https://files.pythonhosted.org/packages/c/d/six-1.17.0.tar.gz"}],
                 "last_serial": 42}
                """);
        ObjectNode rewritten = api.rewrite(upstream, "six", "http://granary.test/repository/pypi-group", PypiProxy.DEFAULT_FILES_URL);
        assertThat(rewritten.path("urls").get(0).path("url").asString()).isEqualTo("http://granary.test/repository/pypi-group/packages/c/d/six-1.17.0.tar.gz");
        assertThat(rewritten.path("releases").path("1.16.0").get(0).path("url").asString()).isEqualTo("http://granary.test/repository/pypi-group/packages/a/b/six-1.16.0.tar.gz");
        assertThat(rewritten.path("info").path("package_url").asString()).isEqualTo("https://pypi.org/project/six/");
        assertThat(rewritten.path("last_serial").intValue()).isEqualTo(42);
        // the upstream document is untouched
        assertThat(upstream.path("urls").get(0).path("url").asString()).startsWith("https://files.pythonhosted.org/");
        assertThat(api.rewrite(json.readTree("[]"), "six", BASE, PypiProxy.DEFAULT_FILES_URL).isEmpty()).isTrue();
    }

    @Test
    void filesOutsideTheFilesHostAreAddressedByProjectAndFileName() {
        ObjectNode upstream = (ObjectNode) json.readTree("""
                {"info": {"name": "acme", "version": "1.0"},
                 "urls": [{"filename": "acme-1.0.tar.gz", "url": "https://nexus.example/repository/pypi/packages/acme/1.0/acme-1.0.tar.gz"},
                          {"filename": "acme-1.0-py3-none-any.whl", "url": "https://devpi.example/root/dev/+f/0a1/acme-1.0-py3-none-any.whl"}]}
                """);
        ObjectNode rewritten = api.rewrite(upstream, "acme", BASE, PypiProxy.DEFAULT_FILES_URL);
        assertThat(rewritten.path("urls").get(0).path("url").asString()).isEqualTo(BASE + "/packages/-/acme/acme-1.0.tar.gz");
        assertThat(rewritten.path("urls").get(1).path("url").asString()).isEqualTo(BASE + "/packages/-/acme/acme-1.0-py3-none-any.whl");
        // the same document behind a proxy whose files host is that Nexus keeps the path
        ObjectNode mirrored = api.rewrite(upstream, "acme", BASE, "https://nexus.example/repository/pypi/");
        assertThat(mirrored.path("urls").get(0).path("url").asString()).isEqualTo(BASE + "/packages/acme/1.0/acme-1.0.tar.gz");
    }

    @Test
    void mergesMembersReleaseByRelease() {
        ObjectNode hosted = api.project("acme-demo", files().subList(0, 2), BASE);
        ObjectNode upstream = (ObjectNode) json.readTree("""
                {"info": {"name": "acme-demo", "version": "1.2.0", "summary": "upstream"},
                 "releases": {"1.0.0": [{"filename": "acme_demo-1.0.0.tar.gz", "url": "u-old"}, {"filename": "acme_demo-1.0.0-py2-none-any.whl", "url": "u-py2"}],
                              "1.2.0": [{"filename": "acme_demo-1.2.0.tar.gz", "url": "u-12"}]},
                 "urls": [{"filename": "acme_demo-1.2.0.tar.gz", "url": "u-12"}]}
                """);
        ObjectNode merged = api.merge(List.of(hosted, upstream));
        assertThat(merged.path("info").path("version").asString()).isEqualTo("1.2.0");
        assertThat(merged.path("info").path("summary").asString()).isEqualTo("upstream");
        assertThat(merged.path("releases").propertyNames()).containsExactly("1.0.0", "1.2.0");
        JsonNode v100 = merged.path("releases").path("1.0.0");
        assertThat(v100).extracting(f -> f.path("filename").asString())
                .containsExactly("acme_demo-1.0.0-py3-none-any.whl", "acme_demo-1.0.0.tar.gz", "acme_demo-1.0.0-py2-none-any.whl");
        assertThat(v100.get(1).path("url").asString()).startsWith(BASE); // hosted member wins the duplicate
        assertThat(merged.path("urls")).extracting(f -> f.path("filename").asString()).containsExactly("acme_demo-1.2.0.tar.gz");

        ObjectNode hostedWins = api.merge(List.of(hosted, (ObjectNode) json.readTree("{\"info\":{\"version\":\"0.1\"},\"releases\":{\"0.1\":[{\"filename\":\"a-0.1.tar.gz\"}]}}")));
        assertThat(hostedWins.path("info").path("version").asString()).isEqualTo("1.0.0");
        assertThat(hostedWins.path("info").path("summary").asString()).isEqualTo("First");
    }

    @Test
    void mergingOneOrDegenerateDocumentsDoesNotFail() {
        ObjectNode single = (ObjectNode) json.readTree("{\"info\":{\"version\":\"1.0\"},\"urls\":[{\"filename\":\"a-1.0.tar.gz\"}]}");
        ObjectNode merged = api.merge(List.of(single));
        assertThat(merged.path("releases").path("1.0")).hasSize(1);
        assertThat(merged.path("urls")).hasSize(1);
        ObjectNode empty = api.merge(List.of(json.createObjectNode()));
        assertThat(empty.path("releases").isEmpty()).isTrue();
        ObjectNode noInfo = api.merge(List.of((ObjectNode) json.readTree("{\"releases\":{\"2.0\":[{\"filename\":\"x\"}]}}"), json.createObjectNode()));
        assertThat(noInfo.path("info").path("version").asString()).isEqualTo("2.0");
        assertThat(Map.of()).isEmpty();
    }
}
