package dev.granary.server.pypi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import dev.granary.core.storage.Checksums;
import dev.granary.format.pypi.Blake2b;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Distribution files, the upload form exactly as twine sends it, upstream index pages as pypi.org serves them
 * and small JSON helpers for the PyPI integration tests.
 */
final class PypiFixtures {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    /** MockMvc's view of the server: what {@code request.baseUrl()} resolves to in these tests. */
    static final String SERVER = "http://localhost";

    /** The Accept header pip 23+ sends for an index page. */
    static final String PIP_ACCEPT = "application/vnd.pypi.simple.v1+json, application/vnd.pypi.simple.v1+html;q=0.1, text/html;q=0.01";
    static final String JSON_V1 = "application/vnd.pypi.simple.v1+json";
    static final String HTML_V1 = "application/vnd.pypi.simple.v1+html";

    private PypiFixtures() {
    }

    static String repositoryUrl(String name) {
        return SERVER + "/repository/" + name;
    }

    // --- distribution files -----------------------------------------------------------------------

    static String metadata(String name, String version, String requiresPython, String summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("Metadata-Version: 2.4\n");
        sb.append("Name: ").append(name).append('\n');
        sb.append("Version: ").append(version).append('\n');
        if (summary != null) {
            sb.append("Summary: ").append(summary).append('\n');
        }
        sb.append("Author-email: Acme <dev@acme.example>\n");
        sb.append("Project-URL: Homepage, https://example.com/").append(name).append('\n');
        sb.append("Classifier: Programming Language :: Python :: 3\n");
        if (requiresPython != null) {
            sb.append("Requires-Python: ").append(requiresPython).append('\n');
        }
        sb.append("Description-Content-Type: text/markdown\n");
        sb.append("Requires-Dist: six>=1.16\n");
        sb.append("\n# ").append(name).append("\n\nA demo package.\n");
        return sb.toString();
    }

    static String wheelName(String name, String version) {
        return name.replace('-', '_') + "-" + version + "-py3-none-any.whl";
    }

    static String sdistName(String name, String version) {
        return name.replace('-', '_') + "-" + version + ".tar.gz";
    }

    /** A wheel with the given METADATA member under {@code <name_>-<version>.dist-info/}. */
    static byte[] wheel(String name, String version, String metadata) {
        String distInfo = name.replace('-', '_') + "-" + version + ".dist-info/";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(name.replace('-', '_') + "/__init__.py", ("__version__ = \"" + version + "\"\n").getBytes(StandardCharsets.UTF_8));
        entries.put(distInfo + "METADATA", metadata.getBytes(StandardCharsets.UTF_8));
        entries.put(distInfo + "WHEEL", "Wheel-Version: 1.0\nGenerator: test\nRoot-Is-Purelib: true\nTag: py3-none-any\n".getBytes(StandardCharsets.UTF_8));
        entries.put(distInfo + "RECORD", new byte[0]);
        return zip(entries);
    }

    static byte[] wheel(String name, String version) {
        return wheel(name, version, metadata(name, version, ">=3.8", "Demo package " + name));
    }

    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** An sdist: a gzip'ed ustar archive with {@code <name_>-<version>/PKG-INFO} and a module. */
    static byte[] sdist(String name, String version, String pkgInfo) {
        String dir = name.replace('-', '_') + "-" + version + "/";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(dir + "pyproject.toml", "[project]\n".getBytes(StandardCharsets.UTF_8));
        entries.put(dir + "PKG-INFO", pkgInfo.getBytes(StandardCharsets.UTF_8));
        entries.put(dir + name.replace('-', '_') + "/__init__.py", new byte[0]);
        return targz(entries);
    }

    static byte[] sdist(String name, String version) {
        return sdist(name, version, metadata(name, version, ">=3.8", "Demo package " + name));
    }

