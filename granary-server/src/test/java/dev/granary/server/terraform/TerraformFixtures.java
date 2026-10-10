package dev.granary.server.terraform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Provider releases, module archives and registry documents the way HashiCorp's tooling and registry.terraform.io produce them. */
final class TerraformFixtures {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    /** MockMvc's view of the server: what {@code request.baseUrl()} resolves to in these tests. */
    static final String SERVER = "http://localhost";
    static final String HASHICORP_KEY_ID = "34365D9472D7468F";
    static final String ARMOR = "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nmQINBGB9+xkBEACabYZOWKmgZsHTdRDiyPJxhbuUiKX3bZpdUYmvmHBCUm0ECSSV\n-----END PGP PUBLIC KEY BLOCK-----\n";

    private TerraformFixtures() {
    }

    static String repositoryUrl(String name) {
        return SERVER + "/repository/" + name;
    }

    static String archiveName(String type, String version, String os, String arch) {
        return "terraform-provider-" + type + "_" + version + "_" + os + "_" + arch + ".zip";
    }

    /** A genuine zip with one entry; the content differs per seed so digests differ. */
    static byte[] providerZip(String seed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("terraform-provider-null_v3.2.4_x5"));
            zip.write(("ELF provider binary " + seed).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] moduleTgz(String seed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(("tar archive of module " + seed).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code sha256sum} output for the given files. */
    static String shaSums(Object... nameAndBytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < nameAndBytes.length; i += 2) {
            sb.append(sha256((byte[]) nameAndBytes[i + 1])).append("  ").append(nameAndBytes[i]).append('\n');
        }
        return sb.toString();
    }

    /** Looks like a binary OpenPGP signature packet; nothing verifies it server side. */
    static byte[] signature() {
        return new byte[] {(byte) 0x89, 0x02, 0x33, 0x04, 0x00, 0x01, 0x08, 0x00, 0x1d, 0x16, 0x21, 0x04};
    }

    static String gpgKey(String keyId) {
        ObjectNode key = MAPPER.createObjectNode();
        key.put("key_id", keyId);
        key.put("ascii_armor", ARMOR);
        return MAPPER.writeValueAsString(key);
    }

    static String signingKeys(String keyId) {
        return "{\"gpg_public_keys\":[{\"key_id\":\"" + keyId + "\",\"ascii_armor\":" + MAPPER.writeValueAsString(ARMOR)
                + ",\"trust_signature\":\"\",\"source\":\"HashiCorp\",\"source_url\":\"https://www.hashicorp.com/security.html\"}]}";
    }

    /** A {@code versions} document as registry.terraform.io serves it. */
    static String upstreamVersions(String ns, String type, String... versionAndPlatforms) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("id", ns + "/" + type);
        var versions = doc.putArray("versions");
        for (String spec : versionAndPlatforms) {
            String[] parts = spec.split(":");
            ObjectNode v = versions.addObject();
            v.put("version", parts[0]);
            v.putArray("protocols").add("5.0");
            var platforms = v.putArray("platforms");
            for (String p : parts[1].split(",")) {
                String[] oa = p.split("_");
                platforms.addObject().put("os", oa[0]).put("arch", oa[1]);
            }
        }
        doc.putNull("warnings");
        return MAPPER.writeValueAsString(doc);
    }

    /** A download document as registry.terraform.io serves it, with the files on {@code releasesBase}. */
    static String upstreamDownload(String releasesBase, String type, String version, String os, String arch, String shasum) {
        String file = archiveName(type, version, os, arch);
        ObjectNode doc = MAPPER.createObjectNode();
        doc.putArray("protocols").add("5.0");
        doc.put("os", os);
        doc.put("arch", arch);
        doc.put("filename", file);
        doc.put("download_url", releasesBase + "/terraform-provider-" + type + "/" + version + "/" + file);
        doc.put("shasums_url", releasesBase + "/terraform-provider-" + type + "/" + version + "/terraform-provider-" + type + "_" + version + "_SHA256SUMS");
        doc.put("shasums_signature_url", releasesBase + "/terraform-provider-" + type + "/" + version + "/terraform-provider-" + type + "_" + version + "_SHA256SUMS.sig");
        doc.put("shasum", shasum);
        doc.set("signing_keys", MAPPER.readTree(signingKeys(HASHICORP_KEY_ID)));
        return MAPPER.writeValueAsString(doc);
    }

    static String upstreamModuleVersions(String ns, String name, String system, String... versions) {
        ObjectNode doc = MAPPER.createObjectNode();
        ObjectNode module = doc.putArray("modules").addObject();
        module.put("source", ns + "/" + name + "/" + system);
        var array = module.putArray("versions");
        for (String v : versions) {
            ObjectNode entry = array.addObject();
            entry.put("version", v);
            entry.putObject("root").putArray("providers");
            entry.putArray("submodules");
        }
        return MAPPER.writeValueAsString(doc);
    }

    static ObjectNode object(MvcResult result) {
        try {
            return (ObjectNode) MAPPER.readTree(result.getResponse().getContentAsByteArray());
        } catch (RuntimeException e) {
            throw new AssertionError("not a JSON object: " + new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), e);
        }
    }

    static String str(JsonNode node, String... path) {
        JsonNode n = node;
        for (String p : path) {
            n = n.path(p);
        }
        return n.isString() ? n.stringValue() : null;
    }
}
