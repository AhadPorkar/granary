package dev.granary.server.npm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Request bodies exactly as the npm CLI sends them, plus small JSON helpers for the npm integration tests. */
final class NpmFixtures {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    /** MockMvc's view of the server: what {@code request.baseUrl()} resolves to in these tests. */
    static final String SERVER = "http://localhost";

    private NpmFixtures() {
    }

    static String repositoryUrl(String name) {
        return SERVER + "/repository/" + name;
    }

    static byte[] tarball(String seed) {
        return ("\u001f\u008b fake tarball " + seed).getBytes(StandardCharsets.UTF_8);
    }

    /** The document {@code npm publish} produces (libnpmpublish's {@code buildMetadata}). */
    static ObjectNode publishDocument(String name, String version, byte[] tarball, String tag) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("_id", name);
        doc.put("name", name);
        doc.put("description", "description of " + name + "@" + version);
        doc.put("readme", "# " + name);
        doc.putObject("dist-tags").put(tag, version);
        ObjectNode v = doc.putObject("versions").putObject(version);
        v.put("name", name);
        v.put("version", version);
        v.put("description", "description of " + name + "@" + version);
        v.put("license", "MIT");
        v.put("_id", name + "@" + version);
        v.put("_nodeVersion", "22.0.0");
        v.put("_npmVersion", "10.9.4");
        ObjectNode dist = v.putObject("dist");
        dist.put("integrity", "sha512-" + Base64.getEncoder().encodeToString(digest("SHA-512", tarball)));
        dist.put("shasum", HexFormat.of().formatHex(digest("SHA-1", tarball)));
        String file = name + "-" + version + ".tgz";
        dist.put("tarball", "http://localhost:8081/repository/npm-hosted/" + name + "/-/" + file);
        doc.put("access", "public");
        ObjectNode attachment = doc.putObject("_attachments").putObject(file);
        attachment.put("content_type", "application/octet-stream");
        attachment.put("data", Base64.getEncoder().encodeToString(tarball));
        attachment.put("length", tarball.length);
        return doc;
    }

    static byte[] publishBody(String name, String version, byte[] tarball) {
        return MAPPER.writeValueAsBytes(publishDocument(name, version, tarball, "latest"));
    }

    static byte[] publishBody(String name, String version, byte[] tarball, String tag) {
        return MAPPER.writeValueAsBytes(publishDocument(name, version, tarball, tag));
    }

    /** A packument as an upstream registry would serve it, with the tarball URL on that upstream. */
    static String upstreamPackument(String upstreamBase, String name, String... versions) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("_id", name);
        doc.put("_rev", "7-upstream");
        doc.put("name", name);
        doc.put("description", "upstream " + name);
        ObjectNode tags = doc.putObject("dist-tags");
        ObjectNode vs = doc.putObject("versions");
        ObjectNode time = doc.putObject("time");
        time.put("created", "2020-01-01T00:00:00.000Z");
        String latest = null;
        for (String version : versions) {
            ObjectNode v = vs.putObject(version);
            v.put("name", name);
            v.put("version", version);
            v.put("description", "upstream " + name + " " + version);
            ObjectNode dist = v.putObject("dist");
            dist.put("shasum", "0000000000000000000000000000000000000000");
            String unscoped = name.contains("/") ? name.substring(name.indexOf('/') + 1) : name;
            dist.put("tarball", upstreamBase + "/" + name + "/-/" + unscoped + "-" + version + ".tgz");
            time.put(version, "2021-01-01T00:00:00.000Z");
            latest = version;
        }
        time.put("modified", "2021-01-01T00:00:00.000Z");
        if (latest != null) {
            tags.put("latest", latest);
        }
        return MAPPER.writeValueAsString(doc);
    }

    static JsonNode json(MvcResult result) {
        return MAPPER.readTree(result.getResponse().getContentAsByteArray());
    }

    static ObjectNode object(MvcResult result) {
        return (ObjectNode) json(result);
    }

    static String str(JsonNode node, String... path) {
        JsonNode n = node;
        for (String p : path) {
            n = n.path(p);
        }
        return n.isString() ? n.stringValue() : null;
    }

    static byte[] bytes(JsonNode node) {
        return MAPPER.writeValueAsBytes(node);
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