    static byte[] targz(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(tar(entries));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] tar(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            byte[] header = new byte[512];
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            System.arraycopy(name, 0, header, 0, Math.min(name.length, 100));
            octal(header, 100, 8, 0644);
            octal(header, 108, 8, 0);
            octal(header, 116, 8, 0);
            octal(header, 124, 12, e.getValue().length);
            octal(header, 136, 12, 0);
            for (int i = 148; i < 156; i++) {
                header[i] = ' ';
            }
            header[156] = '0';
            System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
            header[263] = '0';
            header[264] = '0';
            int sum = 0;
            for (byte b : header) {
                sum += b & 0xff;
            }
            octal(header, 148, 7, sum);
            header[155] = ' ';
            out.writeBytes(header);
            out.writeBytes(e.getValue());
            int padding = (512 - (e.getValue().length % 512)) % 512;
            out.writeBytes(new byte[padding]);
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    private static void octal(byte[] header, int offset, int length, long value) {
        String s = Long.toOctalString(value);
        while (s.length() < length - 1) {
            s = "0" + s;
        }
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
        header[offset + bytes.length] = 0;
    }

    static String sha256(byte[] data) {
        return Checksums.hex("SHA-256", data);
    }

    static String md5(byte[] data) {
        return Checksums.hex("MD5", data);
    }

    // --- the upload form ----------------------------------------------------------------------------

    /** {@code twine upload}: a multipart POST with the metadata fields, the three digests and the file as {@code content}. */
    static MockMultipartHttpServletRequestBuilder twineUpload(String url, String name, String version, String filetype,
                                                              String filename, byte[] bytes) {
        return twineUpload(url, name, version, filetype, filename, bytes, Map.of());
    }

    /** The same form with some fields replaced, for the ways an upload can be wrong. */
    static MockMultipartHttpServletRequestBuilder twineUpload(String url, String name, String version, String filetype,
                                                              String filename, byte[] bytes, Map<String, String> overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(":action", "file_upload");
        fields.put("protocol_version", "1");
        fields.put("name", name);
        fields.put("version", version);
        fields.put("filetype", filetype);
        fields.put("pyversion", filetype.equals("sdist") ? "source" : "py3");
        fields.put("metadata_version", "2.4");
        fields.put("summary", "Demo package " + name);
        fields.put("requires_python", ">=3.8");
        fields.put("classifiers", "Programming Language :: Python :: 3");
        fields.put("sha256_digest", sha256(bytes));
        fields.put("md5_digest", md5(bytes));
        fields.put("blake2_256_digest", Blake2b.hex256(bytes));
        fields.putAll(overrides);
        MockMultipartHttpServletRequestBuilder builder = multipart(url)
                .file(new MockMultipartFile("content", filename, "application/octet-stream", bytes))
                .header("User-Agent", "twine/7.0.0 CPython/3.12.3");
        fields.forEach(builder::param);
        return builder;
    }

    static MockMultipartHttpServletRequestBuilder uploadWheel(String url, String name, String version, byte[] bytes) {
        return twineUpload(url, name, version, "bdist_wheel", wheelName(name, version), bytes);
    }

    static MockMultipartHttpServletRequestBuilder uploadSdist(String url, String name, String version, byte[] bytes) {
        return twineUpload(url, name, version, "sdist", sdistName(name, version), bytes);
    }

    // --- upstream index pages -----------------------------------------------------------------------

    /** One file link on an upstream project page. */
    record UpstreamFile(String filename, String url, byte[] bytes, String requiresPython, boolean metadata, String yanked) {
        UpstreamFile(String filename, String url, byte[] bytes) {
            this(filename, url, bytes, ">=3.8", true, null);
        }

        String sha256() {
            return PypiFixtures.sha256(bytes);
        }

        String version() {
            String stem = filename.endsWith(".whl") ? filename.split("-")[1]
                    : filename.replace(".tar.gz", "").substring(filename.lastIndexOf('-') + 1);
            return stem;
        }
    }

    /** A PEP 503 project page the way pypi.org renders it. */
    static String upstreamHtml(String project, List<UpstreamFile> files) {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html>\n<html>\n  <head>\n    <meta name=\"pypi:repository-version\" content=\"1.3\">\n"
                + "    <title>Links for " + project + "</title>\n  </head>\n  <body>\n    <h1>Links for " + project + "</h1>\n");
        for (UpstreamFile f : files) {
            sb.append("    <a href=\"").append(f.url()).append("#sha256=").append(f.sha256()).append('"');
            if (f.requiresPython() != null) {
                sb.append(" data-requires-python=\"").append(f.requiresPython().replace(">", "&gt;")).append('"');
            }
            if (f.metadata()) {
                sb.append(" data-dist-info-metadata=\"sha256=").append(sha256(upstreamMetadata(f))).append('"');
                sb.append(" data-core-metadata=\"sha256=").append(sha256(upstreamMetadata(f))).append('"');
            }
            if (f.yanked() != null) {
                sb.append(" data-yanked=\"").append(f.yanked()).append('"');
            }
            sb.append('>').append(f.filename()).append("</a><br />\n");
        }
        sb.append("</body>\n</html>\n<!--SERIAL 1234-->");
        return sb.toString();
    }

