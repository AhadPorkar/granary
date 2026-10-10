package dev.granary.server.helm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Charts, provenance files and upstream indexes built in code, and the requests the helm CLI, {@code helm cm-push}
 * and curl send.
 */
final class HelmFixtures {

    static final String SERVER = "http://localhost";

    private HelmFixtures() {
    }

    static String repositoryUrl(String repository) {
        return SERVER + "/repository/" + repository;
    }

    // --- charts ----------------------------------------------------------------------------------

    /** A packaged chart laid out like {@code helm package} writes it. */
    static byte[] chart(String name, String version) {
        return chart(name, version, "A Helm chart for Kubernetes");
    }

    /** A chart whose description tells copies of the same version apart. */
    static byte[] chart(String name, String version, String description) {
        String chartYaml = "apiVersion: v2\nname: " + name + "\ndescription: " + description + "\ntype: application\n"
                + "version: " + version + "\nappVersion: \"1.16.0\"\n";
        return chartWithYaml(name, chartYaml);
    }

    static byte[] chartWithYaml(String directory, String chartYaml) {
        return gzip(tar(Map.of(
                directory + "/Chart.yaml", chartYaml,
                directory + "/values.yaml", "replicaCount: 1\n",
                directory + "/templates/deployment.yaml", "kind: Deployment\n")));
    }

    /** A provenance file as {@code helm package --sign} writes it; the server checks name, version and digest. */
    static String provenance(String name, String version, byte[] archive) {
        return """
                -----BEGIN PGP SIGNED MESSAGE-----
                Hash: SHA512

                apiVersion: v2
                appVersion: 1.16.0
                description: A Helm chart for Kubernetes
                name: %1$s
                type: application
                version: %2$s

                ...
                files:
                  %1$s-%2$s.tgz: sha256:%3$s
                -----BEGIN PGP SIGNATURE-----

                wsBcBAEBCgAQBQJqyeDWCRCJuSJKCyqK+AAAY4cIAG7GAKgZAGCAE5ZO9fwoLkN4
                =x+rj
                -----END PGP SIGNATURE-----
                """.formatted(name, version, sha256(archive));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] tar(Map<String, String> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(file -> {
            byte[] data = file.getValue().getBytes(StandardCharsets.UTF_8);
            out.writeBytes(header(file.getKey(), data.length));
            out.writeBytes(data);
            out.writeBytes(new byte[(512 - data.length % 512) % 512]);
        });
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    private static byte[] header(String name, long size) {
        byte[] h = new byte[512];
        put(h, 0, name);
        put(h, 100, "0000644\0");
        put(h, 108, "0000000\0");
        put(h, 116, "0000000\0");
        put(h, 124, String.format("%011o", size) + "\0");
        put(h, 136, String.format("%011o", 1_700_000_000L) + "\0");
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        h[156] = '0';
        put(h, 257, "ustar\0");
        put(h, 263, "00");
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, String.format("%06o", sum) + "\0 ");
        return h;
    }

    private static void put(byte[] header, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    // --- upstream indexes ------------------------------------------------------------------------

    /** One {@code index.yaml} entry of an upstream chart repository. */
    record UpstreamChart(String name, String version, String url, byte[] archive) {
    }

    static UpstreamChart upstreamChart(String name, String version, String url, byte[] archive) {
        return new UpstreamChart(name, version, url, archive);
    }

    /** An upstream {@code index.yaml} in the shape {@code helm repo index} writes. */
    static String upstreamIndex(UpstreamChart... charts) {
        StringBuilder yaml = new StringBuilder("apiVersion: v1\nentries:\n");
        Map<String, List<UpstreamChart>> byName = new java.util.TreeMap<>();
        for (UpstreamChart chart : charts) {
            byName.computeIfAbsent(chart.name(), k -> new java.util.ArrayList<>()).add(chart);
        }
        byName.forEach((name, versions) -> {
            yaml.append("  ").append(name).append(":\n");
            for (UpstreamChart chart : versions) {
                yaml.append("  - apiVersion: v2\n")
                        .append("    appVersion: 1.16.0\n")
                        .append("    created: \"2026-09-01T10:00:00.000000000Z\"\n")
                        .append("    description: A Helm chart for Kubernetes\n")
                        .append("    digest: ").append(sha256(chart.archive())).append('\n')
                        .append("    name: ").append(name).append('\n')
                        .append("    type: application\n")
                        .append("    urls:\n")
                        .append("    - ").append(chart.url()).append('\n')
                        .append("    version: ").append(chart.version()).append('\n');
            }
        });
        yaml.append("generated: \"2026-09-01T10:00:00.000000000Z\"\n");
        return yaml.toString();
    }

    // --- requests --------------------------------------------------------------------------------

    /** {@code curl --data-binary @chart.tgz <repo>/api/charts}. */
    static MockHttpServletRequestBuilder upload(String repositoryPath, String authorization, byte[] chart) {
        MockHttpServletRequestBuilder request = post(repositoryPath + "/api/charts")
                .contentType("application/octet-stream").content(chart);
        return authorization == null ? request : request.header("Authorization", authorization);
    }

    /** {@code helm cm-push} and {@code curl -F chart=@… -F prov=@…}: a multipart form. */
    static MockMultipartHttpServletRequestBuilder multipartUpload(String repositoryPath, String authorization, byte[] chart,
                                                                  String prov) {
        MockMultipartHttpServletRequestBuilder request = multipart(repositoryPath + "/api/charts");
        if (chart != null) {
            request.file(new MockMultipartFile("chart", "chart.tgz", "application/gzip", chart));
        }
        if (prov != null) {
            request.file(new MockMultipartFile("prov", "chart.tgz.prov", "application/pgp-signature",
                    prov.getBytes(StandardCharsets.UTF_8)));
        }
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return request;
    }

    /** What helm sends for {@code helm repo add} / {@code helm repo update}. */
    static MockHttpServletRequestBuilder index(String repositoryPath) {
        return get(repositoryPath + "/index.yaml").header("User-Agent", "Helm/3.19.0");
    }

    // --- responses -------------------------------------------------------------------------------

    /** The parsed {@code index.yaml} of a response. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> yaml(MvcResult result) {
        return (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(new String(
                result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    /** The entries of one chart in an index, newest first. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> versions(Map<String, Object> index, String chart) {
        Map<String, Object> entries = (Map<String, Object>) index.get("entries");
        Object versions = entries == null ? null : entries.get(chart);
        return versions == null ? List.of() : (List<Map<String, Object>>) versions;
    }

    static List<String> versionNumbers(Map<String, Object> index, String chart) {
        return versions(index, chart).stream().map(v -> String.valueOf(v.get("version"))).toList();
    }

    @SuppressWarnings("unchecked")
    static String url(Map<String, Object> entry) {
        return ((List<String>) entry.get("urls")).getFirst();
    }
}
