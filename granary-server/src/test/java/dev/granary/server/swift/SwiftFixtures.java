package dev.granary.server.swift;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Requests exactly as SwiftPM 6.1 sends them (recorded from {@code swift package-registry publish}, {@code login}
 * and {@code swift package resolve}), source archives laid out as {@code swift package archive-source} creates
 * them, and small JSON helpers for the Swift integration tests.
 */
final class SwiftFixtures {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    /** MockMvc's view of the server: what {@code request.baseUrl()} resolves to in these tests. */
    static final String SERVER = "http://localhost";

    static final String USER_AGENT = "SwiftPackageManager/6.1.3";
    static final String ACCEPT_JSON = "application/vnd.swift.registry.v1+json";
    static final String ACCEPT_SWIFT = "application/vnd.swift.registry.v1+swift";
    static final String ACCEPT_ZIP = "application/vnd.swift.registry.v1+zip";
    /** SwiftPM quotes the boundary, a UUID. */
    static final String BOUNDARY = "4AD6DD7F-5811-4F17-8F29-F645E19D0634";

    static final String MANIFEST = """
            // swift-tools-version:5.9
            import PackageDescription

            let package = Package(
                name: "Demo",
                products: [.library(name: "Demo", targets: ["Demo"])],
                targets: [.target(name: "Demo")]
            )
            """;

    static final String MANIFEST_6 = """
            // swift-tools-version:6.0
            import PackageDescription

            let package = Package(
                name: "Demo",
                products: [.library(name: "Demo", targets: ["Demo"])],
                targets: [.target(name: "Demo")],
                swiftLanguageModes: [.v5]
            )
            """;

    static final String METADATA = """
            {
              "description": "A demo package",
              "author": { "name": "Jane Doe" },
              "licenseURL": "https://example.com/acme/demo/LICENSE",
              "repositoryURLs": ["https://github.com/acme/demo", "git@github.com:acme/demo.git"]
            }
            """;

    private SwiftFixtures() {
    }

    static String repositoryUrl(String name) {
        return SERVER + "/repository/" + name;
    }

    // --- archives -----------------------------------------------------------------------------------

    private static final long FIXED_TIME = java.time.Instant.parse("2024-01-31T12:00:00Z").toEpochMilli();

    /** A zip with the given entries in order; a name ending in {@code /} becomes a directory entry. */
    static byte[] zip(Map<String, String> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(FIXED_TIME); // the same entries always make the same bytes (and checksum)
                zip.putNextEntry(entry);
                if (!e.getKey().endsWith("/")) {
                    zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * A source archive as {@code swift package-registry publish} builds it: the package directory copied to
     * {@code <scope>.<name>/} and zipped with that single top-level directory, {@code package-metadata.json} included.
     */
    static byte[] archive(String id, String seed, boolean withSwift6Variant) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(id + "/", "");
        entries.put(id + "/package-metadata.json", METADATA);
        entries.put(id + "/Sources/", "");
        entries.put(id + "/Sources/Demo/", "");
        entries.put(id + "/Sources/Demo/Demo.swift", "public enum Demo { public static let seed = \"" + seed + "\" }\n");
        if (withSwift6Variant) {
            entries.put(id + "/Package@swift-6.swift", MANIFEST_6);
        }
        entries.put(id + "/Package.swift", MANIFEST);
        return zip(entries);
    }

    static byte[] archive(String seed) {
        return archive("acme.demo", seed, false);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256Bytes(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- requests -----------------------------------------------------------------------------------

    /** A GET as SwiftPM sends it for JSON documents (release lists, release metadata, identifiers). */
    static MockHttpServletRequestBuilder getJson(String url) {
        return get(URI.create(url)).header("Accept", ACCEPT_JSON).header("User-Agent", USER_AGENT).header("Accept-Language", "en");
    }

    /** A GET as SwiftPM sends it for {@code Package.swift}. */
    static MockHttpServletRequestBuilder getSwift(String url) {
        return get(URI.create(url)).header("Accept", ACCEPT_SWIFT).header("User-Agent", USER_AGENT).header("Accept-Language", "en");
    }

    /** A GET as SwiftPM sends it for source archives. */
    static MockHttpServletRequestBuilder getZip(String url) {
        return get(URI.create(url)).header("Accept", ACCEPT_ZIP).header("User-Agent", USER_AGENT).header("Accept-Language", "en");
    }

    /**
     * The publish request of {@code swift package-registry publish}: {@code PUT <scope>/<name>/<version>},
     * multipart parts without file names, the archive labelled {@code binary}, the metadata
     * {@code quoted-printable} (while it is plain JSON), {@code Prefer: respond-async} and {@code Expect: 100-continue}.
     */
    static MockMultipartHttpServletRequestBuilder publish(String url, byte[] archive, String metadata) {
        MockMultipartHttpServletRequestBuilder request = multipart(HttpMethod.PUT, URI.create(url));
        if (archive != null) {
            request.part(part("source-archive", archive, "application/zip", "binary"));
        }
        if (metadata != null) {
            request.part(part("metadata", metadata.getBytes(StandardCharsets.UTF_8), "application/json", "quoted-printable"));
        }
        request.contentType("multipart/form-data;boundary=\"" + BOUNDARY + "\"");
        request.header("Accept", ACCEPT_JSON).header("Prefer", "respond-async").header("Expect", "100-continue")
                .header("User-Agent", USER_AGENT).header("Accept-Language", "en");
        return request;
    }

    static MockPart part(String name, byte[] content, String contentType, String transferEncoding) {
        MockPart part = new MockPart(name, content);
        part.getHeaders().set("Content-Type", contentType);
        if (transferEncoding != null) {
            part.getHeaders().set("Content-Transfer-Encoding", transferEncoding);
        }
        return part;
    }

    // --- responses ----------------------------------------------------------------------------------

    static JsonNode json(MvcResult result) {
        return MAPPER.readTree(result.getResponse().getContentAsByteArray());
    }

    static String text(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