    /** A PEP 691 project page the way pypi.org renders it. */
    static String upstreamJson(String project, List<UpstreamFile> files) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("meta").put("_last-serial", 1234).put("api-version", "1.3");
        root.put("name", project);
        ArrayNode array = root.putArray("files");
        for (UpstreamFile f : files) {
            ObjectNode o = array.addObject();
            o.put("filename", f.filename());
            o.put("url", f.url());
            o.putObject("hashes").put("sha256", f.sha256());
            if (f.requiresPython() != null) {
                o.put("requires-python", f.requiresPython());
            } else {
                o.putNull("requires-python");
            }
            if (f.metadata()) {
                o.putObject("core-metadata").put("sha256", sha256(upstreamMetadata(f)));
                // pypi.org really spells the legacy key of its JSON pages with the "data-" prefix
                o.putObject("data-dist-info-metadata").put("sha256", sha256(upstreamMetadata(f)));
            } else {
                o.put("core-metadata", false);
                o.put("data-dist-info-metadata", false);
            }
            o.put("size", f.bytes().length);
            o.put("upload-time", "2024-05-01T12:00:00.000000Z");
            if (f.yanked() != null) {
                o.put("yanked", f.yanked());
            } else {
                o.put("yanked", false);
            }
        }
        ArrayNode versions = root.putArray("versions");
        files.stream().map(UpstreamFile::version).distinct().forEach(versions::add);
        return MAPPER.writeValueAsString(root);
    }

    /** The PEP 658 sidecar upstream serves next to {@code f}. */
    static byte[] upstreamMetadata(UpstreamFile f) {
        String name = f.filename().split("-")[0].replace('_', '-');
        return metadata(name, f.version(), f.requiresPython(), "upstream " + name).getBytes(StandardCharsets.UTF_8);
    }

    static String upstreamRootHtml(List<String> names) {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html>\n<html>\n  <head>\n    <meta name=\"pypi:repository-version\" content=\"1.3\">\n"
                + "    <title>Simple index</title>\n  </head>\n  <body>\n");
        for (String name : names) {
            sb.append("    <a href=\"/simple/").append(name).append("/\">").append(name).append("</a>\n");
        }
        sb.append("  </body>\n</html>");
        return sb.toString();
    }

    static String upstreamRootJson(List<String> names) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("meta").put("_last-serial", 1234).put("api-version", "1.3");
        ArrayNode projects = root.putArray("projects");
        for (String name : names) {
            projects.addObject().put("_last-serial", 1).put("name", name);
        }
        return MAPPER.writeValueAsString(root);
    }

    /** A {@code pypi/<name>/json} document with one release, the way pypi.org renders it. */
    static String upstreamJsonApi(String project, String version, UpstreamFile file) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode info = root.putObject("info");
        info.put("name", project);
        info.put("version", version);
        info.put("summary", "upstream " + project);
        info.put("package_url", "https://pypi.org/project/" + project + "/");
        ObjectNode url = MAPPER.createObjectNode();
        url.put("filename", file.filename());
        url.put("url", file.url());
        url.put("packagetype", file.filename().endsWith(".whl") ? "bdist_wheel" : "sdist");
        url.putObject("digests").put("sha256", file.sha256());
        root.putObject("releases").putArray(version).add(url);
        root.putArray("urls").add(url.deepCopy());
        root.put("last_serial", 1234);
        root.putArray("vulnerabilities");
        return MAPPER.writeValueAsString(root);
    }

    // --- response helpers ---------------------------------------------------------------------------

    static ObjectNode object(MvcResult result) throws Exception {
        return (ObjectNode) MAPPER.readTree(result.getResponse().getContentAsByteArray());
    }

    static String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    static String str(JsonNode node, String... path) {
        JsonNode n = node;
        for (String p : path) {
            n = n.path(p);
        }
        return n.isMissingNode() || n.isNull() ? null : n.asString();
    }

    static List<String> filenames(JsonNode page) {
        return page.path("files").valueStream().map(f -> f.path("filename").asString()).toList();
    }
}
