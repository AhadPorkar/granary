package dev.granary.server.conan;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.granary.core.storage.Checksums;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Recipe and binary files, request builders and response readers shared by the Conan end-to-end tests. */
final class ConanFixtures {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final String RREV = "f1e2d3c4b5a697887766554433221100";
    static final String RREV2 = "00112233445566778899aabbccddeeff";
    static final String PID = "9e186f6d94c008b544af1569d1a6368d8339efc5";
    static final String PREV = "a3b4c5d6e7f8091a2b3c4d5e6f708192";
    static final String PREV2 = "b4c5d6e7f8091a2b3c4d5e6f70819203";
    static final String CAPABILITIES = "complex_search,checksum_deploy,revisions,only_v2";
    /** What every Conan 2 request carries besides credentials. */
    static final String USER_AGENT = "Conan/2.32.0 (Python 3.13.5) python-requests/2.32.5";
    static final String CONANINFO = """
            [settings]
            arch=x86_64
            build_type=Release
            compiler=gcc
            compiler.version=13
            os=Linux

            [options]
            shared=False

            [requires]
            """;

    private ConanFixtures() {
    }

    static String recipe(String name, String version) {
        return "/v2/conans/" + name + "/" + version + "/_/_";
    }

    static String recipe(String name, String version, String user, String channel) {
        return "/v2/conans/" + name + "/" + version + "/" + user + "/" + channel;
    }

    static String revision(String recipe, String rrev) {
        return recipe + "/revisions/" + rrev;
    }

    static String packageRevision(String recipe, String rrev, String pid, String prev) {
        return revision(recipe, rrev) + "/packages/" + pid + "/revisions/" + prev;
    }

    /** The files of one recipe revision in the order {@code conan upload} sends them (sorted, manifest last). */
    static Map<String, byte[]> recipeFiles(String seed) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("conan_export.tgz", tgz("export " + seed));
        files.put("conanfile.py", utf8("from conan import ConanFile\n\nclass Pkg(ConanFile):\n    name = \"" + seed + "\"\n"));
        files.put("conanmanifest.txt", utf8("1760000000\nconanfile.py: " + Checksums.hex("MD5", utf8(seed)) + "\n"));
        return files;
    }

    /** The files of one binary package revision, manifest last. */
    static Map<String, byte[]> packageFiles(String seed) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("conan_package.tgz", tgz("package " + seed));
        files.put("conaninfo.txt", utf8(CONANINFO));
        files.put("conanmanifest.txt", utf8("1760000000\nconaninfo.txt: " + Checksums.hex("MD5", utf8(seed)) + "\n"));
        return files;
    }

    /** A PUT of one file exactly as the client sends it: raw body, SHA-1 header, bearer token. */
    static MockHttpServletRequestBuilder upload(String url, byte[] body, String authorization) {
        MockHttpServletRequestBuilder builder = put(url)
                .header("User-Agent", USER_AGENT)
                .header("X-Checksum-Sha1", sha1(body))
                .content(body);
        return authorization == null ? builder : builder.header("Authorization", authorization);
    }

    static byte[] tgz(String seed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(("archive " + seed).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static String sha1(byte[] bytes) {
        return Checksums.hex("SHA-1", bytes);
    }

    static JsonNode json(MvcResult result) {
        return MAPPER.readTree(result.getResponse().getContentAsByteArray());
    }

    static String text(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    static String revisionsJson(String... revisionTimePairs) {
        StringBuilder sb = new StringBuilder("{\"revisions\":[");
        for (int i = 0; i < revisionTimePairs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"revision\":\"").append(revisionTimePairs[i]).append("\",\"time\":\"")
                    .append(revisionTimePairs[i + 1]).append("\"}");
        }
        return sb.append("]}").toString();
    }

    static String latestJson(String revision, String time) {
        return "{\"revision\":\"" + revision + "\",\"time\":\"" + time + "\"}";
    }
}
