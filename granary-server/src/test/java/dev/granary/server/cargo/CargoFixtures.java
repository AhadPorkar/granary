package dev.granary.server.cargo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Requests exactly as cargo 1.97 sends them (headers and publish body recorded from the real client), crate
 * archives as {@code cargo package} writes them, and index lines as index.crates.io serves them.
 */
final class CargoFixtures {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    /** MockMvc's view of the server: what {@code request.baseUrl()} resolves to in these tests. */
    static final String SERVER = "http://localhost";
    static final String USER_AGENT = "cargo/1.97.0 (c980f4866 2026-06-30)";
    static final String CRATES_IO_INDEX = "https://github.com/rust-lang/crates.io-index";

    private CargoFixtures() {
    }

    static String repositoryUrl(String name) {
        return SERVER + "/repository/" + name;
    }

    // --- requests ------------------------------------------------------------------------------

    /** A sparse index fetch ({@code config.json} or a crate's file). */
    static MockHttpServletRequestBuilder index(String url) {
        return get(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "deflate, gzip")
                .header("cargo-protocol", "version=1")
                .header("accept", "text/plain");
    }

    /** A crate download as cargo asks for it ({@code dl} of {@code config.json} plus {@code /<name>/<version>/download}). */
    static MockHttpServletRequestBuilder download(String url) {
        return get(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "deflate, gzip");
    }

