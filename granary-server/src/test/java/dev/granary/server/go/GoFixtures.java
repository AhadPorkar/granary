package dev.granary.server.go;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Module zips the way {@code go mod download} and {@code zip -r} lay them out, and requests shaped exactly like
 * the ones the go command sends to a GOPROXY (recorded from go 1.24: a plain GET with {@code User-Agent:
 * Go-http-client/1.1} and {@code Accept-Encoding: gzip}, nothing else) and the ones {@code curl -T} sends to publish.
 */
final class GoFixtures {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    /** MockMvc's view of the server: what {@code request.baseUrl()} resolves to in these tests. */
    static final String SERVER = "http://localhost";
    static final String GO_USER_AGENT = "Go-http-client/1.1";
    static final String MODULE = "example.com/acme/hello";
    private static final long FIXED_TIME = 1_704_067_200_000L; // 2024-01-01T00:00:00Z

    private GoFixtures() {
    }

    static String repositoryUrl(String name) {
        return SERVER + "/repository/" + name;
    }

    /** {@code GET <repository>/<path>} as the go command sends it. */
    static MockHttpServletRequestBuilder goGet(String repository, String path) {
        return get("/repository/" + repository + "/" + path)
                .header("User-Agent", GO_USER_AGENT)
                .header("Accept-Encoding", "gzip");
    }

    /** {@code curl -T file <repository>/<path>}: a PUT with the raw file as body and no Content-Type. */
    static MockHttpServletRequestBuilder curlPut(String repository, String path, byte[] body) {
        return put("/repository/" + repository + "/" + path)
                .header("User-Agent", "curl/8.5.0")
                .header("Accept", "*/*")
                .content(body);
    }

    /** A module with a go.mod declaring {@code module} and one Go file, under {@code <module>@<version>/}. */
    static byte[] moduleZip(String module, String version) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("go.mod", goMod(module));
        files.put("hello.go", "package hello\n\n// Hello greets.\nfunc Hello() string { return \"hello from " + module + " " + version + "\" }\n");
        return moduleZip(module, version, files);
    }

    static String goMod(String module) {
        return "module " + module + "\n\ngo 1.21\n";
    }

    /** Files relative to the module root, wrapped into the {@code <module>@<version>/} prefix. */
    static byte[] moduleZip(String module, String version, Map<String, String> files) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        files.forEach((name, content) -> entries.put(module + "@" + version + "/" + name, content.getBytes(StandardCharsets.UTF_8)));
        return zip(entries);
    }

    /** A zip with exactly these entries. */
    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                // a fixed time keeps two zips with the same content byte-identical
                entry.setTime(FIXED_TIME);
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String text(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    static byte[] bytes(MvcResult result) {
        return result.getResponse().getContentAsByteArray();
    }

    static JsonNode json(MvcResult result) {
        return MAPPER.readTree(result.getResponse().getContentAsByteArray());
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An {@code .info} document as proxy.golang.org serves it, Origin block included. */
    static String upstreamInfo(String version, String time) {
        return "{\"Version\":\"" + version + "\",\"Time\":\"" + time + "\",\"Origin\":{\"VCS\":\"git\",\"URL\":\"https://github.com/rsc/quote\","
                + "\"Ref\":\"refs/tags/" + version + "\",\"Hash\":\"0406d7298a1f9a6a2b2a6b6b6b6b6b6b6b6b6b6b\"}}";
    }

    /** A checksum-database lookup answer: the go.sum lines, a blank line and a signed tree note. */
    static String sumdbLookup(String module, String version) {
        return "1234567\n" + module + " " + version + " h1:w5fcysjrx7yqtD/aO+QwRjYZOKnaM9Uh2b40tElTs3Y=\n"
                + module + " " + version + "/go.mod h1:LzX7hefJvL54yjefDEDHNONDjII0t9xZLPXsUe+TKr0=\n\n"
                + "go.sum database tree\n42000000\nO6x0Wnxo8yRvGUCzjGAc6a5vH3xHfNn1J6M2hBu4kBE=\n\n"
                + "— sum.golang.org Az3grlR8sx0Hpa+YyFRxLc9UQ4u7Bl/3cmVM2+1Fk0D9Lwz1EEwtcYELqzsU7Zl3vJMa5sD3cMd6m6g2rM0vUYW2Bwo=\n";
    }
}
