package dev.granary.server.composer;

import dev.granary.core.storage.Checksums;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Package archives, upstream documents in the shapes packagist.org serves them, and the requests Composer 2.8 sends
 * (recorded from {@code composer require}, {@code composer update}, {@code composer audit} and {@code composer search}
 * against a running Granary).
 */
final class ComposerFixtures {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    /** MockMvc's view of the server: what {@code request.baseUrl()} resolves to in these tests. */
    static final String SERVER = "http://localhost";
    /** The User-Agent Composer sends on every request. */
    static final String USER_AGENT = "Composer/2.8.12 (Linux; 6.18.44; PHP 8.3.6; cURL 8.5.0)";
    /** The body of Composer's install notification ({@code notify-batch}) after installing three packages. */
    static final String NOTIFY_BODY = "{\"downloads\":[{\"name\":\"psr\\/log\",\"version\":\"3.0.2.0\"},"
            + "{\"name\":\"acme\\/lib\",\"version\":\"1.0.0.0\"}]}";
    /** The form body {@code composer audit} posts to the security advisory API. */
    static final String AUDIT_BODY = "packages%5B0%5D=psr%2Flog&packages%5B1%5D=acme%2Flib";

    /** A commit of php-fig/log, the reference Packagist names for psr/log 3.0.2. */
    static final String REF_302 = "f16e1d5863e37f8d8c2a01719f5b34baa2b714d3";
    static final String REF_301 = "79dff0b268932c640297f5208d6298f71855c03e";
    static final String REF_200 = "ef29f6d262798707a9edd554e2b82517ef3a9376";

    private ComposerFixtures() {
    }

    /**
     * Waits for the next wall-clock second. A {@code Last-Modified} in the current second is a weak validator that
     * Granary neither sends nor matches, so tests that need one let the second of the last change pass first.
     */
    static void nextSecond() throws InterruptedException {
        Thread.sleep(1000 - System.currentTimeMillis() % 1000 + 20);
    }

    static String repositoryUrl(String name) {
        return SERVER + "/repository/" + name;
    }

    // --- package archives --------------------------------------------------------------------------

