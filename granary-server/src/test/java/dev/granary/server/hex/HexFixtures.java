package dev.granary.server.hex;

import dev.granary.format.hex.Etf;
import dev.granary.format.hex.HexRegistry;
import dev.granary.format.hex.HexSigningKey;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Requests and payloads exactly as mix 2.5 / hex_core 0.18 send them (recorded from the real client): release
 * tarballs as {@code mix hex.build} writes them, Erlang-term bodies, and helpers to open signed registry documents.
 */
final class HexFixtures {

    /** What hex_core sends as Accept on API calls and as Content-Type on term bodies. */
    static final String ERLANG = "application/vnd.hex+erlang";
    /** The User-Agent mix 2.5.1 sent in the recording. */
    static final String USER_AGENT = "hex_core/0.18.0 hex/2.5.1 (elixir/1.18.4) (OTP/27) (erts/15.2.7.1)";
    /** MockMvc's view of the server: what the repository base URL resolves to in these tests. */
    static final String SERVER = "http://localhost";

    private HexFixtures() {
    }

    static String repositoryUrl(String name) {
        return SERVER + "/repository/" + name;
    }

    // --- release tarballs ---------------------------------------------------------------------------

    /** metadata.config the way hex_tarball writes it; requirements come in (name, requirement, optional) triples. */
    static String metadata(String name, String version, String... requirements) {
        StringBuilder sb = new StringBuilder();
        sb.append("{<<\"links\">>,[{<<\"Source\">>,<<\"https://example.test/").append(name).append("\">>}]}.\n");
        sb.append("{<<\"name\">>,<<\"").append(name).append("\">>}.\n");
        sb.append("{<<\"version\">>,<<\"").append(version).append("\">>}.\n");
        sb.append("{<<\"description\">>,<<\"The ").append(name).append(" package ✓\"/utf8>>}.\n");
        sb.append("{<<\"elixir\">>,<<\"~> 1.15\">>}.\n");
        sb.append("{<<\"app\">>,<<\"").append(name).append("\">>}.\n");
        sb.append("{<<\"licenses\">>,[<<\"Apache-2.0\">>]}.\n");
        sb.append("{<<\"build_tools\">>,[<<\"mix\">>]}.\n");
        sb.append("{<<\"files\">>,[<<\"lib\">>,<<\"lib/").append(name).append(".ex\">>,<<\"mix.exs\">>]}.\n");
        sb.append("{<<\"requirements\">>,[");
        for (int i = 0; i + 2 < requirements.length; i += 3) {
            sb.append(i > 0 ? "," : "")
                    .append("[{<<\"name\">>,<<\"").append(requirements[i]).append("\">>},")
                    .append("{<<\"repository\">>,<<\"hexpm\">>},")
                    .append("{<<\"requirement\">>,<<\"").append(requirements[i + 1]).append("\">>},")
                    .append("{<<\"optional\">>,").append(requirements[i + 2]).append("},")
                    .append("{<<\"app\">>,<<\"").append(requirements[i]).append("\">>}]");
        }
        sb.append("]}.\n");
        return sb.toString();
    }

    static byte[] release(String name, String version, String... requirements) {
        return releaseWith(metadata(name, version, requirements));
    }

    static byte[] releaseWith(String metadataText) {
        byte[] version = "3".getBytes(StandardCharsets.US_ASCII);
        byte[] metadata = metadataText.getBytes(StandardCharsets.UTF_8);
        byte[] contents = gzip(tar(Map.of("lib/code.ex", "defmodule Code do\nend\n".getBytes(StandardCharsets.UTF_8))));
        MessageDigest digest = sha256Digest();
        digest.update(version);
        digest.update(metadata);
        digest.update(contents);
        byte[] checksum = HexFormat.of().withUpperCase().formatHex(digest.digest()).getBytes(StandardCharsets.US_ASCII);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("VERSION", version);
        files.put("CHECKSUM", checksum);
        files.put("metadata.config", metadata);
        files.put("contents.tar.gz", contents);
        return tar(files);
    }

    static byte[] docsTarball(String marker) {
        return gzip(tar(Map.of("index.html", ("<h1>" + marker + "</h1>").getBytes(StandardCharsets.UTF_8))));
    }

    static byte[] tar(Map<String, byte[]> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            byte[] h = new byte[512];
            put(h, 0, e.getKey());
            put(h, 100, "0000644");
            put(h, 108, "0000000");
            put(h, 116, "0000000");
            put(h, 124, String.format("%011o", e.getValue().length));
            put(h, 136, "14000000000");
            h[156] = '0';
            put(h, 257, "ustar");
            put(h, 263, "00");
            for (int i = 148; i < 156; i++) {
                h[i] = ' ';
            }
            int sum = 0;
            for (byte b : h) {
                sum += b & 0xFF;
            }
            put(h, 148, String.format("%06o", sum));
            h[154] = 0;
            out.writeBytes(h);
            out.writeBytes(e.getValue());
            out.writeBytes(new byte[(512 - e.getValue().length % 512) % 512]);
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    private static void put(byte[] target, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
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

    static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256Digest().digest(data));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- Erlang terms ------------------------------------------------------------------------------

    /** The body {@code mix hex.retire} posts: {@code %{"reason" => …, "message" => …}} as an external term. */
    static byte[] retireBody(String reason, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("reason", reason);
        return Etf.encode(body);
    }

    static Map<String, Object> term(MvcResult result) {
        return Etf.asMap(Etf.decode(result.getResponse().getContentAsByteArray()));
    }

    @SuppressWarnings("unchecked")
    static List<Object> termList(MvcResult result) {
        return (List<Object>) Etf.decode(result.getResponse().getContentAsByteArray());
    }

    static Map<String, Object> map(Object term) {
        return Etf.asMap(term);
    }

    // --- registry documents ------------------------------------------------------------------------

    /** Opens a served registry document, checking its signature against {@code publicKeyPem}. */
    static byte[] verifiedPayload(byte[] document, String publicKeyPem) {
        HexRegistry.Signed signed = HexRegistry.decodeSigned(HexRegistry.gunzip(document));
        if (!HexSigningKey.verify(signed.payload(), signed.signature(), HexSigningKey.parsePublicKey(publicKeyPem))) {
            throw new AssertionError("registry document signature does not verify");
        }
        return signed.payload();
    }

    static HexRegistry.Package pkg(byte[] document, String publicKeyPem) {
        return HexRegistry.decodePackage(verifiedPayload(document, publicKeyPem));
    }

    static HexRegistry.Names names(byte[] document, String publicKeyPem) {
        return HexRegistry.decodeNames(verifiedPayload(document, publicKeyPem));
    }

    static HexRegistry.Versions versions(byte[] document, String publicKeyPem) {
        return HexRegistry.decodeVersions(verifiedPayload(document, publicKeyPem));
    }

    /** An upstream document the way repo.hex.pm serves it: gzip(Signed{payload, signature}). */
    static byte[] signedDocument(byte[] payload, HexSigningKey key) {
        return HexRegistry.pack(payload, key::sign);
    }
}