    /** {@code cargo publish}: a PUT of the binary publish body, authorised by the bare token. */
    static MockHttpServletRequestBuilder publish(String repositoryPath, String token, byte[] body) {
        MockHttpServletRequestBuilder request = put(repositoryPath + "/api/v1/crates/new")
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "deflate, gzip")
                .header("content-type", "application/octet-stream")
                .header("accept", "application/json")
                .content(body);
        return token == null ? request : request.header("authorization", token);
    }

    /** Any other web API call ({@code yank}, {@code owner}, {@code search}). */
    static MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder request, String token) {
        request.header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "deflate, gzip")
                .header("accept", "application/json");
        return token == null ? request : request.header("authorization", token);
    }

    // --- publish bodies ------------------------------------------------------------------------

    /** The metadata cargo 1.97 sends for a crate depending on {@code itoa = "1"} from crates.io. */
    static ObjectNode metadata(String name, String version) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("name", name);
        m.put("vers", version);
        ArrayNode deps = m.putArray("deps");
        deps.add(dependency("itoa", "^1", null, false));
        m.putObject("features");
        m.putArray("authors");
        m.put("description", "Hello from " + name);
        m.putNull("documentation");
        m.putNull("homepage");
        m.putNull("readme");
        m.putNull("readme_file");
        m.putArray("keywords").add("greeting");
        m.putArray("categories");
        m.put("license", "MIT");
        m.putNull("license_file");
        m.putNull("repository");
        m.putObject("badges");
        m.putNull("links");
        m.putNull("rust_version");
        return m;
    }

    /**
     * The metadata recorded from {@code cargo publish} of a crate with an optional dependency renamed in its
     * manifest ({@code json = { package = "serde_json", optional = true }}) and a feature {@code fast = ["dep:json"]}.
     */
    static ObjectNode renamedDependencyMetadata(String name, String version) {
        ObjectNode m = metadata(name, version);
        ArrayNode deps = m.putArray("deps");
        deps.add(dependency("itoa", "^1", null, false));
        deps.add(dependency("serde_json", "^1", "json", true));
        ObjectNode features = m.putObject("features");
        features.putArray("fast").add("dep:json");
        features.putArray("plain");
        m.put("description", "Recorded");
        m.putArray("keywords");
        return m;
    }

    private static ObjectNode dependency(String name, String req, String rename, boolean optional) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("optional", optional);
        d.put("default_features", true);
        d.put("name", name);
        d.putArray("features");
        d.put("version_req", req);
        d.putNull("target");
        d.put("kind", "normal");
        d.put("registry", CRATES_IO_INDEX);
        if (rename != null) {
            d.put("explicit_name_in_toml", rename);
        }
        return d;
    }

    /** {@code u32 LE} metadata length, metadata JSON, {@code u32 LE} crate length, crate bytes. */
    static byte[] publishBody(ObjectNode metadata, byte[] crate) {
        byte[] json = MAPPER.writeValueAsBytes(metadata);
        ByteBuffer buffer = ByteBuffer.allocate(8 + json.length + crate.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(json.length).put(json).putInt(crate.length).put(crate);
        return buffer.array();
    }

    static byte[] publishBody(String name, String version) {
        return publishBody(metadata(name, version), crate(name, version));
    }

    // --- crate archives ------------------------------------------------------------------------

    /** A {@code .crate}: a gzip-compressed tar of {@code <name>-<version>/} with a manifest and a source file. */
    static byte[] crate(String name, String version) {
        return crate(name, version, "pub fn hello() -> &'static str { \"" + name + " " + version + "\" }\n");
    }

    static byte[] crate(String name, String version, String librs) {
        String root = name + "-" + version + "/";
        Map<String, String> files = new LinkedHashMap<>();
        files.put(root + "Cargo.toml", "[package]\nname = \"" + name + "\"\nversion = \"" + version + "\"\nedition = \"2021\"\n");
        files.put(root + "src/lib.rs", librs);
        return tarGz(files);
    }

    /** A gzip-compressed ustar archive of regular files. */
    static byte[] tarGz(Map<String, String> files) {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        files.forEach((path, content) -> {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            tar.writeBytes(header(path, data.length));
            tar.writeBytes(data);
            tar.writeBytes(new byte[(512 - data.length % 512) % 512]);
        });
        tar.writeBytes(new byte[1024]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(tar.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] header(String path, long size) {
        byte[] h = new byte[512];
        write(h, 0, path.getBytes(StandardCharsets.UTF_8));
        write(h, 100, octal(0644, 8));
        write(h, 108, octal(0, 8));
        write(h, 116, octal(0, 8));
        write(h, 124, octal(size, 12));
        write(h, 136, octal(1_700_000_000L, 12));
        h[156] = '0';
        write(h, 257, "ustar\0".getBytes(StandardCharsets.US_ASCII));
        write(h, 263, "00".getBytes(StandardCharsets.US_ASCII));
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        write(h, 148, octal(sum, 7));
        h[155] = ' ';
        return h;
    }

    private static byte[] octal(long value, int width) {
        String s = Long.toOctalString(value);
        return ("0".repeat(Math.max(0, width - 1 - s.length())) + s + "\0").getBytes(StandardCharsets.US_ASCII);
    }

    private static void write(byte[] target, int offset, byte[] value) {
        System.arraycopy(value, 0, target, offset, value.length);
    }

    // --- index lines ---------------------------------------------------------------------------

    /** One line of an index file as index.crates.io serves it. */
    static String indexLine(String name, String version, byte[] crate, boolean yanked) {
        ObjectNode line = MAPPER.createObjectNode();
        line.put("name", name);
        line.put("vers", version);
        line.putArray("deps");
        line.put("cksum", sha256(crate));
        line.putObject("features");
        line.put("yanked", yanked);
        return MAPPER.writeValueAsString(line);
    }

    static String indexFile(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    /** The lines of an index file, parsed. */
    static List<ObjectNode> lines(MvcResult result) {
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        List<ObjectNode> lines = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (!line.isBlank()) {
                lines.add((ObjectNode) MAPPER.readTree(line));
            }
        }
        return lines;
    }

    static List<String> versions(MvcResult result) {
        return lines(result).stream().map(l -> l.path("vers").stringValue()).toList();
    }

    // --- JSON ----------------------------------------------------------------------------------

    static ObjectNode json(MvcResult result) {
        return (ObjectNode) MAPPER.readTree(result.getResponse().getContentAsByteArray());
    }

    static String errorDetail(MvcResult result) {
        JsonNode detail = json(result).path("errors").path(0).path("detail");
        return detail.isString() ? detail.stringValue() : null;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