    /** A library's composer.json; {@code version} is left out when null, {@code extra} is merged in when given. */
    static String composerJson(String name, String version, String extra) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"name\": \"").append(name).append("\",\n");
        if (version != null) {
            sb.append("  \"version\": \"").append(version).append("\",\n");
        }
        sb.append("  \"description\": \"Demo library ").append(name).append("\",\n");
        sb.append("  \"type\": \"library\",\n");
        sb.append("  \"license\": \"MIT\",\n");
        sb.append("  \"keywords\": [\"demo\", \"greeting\"],\n");
        sb.append("  \"authors\": [{\"name\": \"Acme Developers\", \"email\": \"dev@acme.example\"}],\n");
        sb.append("  \"require\": {\"php\": \">=8.1\", \"psr/log\": \"^3.0\"},\n");
        sb.append("  \"require-dev\": {\"phpunit/phpunit\": \"^11.0\"},\n");
        if (extra != null && !extra.isBlank()) {
            sb.append("  ").append(extra).append(",\n");
        }
        sb.append("  \"autoload\": {\"psr-4\": {\"Acme\\\\Lib\\\\\": \"src/\"}}\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** An archive as {@code composer archive} builds it: composer.json at the root. */
    static byte[] archive(String composerJson) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("composer.json", utf8(composerJson));
        entries.put("src/Greeter.php", utf8("<?php\nnamespace Acme\\Lib;\nfinal class Greeter {}\n"));
        return zip(entries);
    }

    /** An archive as GitHub and {@code git archive --prefix} build it: everything below one directory. */
    static byte[] githubArchive(String directory, String composerJson) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(directory + "/", new byte[0]);
        entries.put(directory + "/composer.json", utf8(composerJson));
        entries.put(directory + "/src/Greeter.php", utf8("<?php\n"));
        return zip(entries);
    }

    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String sha1(byte[] data) {
        return Checksums.hex("SHA-1", data);
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // --- upstream documents --------------------------------------------------------------------------

    /**
     * packagist.org's {@code packages.json}, with every API on the fake upstream: a root-relative
     * {@code metadata-url} like Satis writes, absolute URLs for the rest, plus the Composer 1 fields a proxy
     * must not pass on.
     */
    static String upstreamRoot(String upstream) {
        return "{\"packages\":[],"
                + "\"notify-batch\":\"" + upstream + "/downloads/\","
                + "\"providers-url\":\"/p/%package%$%hash%.json\","
                + "\"provider-includes\":{\"p/provider-latest$%hash%.json\":{\"sha256\":\"abc\"}},"
                + "\"metadata-url\":\"/p2/%package%.json\","
                + "\"metadata-changes-url\":\"" + upstream + "/metadata/changes.json\","
                + "\"search\":\"" + upstream + "/search.json?q=%query%&type=%type%\","
                + "\"list\":\"" + upstream + "/packages/list.json\","
                + "\"security-advisories\":{\"metadata\":true,\"api-url\":\"" + upstream + "/api/security-advisories/\"},"
                + "\"providers-api\":\"" + upstream + "/providers/%package%.json\","
                + "\"available-package-patterns\":[\"psr/*\"],"
                + "\"warning\":\"Support for Composer 1 has been shutdown\",\"warning-versions\":\"<1.999\","
                + "\"providers\":[],"
                + "\"filter\":{\"metadata\":true,\"lists\":{\"malware\":{\"enabled\":true}},\"summary-url\":\"" + upstream + "/lists/all/summary.json\"}}";
    }

    /**
     * {@code p2/psr/log.json} the way repo.packagist.org minifies it: three releases whose dist archives are GitHub
     * zipballs on {@code github}, with a per-package notification URL and a mirror list a proxy must not pass on.
     */
    static String upstreamPsrLog(String github) {
        return "{\"minified\":\"composer/2.0\",\"packages\":{\"psr/log\":["
                + "{\"name\":\"psr/log\",\"description\":\"Common interface for logging libraries\","
                + "\"keywords\":[\"log\",\"psr\",\"psr-3\"],\"homepage\":\"https://github.com/php-fig/log\","
                + "\"version\":\"3.0.2\",\"version_normalized\":\"3.0.2.0\",\"license\":[\"MIT\"],"
                + "\"authors\":[{\"name\":\"PHP-FIG\",\"homepage\":\"https://www.php-fig.org/\"}],"
                + "\"source\":{\"url\":\"https://github.com/php-fig/log.git\",\"type\":\"git\",\"reference\":\"" + REF_302 + "\"},"
                + "\"dist\":{\"url\":\"" + github + "/repos/php-fig/log/zipball/" + REF_302 + "\",\"type\":\"zip\",\"shasum\":\"\",\"reference\":\"" + REF_302 + "\","
                + "\"mirrors\":[{\"url\":\"" + github + "/mirror/%package%/%reference%.%type%\",\"preferred\":true}]},"
                + "\"type\":\"library\",\"notification-url\":\"" + github + "/downloads/\","
                + "\"time\":\"2024-09-11T13:17:53+00:00\",\"autoload\":{\"psr-4\":{\"Psr\\\\Log\\\\\":\"src\"}},"
                + "\"extra\":{\"branch-alias\":{\"dev-master\":\"3.x-dev\"}},\"require\":{\"php\":\">=8.0.0\"},"
                + "\"support\":{\"source\":\"https://github.com/php-fig/log/tree/3.0.2\"}},"
                + "{\"version\":\"3.0.1\",\"version_normalized\":\"3.0.1.0\","
                + "\"source\":{\"url\":\"https://github.com/php-fig/log.git\",\"type\":\"git\",\"reference\":\"" + REF_301 + "\"},"
                + "\"dist\":{\"url\":\"" + github + "/repos/php-fig/log/zipball/" + REF_301 + "\",\"type\":\"zip\",\"shasum\":\"\",\"reference\":\"" + REF_301 + "\"},"
                + "\"time\":\"2024-08-21T13:31:24+00:00\",\"support\":{\"source\":\"https://github.com/php-fig/log/tree/3.0.1\"}},"
                + "{\"version\":\"2.0.0\",\"version_normalized\":\"2.0.0.0\","
                + "\"source\":{\"url\":\"https://github.com/php-fig/log.git\",\"type\":\"git\",\"reference\":\"" + REF_200 + "\"},"
                + "\"dist\":{\"url\":\"" + github + "/repos/php-fig/log/zipball/" + REF_200 + "\",\"type\":\"zip\",\"shasum\":\"\",\"reference\":\"" + REF_200 + "\"},"
                + "\"time\":\"2021-07-14T16:41:46+00:00\",\"autoload\":{\"psr-4\":{\"Psr\\\\Log\\\\\":\"src/\"}},"
                + "\"extra\":\"__unset\",\"require\":{\"php\":\">=8.0.0\"},"
                + "\"support\":{\"source\":\"https://github.com/php-fig/log/tree/2.0.0\"}}"
                + "]},\"security-advisories\":[{\"advisoryId\":\"PKSA-test-0001\",\"affectedVersions\":\"<2.0.0\"}]}";
    }

    /** {@code p2/psr/log~dev.json}: the default branch, minified. */
    static String upstreamPsrLogDev(String github) {
        return "{\"minified\":\"composer/2.0\",\"packages\":{\"psr/log\":["
                + "{\"name\":\"psr/log\",\"description\":\"Common interface for logging libraries\","
                + "\"version\":\"dev-master\",\"version_normalized\":\"dev-master\",\"license\":[\"MIT\"],"
                + "\"source\":{\"url\":\"https://github.com/php-fig/log.git\",\"type\":\"git\",\"reference\":\"" + REF_302 + "\"},"
                + "\"dist\":{\"url\":\"" + github + "/repos/php-fig/log/zipball/" + REF_302 + "\",\"type\":\"zip\",\"shasum\":\"\",\"reference\":\"" + REF_302 + "\"},"
                + "\"default-branch\":true,\"type\":\"library\",\"extra\":{\"branch-alias\":{\"dev-master\":\"3.x-dev\"}}}"
                + "]}}";
    }

    /** A package that exists both privately and publicly: an older release and an impostor of the private 1.0.0. */
    static String upstreamAcmeLib(String github) {
        return "{\"minified\":\"composer/2.0\",\"packages\":{\"acme/lib\":["
                + "{\"name\":\"acme/lib\",\"description\":\"public impostor\",\"version\":\"1.0.0\",\"version_normalized\":\"1.0.0.0\","
                + "\"dist\":{\"url\":\"" + github + "/repos/acme/lib/zipball/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"type\":\"zip\",\"shasum\":\"\","
                + "\"reference\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},\"type\":\"library\"},"
                + "{\"description\":\"public release\",\"version\":\"0.9.0\",\"version_normalized\":\"0.9.0.0\","
                + "\"dist\":{\"url\":\"" + github + "/repos/acme/lib/zipball/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"type\":\"zip\",\"shasum\":\"\","
                + "\"reference\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}}"
                + "]}}";
    }

    /** packagist.org's search answer. */
    static String upstreamSearch(String... names) {
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(names[i]).append("\",\"description\":\"upstream ").append(names[i])
                    .append("\",\"url\":\"https://packagist.org/packages/").append(names[i])
                    .append("\",\"repository\":\"https://github.com/").append(names[i]).append("\",\"downloads\":1000,\"favers\":10}");
        }
        return sb.append("],\"total\":").append(names.length).append('}').toString();
    }

    /** The zipball GitHub serves for a commit: everything below {@code <owner>-<repo>-<short sha>/}. */
    static byte[] githubZipball(String name, String version, String reference) {
        String json = "{\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"autoload\":{\"psr-4\":{\"Psr\\\\Log\\\\\":\"src\"}}}";
        return githubArchive(name.replace('/', '-') + "-" + reference.substring(0, 7), json);
    }

    // --- JSON helpers --------------------------------------------------------------------------------

    static ObjectNode object(MvcResult result) throws Exception {
        return (ObjectNode) MAPPER.readTree(result.getResponse().getContentAsByteArray());
    }

    /** The version entries of a p2 document, expanded the way Composer's {@code MetadataMinifier::expand} does. */
    static Map<String, ObjectNode> versions(ObjectNode p2, String name) {
        Map<String, ObjectNode> byVersion = new LinkedHashMap<>();
        JsonNode list = p2.path("packages").path(name);
        boolean minified = "composer/2.0".equals(p2.path("minified").asString(""));
        ObjectNode current = null;
        for (JsonNode node : (ArrayNode) list) {
            ObjectNode entry = (ObjectNode) node;
            if (!minified || current == null) {
                current = entry.deepCopy();
            } else {
                for (Map.Entry<String, JsonNode> e : entry.properties()) {
                    if (e.getValue().isString() && "__unset".equals(e.getValue().stringValue())) {
                        current.remove(e.getKey());
                    } else {
                        current.set(e.getKey(), e.getValue().deepCopy());
                    }
                }
            }
            byVersion.put(current.get("version").asString(), current.deepCopy());
        }
        return byVersion;
    }

    static String str(JsonNode node, String... path) {
        JsonNode current = node;
        for (String p : path) {
            current = current.path(p);
        }
        return current.isMissingNode() || current.isNull() ? null : current.asString();
    }
}
