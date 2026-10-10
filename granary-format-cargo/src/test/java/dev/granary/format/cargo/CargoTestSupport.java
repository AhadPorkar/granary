package dev.granary.format.cargo;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Fixtures for the Cargo unit tests: repositories, crate archives, publish bodies the way cargo builds them, requests. */
final class CargoTestSupport {

    static final String SERVER = "http://granary.test";

    private CargoTestSupport() {
    }

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

    private static Repository repository(String name, RepositoryType type) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setFormat(CargoFormatHandler.FORMAT);
        r.setType(type);
        return r;
    }

    static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    // --- crate archives ------------------------------------------------------------------------

    /** A {@code .crate} as {@code cargo package} writes it: {@code <name>-<version>/Cargo.toml} and a source file. */
    static byte[] crate(String name, String version) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(name + "-" + version + "/Cargo.toml", manifest(name, version));
        files.put(name + "-" + version + "/Cargo.toml.orig", manifest(name, version));
        files.put(name + "-" + version + "/src/lib.rs", "pub fn hello() -> &'static str { \"" + name + "\" }\n");
        return tarGz(files);
    }

    static String manifest(String name, String version) {
        return "[package]\nname = \"" + name + "\"\nversion = \"" + version + "\"\nedition = \"2021\"\n";
    }

    /** A gzip-compressed ustar archive with regular files. */
    static byte[] tarGz(Map<String, String> files) {
        Tar tar = new Tar();
        files.forEach((path, content) -> tar.file(path, content.getBytes(StandardCharsets.UTF_8)));
        return gzip(tar.finish());
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

    /** Writes tar archives entry by entry, including the GNU and pax long-name extensions. */
    static final class Tar {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Tar file(String path, byte[] content) {
            return entry(path, '0', content);
        }

        Tar directory(String path) {
            return entry(path.endsWith("/") ? path : path + "/", '5', new byte[0]);
        }

        Tar entry(String path, char type, byte[] content) {
            out.writeBytes(header(path.length() > 99 ? path.substring(0, 99) : path, type, content.length, "ustar\0", true));
            out.writeBytes(padded(content));
            return this;
        }

        /** A GNU long name ({@code ././@LongLink}, type {@code L}) followed by the entry. */
        Tar gnuLongName(String path, byte[] content) {
            byte[] name = (path + "\0").getBytes(StandardCharsets.UTF_8);
            out.writeBytes(header("././@LongLink", 'L', name.length, "ustar ", true));
            out.writeBytes(padded(name));
            out.writeBytes(header(path.substring(0, 99), '0', content.length, "ustar ", true));
            out.writeBytes(padded(content));
            return this;
        }

        /** A pax extended header ({@code x}) carrying {@code path}, followed by the entry. */
        Tar paxPath(String path, byte[] content) {
            String record = " path=" + path + "\n";
            int length = record.getBytes(StandardCharsets.UTF_8).length;
            int total = length + String.valueOf(length).length();
            if (String.valueOf(total).length() != String.valueOf(length).length()) {
                total++;
            }
            byte[] pax = (total + record).getBytes(StandardCharsets.UTF_8);
            out.writeBytes(header("PaxHeaders/x", 'x', pax.length, "ustar\0", true));
            out.writeBytes(padded(pax));
            out.writeBytes(header("truncated-name", '0', content.length, "ustar\0", true));
            out.writeBytes(padded(content));
            return this;
        }

        /** A header whose checksum is wrong. */
        Tar corrupt(String path) {
            byte[] header = header(path, '0', 0, "ustar\0", false);
            out.writeBytes(header);
            return this;
        }

        byte[] finish() {
            out.writeBytes(new byte[1024]);
            return out.toByteArray();
        }

        private static byte[] header(String name, char type, long size, String magic, boolean validChecksum) {
            byte[] h = new byte[512];
            put(h, 0, name.getBytes(StandardCharsets.UTF_8), 100);
            put(h, 100, octal(0644, 8), 8);
            put(h, 108, octal(0, 8), 8);
            put(h, 116, octal(0, 8), 8);
            put(h, 124, octal(size, 12), 12);
            put(h, 136, octal(1_700_000_000L, 12), 12);
            h[156] = (byte) type;
            put(h, 257, magic.getBytes(StandardCharsets.US_ASCII), 6);
            put(h, 263, (magic.equals("ustar ") ? " \0" : "00").getBytes(StandardCharsets.US_ASCII), 2);
            for (int i = 148; i < 156; i++) {
                h[i] = ' ';
            }
            long sum = 0;
            for (byte b : h) {
                sum += b & 0xff;
            }
            put(h, 148, octal(validChecksum ? sum : sum + 1, 7), 7);
            h[155] = ' ';
            return h;
        }

        private static byte[] octal(long value, int width) {
            String s = Long.toOctalString(value);
            String padded = "0".repeat(Math.max(0, width - 1 - s.length())) + s;
            return (padded + "\0").getBytes(StandardCharsets.US_ASCII);
        }

        private static void put(byte[] target, int offset, byte[] value, int max) {
            System.arraycopy(value, 0, target, offset, Math.min(value.length, max));
        }

        private static byte[] padded(byte[] content) {
            int size = (content.length + 511) / 512 * 512;
            byte[] out = new byte[size];
            System.arraycopy(content, 0, out, 0, content.length);
            return out;
        }
    }

    // --- publish bodies ------------------------------------------------------------------------

    /** The metadata cargo 1.97 sends for a crate with one dependency on {@code itoa} from crates.io. */
    static ObjectNode metadata(String name, String version) {
        ObjectNode m = CargoJson.object();
        m.put("name", name);
        m.put("vers", version);
        ArrayNode deps = m.putArray("deps");
        ObjectNode itoa = deps.addObject();
        itoa.put("optional", false);
        itoa.put("default_features", true);
        itoa.put("name", "itoa");
        itoa.putArray("features");
        itoa.put("version_req", "^1");
        itoa.putNull("target");
        itoa.put("kind", "normal");
        itoa.put("registry", "https://github.com/rust-lang/crates.io-index");
        itoa.putNull("explicit_name_in_toml");
        m.putObject("features");
        m.putArray("authors");
        m.put("description", "Hello from " + name);
        m.putNull("documentation");
        m.putNull("homepage");
        m.putNull("readme");
        m.putNull("readme_file");
        m.putArray("keywords").add("greeting").add("demo");
        m.putArray("categories");
        m.put("license", "MIT");
        m.putNull("license_file");
        m.putNull("repository");
        m.putObject("badges");
        m.putNull("links");
        m.putNull("rust_version");
        return m;
    }

    static byte[] publishBody(ObjectNode metadata, byte[] crate) {
        return PublishRequest.encode(CargoJson.bytes(metadata), crate);
    }

    static byte[] publishBody(String name, String version) {
        return publishBody(metadata(name, version), crate(name, version));
    }

    // --- requests ------------------------------------------------------------------------------

    static GranaryPrincipal user(String username) {
        return new GranaryPrincipal(UUID.randomUUID(), username, Set.of("admin"), Set.of("*"), false,
                GranaryPrincipal.AuthMethod.API_TOKEN);
    }

    static RepositoryRequest request(Repository repo, String method, String path) {
        return request(repo, method, path, null, Map.of());
    }

    static RepositoryRequest request(Repository repo, String method, String path, byte[] body, Map<String, String> headers) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        headers.forEach(http::addHeader);
        int q = path.indexOf('?');
        String bare = path;
        if (q >= 0) {
            bare = path.substring(0, q);
            for (String pair : path.substring(q + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                http.addParameter(kv[0], kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        if (body != null) {
            http.setContent(body);
        }
        return new RepositoryRequest(repo, bare, http, user("alice"), baseUrl(repo));
    }

    /** The body of a response as bytes, whatever the handler put in it. */
    static byte[] body(ResponseEntity<?> response) {
        Object body = response.getBody();
        if (body == null) {
            return new byte[0];
        }
        if (body instanceof byte[] bytes) {
            return bytes;
        }
        if (body instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        if (body instanceof Resource resource) {
            try (var in = resource.getInputStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return CargoJson.bytes(CargoJson.MAPPER.valueToTree(body));
    }

    static String text(ResponseEntity<?> response) {
        return new String(body(response), StandardCharsets.UTF_8);
    }

    static ObjectNode json(ResponseEntity<?> response) {
        return CargoJson.parseObject(body(response)).orElseThrow(() -> new AssertionError("not a JSON object: " + text(response)));
    }

    static String errorDetail(ResponseEntity<?> response) {
        JsonNode detail = json(response).path("errors").path(0).path("detail");
        return detail.isString() ? detail.stringValue() : null;
    }
}
