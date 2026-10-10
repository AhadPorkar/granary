package dev.granary.format.rubygems;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.springframework.mock.web.MockHttpServletRequest;

/** Fixtures shared by the RubyGems unit tests: repositories, requests and gems built the way {@code gem build} builds them. */
final class RubygemsTestSupport {

    static final String SERVER = "http://granary.test";

    private RubygemsTestSupport() {
    }

    // --- repositories -----------------------------------------------------------------------------

    static Repository hosted(String name) {
        return repository(name, RepositoryType.HOSTED);
    }

    static Repository proxy(String name) {
        Repository r = repository(name, RepositoryType.PROXY);
        r.setRemoteUrl("https://rubygems.example/");
        return r;
    }

    static Repository group(String name, String... members) {
        Repository r = repository(name, RepositoryType.GROUP);
        r.setMembers(List.of(members));
        return r;
    }

    static Repository repository(String name, RepositoryType type) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setFormat(RubygemsFormatHandler.FORMAT);
        r.setType(type);
        r.setBlobStore("default");
        return r;
    }

    static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    // --- requests ---------------------------------------------------------------------------------

    static GranaryPrincipal user(String name) {
        return new GranaryPrincipal(UUID.randomUUID(), name, Set.of("admin"), Set.of("*"), false,
                GranaryPrincipal.AuthMethod.PASSWORD);
    }

    static GranaryPrincipal anonymous() {
        return GranaryPrincipal.anonymous(Set.of());
    }

    static RepositoryRequest request(Repository repo, String method, String path) {
        return request(repo, method, path, Map.of(), null, user("admin"));
    }

    static RepositoryRequest request(Repository repo, String method, String path, Map<String, String> headers,
                                     byte[] body, GranaryPrincipal principal) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        headers.forEach(http::addHeader);
        if (headers.containsKey("Content-Type")) {
            http.setContentType(headers.get("Content-Type"));
        }
        if (body != null) {
            http.setContent(body);
        }
        return new RepositoryRequest(repo, path, http, principal, baseUrl(repo));
    }

    // --- gems -------------------------------------------------------------------------------------

    static byte[] resource(String name) {
        try (InputStream in = RubygemsTestSupport.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new AssertionError("missing test resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The YAML gemspec {@code gem build} writes for a gem with the given runtime dependencies ({@code "rake:>= 12.0"}). */
    static String gemspecYaml(String name, String version, String platform, String... runtimeDeps) {
        StringBuilder deps = new StringBuilder();
        for (String dep : runtimeDeps) {
            int colon = dep.indexOf(':');
            String depName = dep.substring(0, colon);
            String[] req = dep.substring(colon + 1).trim().split(" ", 2);
            String requirement = "  requirement: !ruby/object:Gem::Requirement\n"
                    + "    requirements:\n"
                    + "    - - \"" + req[0] + "\"\n"
                    + "      - !ruby/object:Gem::Version\n"
                    + "        version: '" + req[1] + "'\n";
            deps.append("- !ruby/object:Gem::Dependency\n")
                    .append("  name: ").append(depName).append('\n')
                    .append(requirement)
                    .append("  type: :runtime\n")
                    .append("  prerelease: false\n")
                    .append(requirement.replace("  requirement:", "  version_requirements:"));
        }
        return "--- !ruby/object:Gem::Specification\n"
                + "name: " + name + "\n"
                + "version: !ruby/object:Gem::Version\n"
                + "  version: " + version + "\n"
                + "platform: " + platform + "\n"
                + "authors:\n"
                + "- Acme Developers\n"
                + "autorequire:\n"
                + "bindir: bin\n"
                + "cert_chain: []\n"
                + "date: 2026-10-10 00:00:00.000000000 Z\n"
                + (runtimeDeps.length == 0 ? "dependencies: []\n" : "dependencies:\n" + deps)
                + "description: The " + name + " gem.\n"
                + "email: dev@acme.example\n"
                + "executables: []\n"
                + "extensions: []\n"
                + "extra_rdoc_files: []\n"
                + "files:\n"
                + "- lib/" + name + ".rb\n"
                + "homepage: https://acme.example/" + name + "\n"
                + "licenses:\n"
                + "- MIT\n"
                + "metadata:\n"
                + "  source_code_uri: https://acme.example/" + name + "/src\n"
                + "post_install_message:\n"
                + "rdoc_options: []\n"
                + "require_paths:\n"
                + "- lib\n"
                + "required_ruby_version: !ruby/object:Gem::Requirement\n"
                + "  requirements:\n"
                + "  - - \">=\"\n"
                + "    - !ruby/object:Gem::Version\n"
                + "      version: '2.7'\n"
                + "required_rubygems_version: !ruby/object:Gem::Requirement\n"
                + "  requirements:\n"
                + "  - - \">=\"\n"
                + "    - !ruby/object:Gem::Version\n"
                + "      version: '0'\n"
                + "requirements: []\n"
                + "rubygems_version: 3.5.22\n"
                + "signing_key:\n"
                + "specification_version: 4\n"
                + "summary: A gem called " + name + "\n"
                + "test_files: []\n";
    }

    static byte[] gem(String name, String version, String... runtimeDeps) {
        return platformGem(name, version, "ruby", runtimeDeps);
    }

    static byte[] platformGem(String name, String version, String platform, String... runtimeDeps) {
        return gemWithMetadata(gemspecYaml(name, version, platform, runtimeDeps), name);
    }

    /** A {@code .gem} with the given YAML gemspec, a one-file {@code data.tar.gz} and matching checksums. */
    static byte[] gemWithMetadata(String yaml, String seed) {
        byte[] metadata = gzip(yaml.getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("lib/" + seed + ".rb", ("module X; end # " + seed + "\n").getBytes(StandardCharsets.UTF_8));
        byte[] data = gzip(tar(files));
        String checksums = "---\nSHA256:\n  metadata.gz: " + sha256(metadata) + "\n  data.tar.gz: " + sha256(data) + "\n";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("metadata.gz", metadata);
        entries.put("data.tar.gz", data);
        entries.put("checksums.yaml.gz", gzip(checksums.getBytes(StandardCharsets.UTF_8)));
        return tar(entries);
    }

    /** A ustar archive of the entries, in order. */
    static byte[] tar(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            byte[] header = tarHeader(e.getKey(), e.getValue().length, '0');
            out.writeBytes(header);
            out.writeBytes(e.getValue());
            out.writeBytes(new byte[(512 - e.getValue().length % 512) % 512]);
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    static byte[] tarHeader(String name, long size, char type) {
        byte[] h = new byte[512];
        put(h, 0, name);
        put(h, 100, "0000444");
        put(h, 108, "0000000");
        put(h, 116, "0000000");
        put(h, 124, String.format("%011o", size));
        put(h, 136, String.format("%011o", 1_700_000_000L));
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        h[156] = (byte) type;
        put(h, 257, "ustar");
        h[263] = '0';
        h[264] = '0';
        put(h, 265, "wheel");
        put(h, 297, "wheel");
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, String.format("%06o", sum));
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    private static void put(byte[] header, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    static byte[] gzip(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}
