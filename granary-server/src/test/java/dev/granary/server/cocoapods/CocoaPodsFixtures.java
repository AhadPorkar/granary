package dev.granary.server.cocoapods;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Podspecs as {@code pod ipc spec} prints them, source archives, CDN index files and request helpers. */
final class CocoaPodsFixtures {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    /** MockMvc's view of the server: what {@code request.baseUrl()} resolves to in these tests. */
    static final String SERVER = "http://localhost";
    static final String VERSION_FILE = "---\nmin: 1.0.0\nlast: 1.16.2\nprefix_lengths:\n- 1\n- 1\n- 1\n";

    private CocoaPodsFixtures() {
    }

    static String repositoryUrl(String name) {
        return SERVER + "/repository/" + name;
    }

    /** The three-level shard directory of a pod, e.g. {@code a/7/5} for AFNetworking. */
    static String shard(String name) {
        String hex = md5(name.getBytes(StandardCharsets.UTF_8));
        return hex.charAt(0) + "/" + hex.charAt(1) + "/" + hex.charAt(2);
    }

    static String shardFile(String name) {
        return "all_pods_versions_" + shard(name).replace('/', '_') + ".txt";
    }

    static String podspecPath(String name, String version) {
        return "Specs/" + shard(name) + "/" + name + "/" + version + "/" + name + ".podspec.json";
    }

    /** A podspec with a git source, the way most trunk pods look. */
    static String gitPodspec(String name, String version) {
        return """
                {
                  "name": "%1$s",
                  "version": "%2$s",
                  "summary": "The %1$s pod.",
                  "homepage": "https://example.com/%1$s",
                  "license": {"type": "MIT", "file": "LICENSE"},
                  "authors": {"Acme": "dev@acme.example"},
                  "source": {"git": "https://example.com/%1$s.git", "tag": "%2$s"},
                  "platforms": {"ios": "12.0", "osx": "10.13"},
                  "swift_versions": ["5.0"],
                  "source_files": "Sources/**/*.swift",
                  "requires_arc": true,
                  "price": 1.50
                }
                """.formatted(name, version);
    }

    /** A binary pod whose source is an http archive. */
    static String httpPodspec(String name, String version, String url, String extraSource) {
        return """
                {
                  "name": "%1$s",
                  "version": "%2$s",
                  "summary": "Binary build of %1$s.",
                  "homepage": "https://example.com/%1$s",
                  "license": {"type": "Commercial"},
                  "authors": {"Acme": "dev@acme.example"},
                  "source": {"http": "%3$s"%4$s},
                  "platforms": {"ios": "13.0"},
                  "vendored_frameworks": "%1$s.xcframework"
                }
                """.formatted(name, version, url, extraSource == null ? "" : ", " + extraSource);
    }

    static String deprecatedPodspec(String name, String version, String inFavorOf) {
        return gitPodspec(name, version).replace("\"requires_arc\": true",
                "\"requires_arc\": true,\n  \"deprecated_in_favor_of\": \"" + inFavorOf + "\"");
    }

    static byte[] zip(String entry, String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    static String md5(byte[] data) {
        return digest("MD5", data);
    }

    private static String digest(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- requests ------------------------------------------------------------------------------

    /** {@code curl -X PUT --data-binary @Name.podspec.json <repo>/Specs/Name/version/Name.podspec.json}. */
    static MockHttpServletRequestBuilder putPodspec(String repo, String name, String version, String json) {
        return MockMvcRequestBuilders.put("/repository/" + repo + "/Specs/{name}/{version}/{file}", name, version,
                        name + ".podspec.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    /** {@code curl -F podspec=@Name.podspec.json -F source=@archive <repo>/api/pods}. */
    static MockMultipartHttpServletRequestBuilder pushWithArchive(String repo, String podspecJson, String archiveName, byte[] archive) {
        MockMultipartHttpServletRequestBuilder request = multipart("/repository/" + repo + "/api/pods");
        request.file(new MockMultipartFile("podspec", "pod.podspec.json", "application/json",
                podspecJson.getBytes(StandardCharsets.UTF_8)));
        if (archive != null) {
            request.file(new MockMultipartFile("source", archiveName, "application/octet-stream", archive));
        }
        return request;
    }

    static String text(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode json(MvcResult result) {
        return MAPPER.readTree(result.getResponse().getContentAsByteArray());
    }

    static String str(JsonNode node, String... path) {
        JsonNode current = node;
        for (String p : path) {
            current = current.path(p);
        }
        return current.isMissingNode() || current.isNull() ? null : current.asString();
    }
}
