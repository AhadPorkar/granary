package dev.granary.server.rubygems;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * {@code .gem} files built the way {@code gem build} builds them (a tar of {@code metadata.gz}, {@code data.tar.gz}
 * and {@code checksums.yaml.gz}), plus readers for the Marshal indexes, for the RubyGems integration tests.
 */
final class RubygemsFixtures {

    /** MockMvc's view of the server: what {@code request.baseUrl()} resolves to in these tests. */
    static final String SERVER = "http://localhost";

    private RubygemsFixtures() {
    }

    static String repositoryUrl(String name) {
        return SERVER + "/repository/" + name;
    }

    // --- gems ---------------------------------------------------------------------------------------

    /** A gem with the given runtime dependencies, each written {@code "rake:>= 12.0"}. */
    static byte[] gem(String name, String version, String... runtimeDeps) {
        return platformGem(name, version, "ruby", runtimeDeps);
    }

    static byte[] platformGem(String name, String version, String platform, String... runtimeDeps) {
        byte[] metadata = gzip(gemspec(name, version, platform, runtimeDeps).getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("lib/" + name + ".rb", ("module X; end # " + name + " " + version + "\n").getBytes(StandardCharsets.UTF_8));
        byte[] data = gzip(tar(files));
        String checksums = "---\nSHA256:\n  metadata.gz: " + sha256(metadata) + "\n  data.tar.gz: " + sha256(data) + "\n";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("metadata.gz", metadata);
        entries.put("data.tar.gz", data);
        entries.put("checksums.yaml.gz", gzip(checksums.getBytes(StandardCharsets.UTF_8)));
        return tar(entries);
    }

    static String gemspec(String name, String version, String platform, String... runtimeDeps) {
        StringBuilder deps = new StringBuilder();
        for (String dep : runtimeDeps) {
            int colon = dep.indexOf(':');
            String[] req = dep.substring(colon + 1).trim().split(" ", 2);
            String requirement = "  requirement: !ruby/object:Gem::Requirement\n"
                    + "    requirements:\n"
                    + "    - - \"" + req[0] + "\"\n"
                    + "      - !ruby/object:Gem::Version\n"
                    + "        version: '" + req[1] + "'\n";
            deps.append("- !ruby/object:Gem::Dependency\n")
                    .append("  name: ").append(dep, 0, colon).append('\n')
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
                + "authors:\n- Acme Developers\n"
                + "bindir: bin\n"
                + "cert_chain: []\n"
                + "date: 2026-10-10 00:00:00.000000000 Z\n"
                + (runtimeDeps.length == 0 ? "dependencies: []\n" : "dependencies:\n" + deps)
                + "description: The " + name + " gem.\n"
                + "email: dev@acme.example\n"
                + "executables: []\n"
                + "extensions: []\n"
                + "extra_rdoc_files: []\n"
                + "files:\n- lib/" + name + ".rb\n"
                + "homepage: https://acme.example/" + name + "\n"
                + "licenses:\n- MIT\n"
                + "metadata: {}\n"
                + "rdoc_options: []\n"
                + "require_paths:\n- lib\n"
                + "required_ruby_version: !ruby/object:Gem::Requirement\n"
                + "  requirements:\n  - - \">=\"\n    - !ruby/object:Gem::Version\n      version: '2.7'\n"
                + "required_rubygems_version: !ruby/object:Gem::Requirement\n"
                + "  requirements:\n  - - \">=\"\n    - !ruby/object:Gem::Version\n      version: '0'\n"
                + "requirements: []\n"
                + "rubygems_version: 3.5.22\n"
                + "specification_version: 4\n"
                + "summary: A gem called " + name + "\n"
                + "test_files: []\n";
    }

    static byte[] tar(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            byte[] header = new byte[512];
            put(header, 0, e.getKey());
            put(header, 100, "0000444");
            put(header, 108, "0000000");
            put(header, 116, "0000000");
            put(header, 124, String.format("%011o", e.getValue().length));
            put(header, 136, String.format("%011o", 1_700_000_000L));
            for (int i = 148; i < 156; i++) {
                header[i] = ' ';
            }
            header[156] = '0';
            put(header, 257, "ustar");
            header[263] = '0';
            header[264] = '0';
            long sum = 0;
            for (byte b : header) {
                sum += b & 0xff;
            }
            put(header, 148, String.format("%06o", sum));
            header[154] = 0;
            out.writeBytes(header);
            out.writeBytes(e.getValue());
            out.writeBytes(new byte[(512 - e.getValue().length % 512) % 512]);
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
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

    static byte[] gunzip(byte[] gz) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] inflate(byte[] deflated) {
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(deflated))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(byte[] bytes) {
        return hex("SHA-256", bytes);
    }

    static String md5(String text) {
        return hex("MD5", text.getBytes(StandardCharsets.UTF_8));
    }

    static String hex(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- the compact index ----------------------------------------------------------------------------

    /** The lines after {@code ---}. */
    static List<String> lines(String document) {
        List<String> all = document.lines().toList();
        int separator = all.indexOf("---");
        return new ArrayList<>(all.subList(separator + 1, all.size()));
    }

    /**
     * A {@code versions} file as Bundler reads it: every gem's versions after applying additions and {@code -}
     * removals, and the info checksum of the last line naming it.
     */
    static Map<String, String[]> versions(String document) {
        Map<String, List<String>> versions = new LinkedHashMap<>();
        Map<String, String> md5s = new LinkedHashMap<>();
        for (String line : lines(document)) {
            String[] parts = line.split(" ");
            List<String> list = versions.computeIfAbsent(parts[0], k -> new ArrayList<>());
            for (String v : parts[1].split(",")) {
                if (v.startsWith("-")) {
                    list.remove(v.substring(1));
                } else if (!list.contains(v)) {
                    list.add(v);
                }
            }
            md5s.put(parts[0], parts[2]);
        }
        Map<String, String[]> result = new LinkedHashMap<>();
        versions.forEach((name, list) -> {
            List<String> entry = new ArrayList<>(list);
            entry.add(md5s.get(name));
            result.put(name, entry.toArray(String[]::new));
        });
        return result;
    }

    // --- the Marshal indexes -------------------------------------------------------------------------

    /**
     * The tuples of a gzipped {@code specs.4.8.gz}-style index as {@code name version platform} strings. A small
     * reader for exactly what these indexes contain (arrays, strings, symbols, links and {@code Gem::Version}), so the
     * tests do not rely on the code under test to check its own output.
     */
    static List<String> specs(byte[] gzipped) {
        return new MiniMarshal(gunzip(gzipped)).tuples();
    }

    private static final class MiniMarshal {
        private final byte[] data;
        private int pos;
        private final List<Object> objects = new ArrayList<>();
        private final List<String> symbols = new ArrayList<>();

        MiniMarshal(byte[] data) {
            this.data = data;
        }

        List<String> tuples() {
            if (data[0] != 4 || data[1] != 8) {
                throw new IllegalArgumentException("not Marshal 4.8");
            }
            pos = 2;
            Object root = read();
            List<String> result = new ArrayList<>();
            for (Object element : (List<?>) root) {
                List<?> tuple = (List<?>) element;
                result.add(tuple.get(0) + " " + tuple.get(1) + " " + tuple.get(2));
            }
            if (pos != data.length) {
                throw new IllegalArgumentException("trailing bytes");
            }
            return result;
        }

        private Object read() {
            int type = data[pos++];
            switch (type) {
                case '0':
                    return null;
                case 'T':
                    return Boolean.TRUE;
                case 'F':
                    return Boolean.FALSE;
                case 'i':
                    return (long) readLong();
                case ':': {
                    String s = new String(readBytes(), StandardCharsets.UTF_8);
                    symbols.add(s);
                    return s;
                }
                case ';':
                    return symbols.get(readLong());
                case '@':
                    return objects.get(readLong());
                case '"': {
                    String s = new String(readBytes(), StandardCharsets.UTF_8);
                    objects.add(s);
                    return s;
                }
                case 'I': {
                    Object inner = read();
                    int ivars = readLong();
                    for (int i = 0; i < ivars; i++) {
                        read();
                        read();
                    }
                    return inner;
                }
                case '[': {
                    List<Object> list = new ArrayList<>();
                    objects.add(list);
                    int n = readLong();
                    for (int i = 0; i < n; i++) {
                        list.add(read());
                    }
                    return list;
                }
                case 'U': {
                    int index = objects.size();
                    objects.add(null);
                    Object className = read();
                    Object state = read();
                    String value = "Gem::Version".equals(className) ? (String) ((List<?>) state).getFirst() : String.valueOf(state);
                    objects.set(index, value);
                    return value;
                }
                default:
                    throw new IllegalArgumentException("unexpected type " + (char) type + " at " + (pos - 1));
            }
        }

        private byte[] readBytes() {
            int n = readLong();
            byte[] bytes = java.util.Arrays.copyOfRange(data, pos, pos + n);
            pos += n;
            return bytes;
        }

        private int readLong() {
            int c = data[pos++];
            if (c == 0) {
                return 0;
            }
            if (c > 4) {
                return c - 5;
            }
            if (c < -4) {
                return c + 5;
            }
            if (c > 0) {
                int x = 0;
                for (int i = 0; i < c; i++) {
                    x |= (data[pos++] & 0xff) << (8 * i);
                }
                return x;
            }
            int x = -1;
            for (int i = 0; i < -c; i++) {
                x &= ~(0xff << (8 * i));
                x |= (data[pos++] & 0xff) << (8 * i);
            }
            return x;
        }
    }
}
