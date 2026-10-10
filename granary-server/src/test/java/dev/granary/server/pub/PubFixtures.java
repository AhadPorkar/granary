package dev.granary.server.pub;

import dev.granary.core.storage.Checksums;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Package archives as {@code dart pub publish} builds them, upstream documents as pub.dev serves them, the
 * headers the pub client sends (recorded from {@code Dart pub 3.13.5}) and small JSON helpers.
 */
final class PubFixtures {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    /** MockMvc's view of the server: what {@code request.baseUrl()} resolves to in these tests. */
    static final String SERVER = "http://localhost";

    /** What the pub client sends with every API request. */
    static final String USER_AGENT = "Dart pub 3.13.5";
    static final String ACCEPT = "application/vnd.pub.v2+json";
    static final String ACCEPT_ENCODING = "gzip";

    private PubFixtures() {
    }

    static String repositoryUrl(String name) {
        return SERVER + "/repository/" + name;
    }

    // --- archives ------------------------------------------------------------------------------------

    static String pubspec(String name, String version) {
        return "name: " + name + "\n"
                + "description: A tiny package published to Granary by the end-to-end tests of the pub format.\n"
                + "version: " + version + "\n"
                + "homepage: https://example.com/" + name + "\n"
                + "environment:\n"
                + "  sdk: ^3.4.0\n"
                + "dependencies:\n"
                + "  path: ^1.9.0\n";
    }

    /** The archive {@code dart pub publish} uploads: the package's files, {@code pubspec.yaml} at the root. */
    static byte[] archive(String name, String version) {
        return archive(name, version, pubspec(name, version));
    }

    static byte[] archive(String name, String version, String pubspec) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("CHANGELOG.md", ("## " + version + "\n\n- Initial version.\n").getBytes(StandardCharsets.UTF_8));
        entries.put("LICENSE", "MIT License\n".getBytes(StandardCharsets.UTF_8));
        entries.put("README.md", ("# " + name + "\n").getBytes(StandardCharsets.UTF_8));
        entries.put("lib/" + name + ".dart", ("String greet(String who) => 'Hello, $who';\n").getBytes(StandardCharsets.UTF_8));
        entries.put("pubspec.yaml", pubspec.getBytes(StandardCharsets.UTF_8));
        return targz(entries);
    }

    /** A gzip-compressed ustar archive with the given regular files, in order. */
    static byte[] targz(Map<String, byte[]> files) {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        files.forEach((path, data) -> {
            tar.writeBytes(header(path, '0', data.length, ""));
            tar.writeBytes(data);
            tar.writeBytes(new byte[(512 - data.length % 512) % 512]);
        });
        tar.writeBytes(new byte[1024]);
        return gzip(tar.toByteArray());
    }

    /** A gzip-compressed archive with a symbolic link next to the pubspec. */
    static byte[] targzWithSymlink(String pubspec, String link, String target) {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        tar.writeBytes(header(link, '2', 0, target));
        byte[] data = pubspec.getBytes(StandardCharsets.UTF_8);
        tar.writeBytes(header("pubspec.yaml", '0', data.length, ""));
        tar.writeBytes(data);
        tar.writeBytes(new byte[(512 - data.length % 512) % 512]);
        tar.writeBytes(new byte[1024]);
        return gzip(tar.toByteArray());
    }

    private static byte[] header(String name, char type, long size, String link) {
        byte[] header = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(n, 0, header, 0, Math.min(n.length, 100));
        octal(header, 100, 8, 0644);
        octal(header, 108, 8, 0);
        octal(header, 116, 8, 0);
        octal(header, 124, 12, size);
        octal(header, 136, 12, 1_760_000_000L);
        header[156] = (byte) type;
        byte[] l = link.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(l, 0, header, 157, Math.min(l.length, 100));
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        header[263] = '0';
        header[264] = '0';
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        int sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }
        octal(header, 148, 7, sum);
        header[155] = ' ';
        return header;
    }

    private static void octal(byte[] header, int offset, int length, long value) {
        StringBuilder s = new StringBuilder(Long.toOctalString(value));
        while (s.length() < length - 1) {
            s.insert(0, '0');
        }
        byte[] bytes = s.toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
        header[offset + bytes.length] = 0;
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String sha256(byte[] data) {
        return Checksums.hex("SHA-256", data);
    }

    // --- upstream documents --------------------------------------------------------------------------

    /**
     * A package listing the way pub.dev serves it: versions in ascending order, each with its pubspec, an
     * {@code archive_url} below {@code <upstream>/api/archives/} and the archive's sha256.
     *
     * @param archives version to archive bytes, in ascending order
     */
    static String upstreamListing(String upstream, String name, String advisoriesUpdated, Map<String, byte[]> archives) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("name", name);
        ArrayNode versions = MAPPER.createArrayNode();
        ObjectNode latest = null;
        for (Map.Entry<String, byte[]> e : archives.entrySet()) {
            ObjectNode v = versions.addObject();
            v.put("version", e.getKey());
            ObjectNode pubspec = v.putObject("pubspec");
            pubspec.put("name", name);
            pubspec.put("version", e.getKey());
            pubspec.put("description", "The upstream package " + name + ".");
            pubspec.putObject("environment").put("sdk", "^3.4.0");
            v.put("archive_url", upstream + "/api/archives/" + name + "-" + e.getKey() + ".tar.gz");
            v.put("archive_sha256", sha256(e.getValue()));
            v.put("published", "2024-11-14T14:32:01.384716Z");
            if (!e.getKey().contains("-")) {
                latest = v;
            }
        }
        if (latest == null && !versions.isEmpty()) {
            latest = (ObjectNode) versions.get(versions.size() - 1);
        }
        if (latest != null) {
            doc.set("latest", latest.deepCopy());
        }
        doc.set("versions", versions);
        if (advisoriesUpdated != null) {
            doc.put("advisoriesUpdated", advisoriesUpdated);
        }
        return doc.toString();
    }

    /** The advisories document pub.dev serves for a package with one advisory. */
    static String upstreamAdvisories(String name, String id) {
        return "{\"advisories\":[{\"schema_version\":\"1.7.3\",\"id\":\"" + id + "\",\"summary\":\"" + name
                + " before 0.13.3 vulnerable to header injection\",\"affected\":[{\"package\":{\"name\":\"" + name
                + "\",\"ecosystem\":\"Pub\"}}]}],\"advisoriesUpdated\":\"2026-05-04T16:03:47.124153Z\"}";
    }

    // --- JSON ----------------------------------------------------------------------------------------

    static JsonNode json(MvcResult result) {
        return MAPPER.readTree(result.getResponse().getContentAsByteArray());
    }

    /** The string at {@code path} (object members), or {@code null}. */
    static String str(JsonNode node, String... path) {
        JsonNode n = node;
        for (String p : path) {
            n = n == null ? null : n.get(p);
        }
        return n == null || n.isNull() ? null : n.asString();
    }

    /** The versions of a listing in document order. */
    static java.util.List<String> versions(JsonNode listing) {
        return listing.path("versions").valueStream().map(v -> v.path("version").asString()).toList();
    }
}
