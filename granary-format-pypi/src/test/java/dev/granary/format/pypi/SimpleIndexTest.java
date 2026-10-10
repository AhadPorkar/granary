package dev.granary.format.pypi;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SimpleIndexTest {

    private static final URI PAGE = URI.create("https://pypi.org/simple/six/");
    private static final String SHA = "8abb2f1d86890a2dfb989f9a77cfd3f28b3a5f0e6e1d4e0f3b2a1c0d9e8f7a6b";

    /** The shape pypi.org serves, including the attributes pip reads and one it does not know. */
    private static final String PYPI_HTML = """
            <!DOCTYPE html>
            <html>
              <head>
                <meta name="pypi:repository-version" content="1.1">
                <title>Links for six</title>
              </head>
              <body>
                <h1>Links for six</h1>
            <a href="https://files.pythonhosted.org/packages/71/39/171f1c67cd00a6a43a84e01d4d3c7a3cf1b6a4ebc7b7a5a0e5c5f2d3a1b2/six-1.15.0.tar.gz#sha256=aaaa" data-requires-python="&gt;=2.7, !=3.0.*, !=3.1.*, !=3.2.*" data-yanked="broken">six-1.15.0.tar.gz</a><br />
            <a href="https://files.pythonhosted.org/packages/d9/5a/e7c31adbe875f2abbb91bd84cf2dc52d792b5a01506781dbcf25c91daf11/six-1.16.0-py2.py3-none-any.whl#sha256=%s" data-requires-python=">=2.7, !=3.0.*, !=3.1.*, !=3.2.*" data-dist-info-metadata="sha256=1111" data-core-metadata="sha256=1111" data-gpg-sig="false" data-provenance="https://example/prov">six-1.16.0-py2.py3-none-any.whl</a><br />
            <A HREF='../../packages/source/s/six/six-1.16.0.tar.gz#sha256=bbbb' data-yanked>six-1.16.0.tar.gz</A><br/>
            <a href="https://files.pythonhosted.org/packages/x/y/z/six-1.17.0.tar.gz" data-core-metadata="true">six-1.17.0.tar.gz</a>
            <a name="anchor-without-href">ignored</a>
              </body>
            </html>
            """.formatted(SHA);

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final SimpleIndex index = new SimpleIndex(mapper);

    @Test
    void parsesPep503Html() {
        PypiProject project = index.parseProjectHtml(PYPI_HTML, "six", PAGE);
        assertThat(project.apiVersion()).isEqualTo("1.1");
        assertThat(project.name()).isEqualTo("six");
        assertThat(project.versions()).isNull();
        assertThat(project.files()).hasSize(4);

        PypiFile sdist = project.files().get(0);
        assertThat(sdist.filename()).isEqualTo("six-1.15.0.tar.gz");
        assertThat(sdist.url()).isEqualTo("https://files.pythonhosted.org/packages/71/39/171f1c67cd00a6a43a84e01d4d3c7a3cf1b6a4ebc7b7a5a0e5c5f2d3a1b2/six-1.15.0.tar.gz");
        assertThat(sdist.hashes()).containsEntry("sha256", "aaaa");
        assertThat(sdist.requiresPython()).isEqualTo(">=2.7, !=3.0.*, !=3.1.*, !=3.2.*");
        assertThat(sdist.yanked()).isEqualTo("broken");
        assertThat(sdist.isYanked()).isTrue();
        assertThat(sdist.coreMetadata()).isNull();

        PypiFile wheel = project.files().get(1);
        assertThat(wheel.hashes()).containsEntry("sha256", SHA);
        assertThat(wheel.sha256()).contains(SHA);
        assertThat(wheel.requiresPython()).isEqualTo(">=2.7, !=3.0.*, !=3.1.*, !=3.2.*");
        assertThat(wheel.coreMetadata()).containsEntry("sha256", "1111");
        assertThat(wheel.gpgSig()).isFalse();
        assertThat(wheel.isYanked()).isFalse();
        assertThat(wheel.attributes()).containsEntry("data-provenance", "https://example/prov");
        assertThat(wheel.version()).contains("1.16.0");

        PypiFile relative = project.files().get(2);
        assertThat(relative.url()).isEqualTo("https://pypi.org/packages/source/s/six/six-1.16.0.tar.gz");
        assertThat(relative.yanked()).isEmpty();
        assertThat(relative.isYanked()).isTrue();

        PypiFile noHash = project.files().get(3);
        assertThat(noHash.hashes()).isEmpty();
        assertThat(noHash.fragment()).isEmpty();
        assertThat(noHash.coreMetadata()).isEmpty();
    }

    @Test
    void parsesHtmlWithoutPageUrlAndWithUnclosedAnchors() {
        String html = "<html><body><a href=\"a-1.0.tar.gz#md5=abc\">a-1.0.tar.gz<a href=\"b-1.0.tar.gz\">b-1.0.tar.gz</a><!-- <a href=\"c\">c</a> --></body></html>";
        PypiProject project = index.parseProjectHtml(html, "a", null);
        assertThat(project.apiVersion()).isEqualTo("1.0");
        assertThat(project.files()).extracting(PypiFile::filename).containsExactly("a-1.0.tar.gz", "b-1.0.tar.gz");
        assertThat(project.files().get(0).url()).isEqualTo("a-1.0.tar.gz");
        assertThat(project.files().get(0).hashes()).containsEntry("md5", "abc");
        assertThat(project.files().get(0).fragment()).contains("md5=abc");
    }

    @Test
    void anchorTextFallsBackToTheHref() {
        PypiProject project = index.parseProjectHtml("<a href=\"https://h/packages/a/b/c-1.0.tar.gz\"><b>bold</b></a><a href=\"d/e-2.0.whl#x\"></a>", "c", null);
        assertThat(project.files()).extracting(PypiFile::filename).containsExactly("bold", "e-2.0.whl");
    }

    @Test
    void parsesPep691Json() {
        String json = """
                {"meta": {"api-version": "1.3", "_last-serial": 7},
                 "name": "six",
                 "files": [
                   {"filename": "six-1.16.0-py2.py3-none-any.whl",
                    "url": "https://files.pythonhosted.org/packages/d9/5a/x/six-1.16.0-py2.py3-none-any.whl",
                    "hashes": {"sha256": "%s", "md5": "ff"},
                    "requires-python": ">=2.7",
                    "yanked": false,
                    "core-metadata": {"sha256": "1111"},
                    "dist-info-metadata": true,
                    "gpg-sig": true,
                    "size": 11053,
                    "upload-time": "2021-05-05T14:18:17.237Z",
                    "provenance": "https://pypi.org/integrity/six/1.16.0/x/provenance"},
                   {"filename": "six-1.15.0.tar.gz", "url": "../../packages/s/six-1.15.0.tar.gz", "hashes": {}, "yanked": "broken"},
                   {"filename": "six-1.14.0.tar.gz", "url": "x", "yanked": true, "dist-info-metadata": true},
                   {"url": "no-filename"}, "junk"
                 ],
                 "versions": ["1.14.0", "1.15.0", "1.16.0"],
                 "alternate-locations": ["https://mirror/simple/six/"]}
                """.formatted(SHA);
        PypiProject project = index.parseProjectJson(json.getBytes(StandardCharsets.UTF_8), "ignored", PAGE).orElseThrow();
        assertThat(project.name()).isEqualTo("six");
        assertThat(project.apiVersion()).isEqualTo("1.3");
        assertThat(project.versions()).containsExactly("1.14.0", "1.15.0", "1.16.0");
        assertThat(project.extra()).containsKey("alternate-locations");
        assertThat(project.files()).hasSize(3);

        PypiFile wheel = project.files().get(0);
        assertThat(wheel.hashes()).containsEntry("sha256", SHA).containsEntry("md5", "ff");
        assertThat(wheel.requiresPython()).isEqualTo(">=2.7");
        assertThat(wheel.isYanked()).isFalse();
        assertThat(wheel.coreMetadata()).containsEntry("sha256", "1111");
        assertThat(wheel.gpgSig()).isTrue();
        assertThat(wheel.size()).isEqualTo(11053L);
        assertThat(wheel.uploadTime()).isEqualTo("2021-05-05T14:18:17.237Z");
        assertThat(wheel.extra()).containsKey("provenance");

        assertThat(project.files().get(1).url()).isEqualTo("https://pypi.org/packages/s/six-1.15.0.tar.gz");
        assertThat(project.files().get(1).yanked()).isEqualTo("broken");
        assertThat(project.files().get(2).yanked()).isEmpty();
        assertThat(project.files().get(2).coreMetadata()).isEmpty();
    }

    @Test
    void detectsTheRepresentation() {
        assertThat(SimpleIndex.looksLikeJson("{}".getBytes(StandardCharsets.UTF_8), null)).isTrue();
        assertThat(SimpleIndex.looksLikeJson("  \n{}".getBytes(StandardCharsets.UTF_8), "text/html")).isFalse();
        assertThat(SimpleIndex.looksLikeJson("<html>".getBytes(StandardCharsets.UTF_8), "application/vnd.pypi.simple.v1+json")).isTrue();
        assertThat(SimpleIndex.looksLikeJson("<html>".getBytes(StandardCharsets.UTF_8), null)).isFalse();
        assertThat(SimpleIndex.looksLikeJson(new byte[0], null)).isFalse();
        assertThat(index.parseProject(PYPI_HTML.getBytes(StandardCharsets.UTF_8), "text/html", "six", PAGE)).hasValueSatisfying(p ->
                assertThat(p.files()).hasSize(4));
        assertThat(index.parseProject("{\"name\":\"six\",\"files\":[]}".getBytes(StandardCharsets.UTF_8), "application/vnd.pypi.simple.v1+json", "six", PAGE))
                .hasValueSatisfying(p -> assertThat(p.files()).isEmpty());
    }

    @Test
    void malformedJsonDoesNotThrow() {
        assertThat(index.parseProjectJson("{not json".getBytes(StandardCharsets.UTF_8), "six", PAGE)).isEmpty();
        assertThat(index.parseProjectJson("[]".getBytes(StandardCharsets.UTF_8), "six", PAGE)).isEmpty();
        assertThat(index.parseProjectJson("{}".getBytes(StandardCharsets.UTF_8), "six", PAGE)).hasValueSatisfying(p -> {
            assertThat(p.name()).isEqualTo("six");
            assertThat(p.files()).isEmpty();
        });
        assertThat(index.parseProjectList("{oops".getBytes(StandardCharsets.UTF_8), "application/json")).isEmpty();
        assertThat(index.parseProjectHtml("", "x", null).files()).isEmpty();
        assertThat(index.parseProjectHtml("<a href=", "x", null).files()).isEmpty();
        assertThat(index.parseProjectHtml("<a href=\"x\" data-yanked=\"unterminated>t</a>", "x", null).files()).hasSize(1);
    }

    @Test
    void rendersHtmlThatParsesBackIdentically() {
        PypiProject project = index.parseProjectHtml(PYPI_HTML, "six", PAGE);
        String html = index.renderProjectHtml(project);
        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<meta name=\"pypi:repository-version\" content=\"1.1\">");
        assertThat(html).contains("<title>Links for six</title>");
        assertThat(html).contains("#sha256=" + SHA + "\"");
        assertThat(html).contains("data-requires-python=\"&gt;=2.7, !=3.0.*, !=3.1.*, !=3.2.*\"");
        assertThat(html).contains("data-yanked=\"broken\"");
        assertThat(html).contains("data-yanked=\"\"");
        assertThat(html).contains("data-core-metadata=\"sha256=1111\" data-dist-info-metadata=\"sha256=1111\"");
        assertThat(html).contains("data-core-metadata=\"true\"");
        assertThat(html).contains("data-gpg-sig=\"false\"");
        assertThat(html).contains("data-provenance=\"https://example/prov\"");
        PypiProject again = index.parseProjectHtml(html, "six", PAGE);
        assertThat(again.files()).isEqualTo(project.files());
        assertThat(again.apiVersion()).isEqualTo("1.1");
    }

    @Test
    void rendersJsonThatParsesBack() {
        PypiProject project = index.parseProjectHtml(PYPI_HTML, "six", PAGE).withVersions(List.of("1.15.0", "1.16.0", "1.17.0"));
        byte[] bytes = index.renderProjectJson(project);
        JsonNode doc = mapper.readTree(bytes);
        assertThat(doc.path("meta").path("api-version").asString()).isEqualTo("1.1");
        assertThat(doc.path("name").asString()).isEqualTo("six");
        assertThat(doc.path("files")).hasSize(4);
        assertThat(doc.path("files").get(0).path("yanked").asString()).isEqualTo("broken");
        assertThat(doc.path("files").get(1).path("yanked").booleanValue()).isFalse();
        assertThat(doc.path("files").get(1).path("hashes").path("sha256").asString()).isEqualTo(SHA);
        assertThat(doc.path("files").get(1).path("core-metadata").path("sha256").asString()).isEqualTo("1111");
        assertThat(doc.path("files").get(1).path("dist-info-metadata").path("sha256").asString()).isEqualTo("1111");
        assertThat(doc.path("files").get(1).path("gpg-sig").booleanValue()).isFalse();
        assertThat(doc.path("files").get(2).path("yanked").booleanValue()).isTrue();
        assertThat(doc.path("files").get(3).path("core-metadata").booleanValue()).isTrue();
        assertThat(doc.path("files").get(3).has("requires-python")).isFalse();
        assertThat(doc.path("versions")).hasSize(3);
        PypiProject again = index.parseProjectJson(bytes, "six", null).orElseThrow();
        assertThat(again.files()).extracting(PypiFile::filename).containsExactlyElementsOf(project.files().stream().map(PypiFile::filename).toList());
        assertThat(again.files().get(1).coreMetadata()).containsEntry("sha256", "1111");
        assertThat(again.versions()).containsExactly("1.15.0", "1.16.0", "1.17.0");
    }

    @Test
    void jsonVersionsAreDerivedFromFileNamesWhenAbsent() {
        PypiProject project = PypiProject.of("six", List.of(
                PypiFile.of("six-1.16.0.tar.gz", "u", "a"),
                PypiFile.of("six-1.15.0-py2.py3-none-any.whl", "u", "b"),
                PypiFile.of("six-1.15.0.tar.gz", "u", "c"),
                PypiFile.of("not-a-dist.txt", "u", "d")));
        assertThat(project.effectiveVersions()).containsExactly("1.15.0", "1.16.0");
        JsonNode doc = mapper.readTree(index.renderProjectJson(project));
        assertThat(doc.path("versions")).extracting(JsonNode::asString).containsExactly("1.15.0", "1.16.0");
        assertThat(doc.path("files").get(0).path("hashes").path("sha256").asString()).isEqualTo("a");
    }

    @Test
    void escapesHtmlInNamesAndAttributes() {
        PypiProject project = PypiProject.of("x<y", List.of(new PypiFile("f\"<>&.tar.gz", "https://h/p?a=1&b=2", Map.of("sha256", "aa"),
                ">=3.8,<4 & \"q\"", "<b>", Map.of(), null, null, null, Map.of(), Map.of("data-x", "1<2"))));
        String html = index.renderProjectHtml(project);
        assertThat(html).contains("<title>Links for x&lt;y</title>");
        assertThat(html).contains("href=\"https://h/p?a=1&amp;b=2#sha256=aa\"");
        assertThat(html).contains("data-requires-python=\"&gt;=3.8,&lt;4 &amp; &quot;q&quot;\"");
        assertThat(html).contains("data-yanked=\"&lt;b&gt;\"");
        assertThat(html).contains("data-x=\"1&lt;2\"");
        assertThat(html).contains(">f&quot;&lt;&gt;&amp;.tar.gz</a>");
        PypiProject again = index.parseProjectHtml(html, "x<y", null);
        assertThat(again.files().get(0).filename()).isEqualTo("f\"<>&.tar.gz");
        assertThat(again.files().get(0).requiresPython()).isEqualTo(">=3.8,<4 & \"q\"");
        assertThat(again.files().get(0).url()).isEqualTo("https://h/p?a=1&b=2");
    }

    @Test
    void projectListsInBothRepresentations() {
        String html = index.renderProjectListHtml(List.of("acme-demo", "Django", "zope.interface"));
        assertThat(html).contains("<a href=\"acme-demo/\">acme-demo</a>");
        assertThat(html).contains("<a href=\"django/\">Django</a>");
        assertThat(html).contains("<a href=\"zope-interface/\">zope.interface</a>");
        assertThat(index.parseProjectList(html.getBytes(StandardCharsets.UTF_8), "text/html"))
                .contains(List.of("acme-demo", "Django", "zope.interface"));

        byte[] json = index.renderProjectListJson(List.of("acme-demo", "Django"));
        JsonNode doc = mapper.readTree(json);
        assertThat(doc.path("meta").path("api-version").asString()).isEqualTo("1.1");
        assertThat(doc.path("projects").get(1).path("name").asString()).isEqualTo("Django");
        assertThat(index.parseProjectList(json, "application/vnd.pypi.simple.v1+json")).contains(List.of("acme-demo", "Django"));

        // duplicates after normalisation collapse, href-only anchors count, and plain pypi.org markup parses
        String pypiRoot = "<!DOCTYPE html><html><body><a href=\"/simple/django/\">Django</a><a href=\"/simple/django/\">django</a><a href=\"/simple/six/\"></a></body></html>";
        assertThat(index.parseProjectList(pypiRoot.getBytes(StandardCharsets.UTF_8), "text/html")).contains(List.of("Django", "six"));
        assertThat(index.parseProjectList("{\"projects\":[{\"name\":\"A\"},{\"name\":\"a\"},\"b\",{}]}".getBytes(StandardCharsets.UTF_8), "application/json"))
                .contains(List.of("A", "b"));
    }

    @Test
    void largePagesParseQuickly() {
        StringBuilder sb = new StringBuilder("<html><body>");
        for (int i = 0; i < 200_000; i++) {
            sb.append("<a href=\"/simple/pkg").append(i).append("/\">pkg").append(i).append("</a><br/>\n");
        }
        sb.append("</body></html>");
        long start = System.nanoTime();
        List<String> names = index.parseProjectList(sb.toString().getBytes(StandardCharsets.UTF_8), "text/html").orElseThrow();
        assertThat(names).hasSize(200_000);
        assertThat(System.nanoTime() - start).isLessThan(java.util.concurrent.TimeUnit.SECONDS.toNanos(20));
    }
}
