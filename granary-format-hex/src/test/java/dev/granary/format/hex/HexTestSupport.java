package dev.granary.format.hex;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Fixtures shared by the Hex unit tests: reference bytes produced by hex_core itself ({@code /hex/golden}),
 * release tarballs built the way {@code mix hex.build} builds them, repositories, principals and requests shaped
 * like the ones mix sends.
 */
final class HexTestSupport {

    static final String SERVER = "http://granary.test";
    static final String ERLANG = "application/vnd.hex+erlang";

    private HexTestSupport() {
    }

    // --- golden files ----------------------------------------------------------------------------

    static byte[] golden(String name) {
        try (InputStream in = HexTestSupport.class.getResourceAsStream("/hex/golden/" + name)) {
            if (in == null) {
                throw new AssertionError("missing golden file " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String goldenText(String name) {
        return new String(golden(name), StandardCharsets.UTF_8);
    }

    /** A signing key directory holding the key the golden documents were signed with. */
    static HexSigningKey goldenKey(Path tempDir) {
        try {
            Path dir = tempDir.resolve("golden-key");
            Files.createDirectories(dir);
            Files.write(dir.resolve(HexSigningKey.PRIVATE_KEY_FILE), golden("signing_key.pem"));
            return new HexSigningKey(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- tarballs ---------------------------------------------------------------------------------

    /** metadata.config the way hex_tarball writes it, with optional requirements (name, requirement, optional). */
    static String metadata(String name, String version, String description, String... requirements) {
        StringBuilder sb = new StringBuilder();
        sb.append("{<<\"name\">>,<<\"").append(name).append("\">>}.\n");
        sb.append("{<<\"version\">>,<<\"").append(version).append("\">>}.\n");
        sb.append("{<<\"app\">>,<<\"").append(name).append("\">>}.\n");
        sb.append("{<<\"description\">>,<<\"").append(description).append("\"/utf8>>}.\n");
        sb.append("{<<\"licenses\">>,[<<\"Apache-2.0\">>]}.\n");
        sb.append("{<<\"links\">>,[{<<\"Source\">>,<<\"https://example.test/").append(name).append("\">>}]}.\n");
        sb.append("{<<\"build_tools\">>,[<<\"mix\">>]}.\n");
        sb.append("{<<\"elixir\">>,<<\"~> 1.15\">>}.\n");
        sb.append("{<<\"files\">>,[<<\"lib\">>,<<\"mix.exs\">>]}.\n");
        sb.append("{<<\"requirements\">>,[");
        for (int i = 0; i + 2 < requirements.length; i += 3) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("[{<<\"name\">>,<<\"").append(requirements[i]).append("\">>},")
                    .append("{<<\"repository\">>,<<\"hexpm\">>},")
                    .append("{<<\"requirement\">>,<<\"").append(requirements[i + 1]).append("\">>},")
                    .append("{<<\"optional\">>,").append(requirements[i + 2]).append("},")
                    .append("{<<\"app\">>,<<\"").append(requirements[i]).append("\">>}]");
        }
        sb.append("]}.\n");
        return sb.toString();
    }

    /** A valid release tarball of {@code name} {@code version}. */
    static byte[] release(String name, String version) {
        return release(metadata(name, version, "The " + name + " package"));
    }

    static byte[] release(String metadataText) {
        byte[] version = "3".getBytes(StandardCharsets.US_ASCII);
        byte[] metadata = metadataText.getBytes(StandardCharsets.UTF_8);
        byte[] contents = gzip(tar(Map.of("lib/code.ex", "defmodule Code do\nend\n".getBytes(StandardCharsets.UTF_8))));
        byte[] checksum = HexFormat.of().withUpperCase().formatHex(HexTarball.innerChecksum(version, metadata, contents))
                .getBytes(StandardCharsets.US_ASCII);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("VERSION", version);
        files.put("CHECKSUM", checksum);
        files.put("metadata.config", metadata);
        files.put("contents.tar.gz", contents);
        return tar(files);
    }

    /** A ustar archive of regular files, in the order given. */
    static byte[] tar(Map<String, byte[]> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            out.writeBytes(header(e.getKey(), e.getValue().length, '0'));
            out.writeBytes(e.getValue());
            out.writeBytes(new byte[pad(e.getValue().length)]);
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    static byte[] header(String name, long size, char type) {
        byte[] h = new byte[512];
        put(h, 0, name.getBytes(StandardCharsets.UTF_8), 100);
        put(h, 100, "0000644\0".getBytes(StandardCharsets.US_ASCII), 8);
        put(h, 108, "0000000\0".getBytes(StandardCharsets.US_ASCII), 8);
        put(h, 116, "0000000\0".getBytes(StandardCharsets.US_ASCII), 8);
        put(h, 124, String.format("%011o\0", size).getBytes(StandardCharsets.US_ASCII), 12);
        put(h, 136, "14000000000\0".getBytes(StandardCharsets.US_ASCII), 12);
        h[156] = (byte) type;
        put(h, 257, "ustar\0".getBytes(StandardCharsets.US_ASCII), 6);
        put(h, 263, "00".getBytes(StandardCharsets.US_ASCII), 2);
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        int sum = 0;
        for (byte b : h) {
            sum += b & 0xFF;
        }
        put(h, 148, String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII), 8);
        return h;
    }

    static int pad(long size) {
        return (int) ((512 - size % 512) % 512);
    }

    private static void put(byte[] target, int offset, byte[] value, int max) {
        System.arraycopy(value, 0, target, offset, Math.min(value.length, max));
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] docsTarball() {
        return gzip(tar(Map.of("index.html", "<h1>docs</h1>".getBytes(StandardCharsets.UTF_8))));
    }

    // --- repositories and principals ---------------------------------------------------------------

    static Repository hosted(String name) {
        Repository r = repository(name, RepositoryType.HOSTED);
        r.setAllowRedeploy(false);
        return r;
    }

    static Repository proxy(String name, String remoteUrl) {
        Repository r = repository(name, RepositoryType.PROXY);
        r.setRemoteUrl(remoteUrl);
        return r;
    }

    static Repository group(String name, String... members) {
        Repository r = repository(name, RepositoryType.GROUP);
        r.setMembers(List.of(members));
        return r;
    }

    static Repository withSetting(Repository repo, String key, String value) {
        Map<String, String> settings = new LinkedHashMap<>(repo.getFormatSettings());
        settings.put(key, value);
        repo.setFormatSettings(settings);
        return repo;
    }

    private static Repository repository(String name, RepositoryType type) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setFormat(HexFormatHandler.FORMAT);
        r.setType(type);
        return r;
    }

    static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    static GranaryPrincipal anonymous() {
        return GranaryPrincipal.anonymous(Set.of("repository:read:*"));
    }

    static GranaryPrincipal user(String username) {
        return new GranaryPrincipal(UUID.randomUUID(), username, Set.of("developer"), Set.of("repository:write:*"),
                false, GranaryPrincipal.AuthMethod.API_TOKEN);
    }

    static GranaryPrincipal reader(String username) {
        return new GranaryPrincipal(UUID.randomUUID(), username, Set.of("viewer"), Set.of("repository:read:*"),
                false, GranaryPrincipal.AuthMethod.API_TOKEN);
    }

    static GranaryPrincipal admin(String username) {
        return new GranaryPrincipal(UUID.randomUUID(), username, Set.of("admin"), Set.of("system:admin"),
                false, GranaryPrincipal.AuthMethod.PASSWORD);
    }

    // --- requests and responses -------------------------------------------------------------------

    /** A request the way hex_core sends it: Accept application/vnd.hex+erlang, query parameters parsed. */
    static RepositoryRequest request(Repository repo, String method, String pathAndQuery, byte[] body, String contentType,
                                     GranaryPrincipal principal) {
        String path = pathAndQuery;
        String query = null;
        int q = pathAndQuery.indexOf('?');
        if (q >= 0) {
            path = pathAndQuery.substring(0, q);
            query = pathAndQuery.substring(q + 1);
        }
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        if (query != null) {
            http.setQueryString(query);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                http.addParameter(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
            }
        }
        if (body != null) {
            http.setContent(body);
        }
        if (contentType != null) {
            http.setContentType(contentType);
            http.addHeader("Content-Type", contentType);
        }
        if (path.startsWith("api")) {
            http.addHeader("Accept", ERLANG);
        }
        return new RepositoryRequest(repo, path, http, principal, baseUrl(repo));
    }

    static RepositoryRequest get(Repository repo, String path) {
        return request(repo, "GET", path, null, null, anonymous());
    }

    static RepositoryRequest get(Repository repo, String path, GranaryPrincipal principal) {
        return request(repo, "GET", path, null, null, principal);
    }

    static MockHttpServletRequest http(RepositoryRequest request) {
        return (MockHttpServletRequest) request.http();
    }

    static byte[] bodyBytes(ResponseEntity<?> response) {
        Object body = response.getBody();
        if (body == null) {
            return new byte[0];
        }
        if (body instanceof byte[] bytes) {
            return bytes;
        }
        if (body instanceof Resource resource) {
            try (var in = resource.getInputStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String body(ResponseEntity<?> response) {
        return new String(bodyBytes(response), StandardCharsets.UTF_8);
    }

    /** An Erlang-term response body decoded into maps and lists. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> termMap(ResponseEntity<?> response) {
        return Etf.asMap(Etf.decode(bodyBytes(response)));
    }

    @SuppressWarnings("unchecked")
    static List<Object> termList(ResponseEntity<?> response) {
        return (List<Object>) Etf.decode(bodyBytes(response));
    }
}
