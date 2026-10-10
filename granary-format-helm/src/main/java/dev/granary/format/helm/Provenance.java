package dev.granary.format.helm;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A chart provenance file ({@code <chart>.tgz.prov}): an OpenPGP clear-signed message whose body is the chart's
 * {@code Chart.yaml}, a {@code ...} separator and a {@code files:} map of archive name to {@code sha256:<hex>}.
 * Granary does not verify signatures (that is {@code helm verify}'s job, with the user's keyring); it reads the
 * chart name and version to know where the file belongs, and the archive digest to refuse a provenance file
 * that belongs to a different archive.
 *
 * @param name    chart name from the signed metadata
 * @param version chart version from the signed metadata
 * @param files   archive file name to {@code sha256:<hex>} digest
 */
public record Provenance(String name, String version, Map<String, String> files) {

    public static final String CONTENT_TYPE = "application/pgp-signature";
    private static final String BEGIN = "-----BEGIN PGP SIGNED MESSAGE-----";
    private static final String SIGNATURE = "-----BEGIN PGP SIGNATURE-----";

    public Provenance {
        files = Map.copyOf(files);
    }

    public static Provenance parse(byte[] bytes) {
        if (bytes.length == 0) {
            throw invalid("the provenance file is empty");
        }
        if (bytes.length > HelmYaml.SMALL_DOCUMENT_LIMIT) {
            throw invalid("the provenance file is too large");
        }
        String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int begin = text.indexOf(BEGIN);
        int signature = text.indexOf(SIGNATURE);
        if (begin < 0 || signature < 0 || signature < begin) {
            throw invalid("not a clear-signed provenance file");
        }
        String[] lines = text.substring(begin + BEGIN.length(), signature).split("\n", -1);
        int i = 1;
        while (i < lines.length && !lines[i].isBlank()) {
            i++; // armor headers such as "Hash: SHA512"
        }
        StringBuilder metadata = new StringBuilder();
        StringBuilder files = new StringBuilder();
        boolean inFiles = false;
        for (i = i + 1; i < lines.length; i++) {
            String line = lines[i].startsWith("- ") ? lines[i].substring(2) : lines[i]; // RFC 4880 dash escaping
            if (!inFiles && line.equals("...")) {
                inFiles = true;
                continue;
            }
            (inFiles ? files : metadata).append(line).append('\n');
        }
        Map<String, Object> chart;
        Map<String, Object> sums;
        try {
            chart = HelmYaml.loadMap(metadata.toString().getBytes(StandardCharsets.UTF_8), HelmYaml.SMALL_DOCUMENT_LIMIT);
            sums = HelmYaml.loadMap(files.toString().getBytes(StandardCharsets.UTF_8), HelmYaml.SMALL_DOCUMENT_LIMIT);
        } catch (HelmYaml.InvalidYamlException e) {
            throw invalid("the signed provenance message is not valid YAML: " + e.getMessage());
        }
        Object name = chart.get("name");
        Object version = chart.get("version");
        if (!(name instanceof String n) || !HelmPath.isValidName(n)) {
            throw invalid("the provenance file names no valid chart");
        }
        if (!(version instanceof String v) || !HelmVersion.isValid(v)) {
            throw invalid("the provenance file names no valid chart version");
        }
        Map<String, String> digests = new LinkedHashMap<>();
        if (sums.get("files") instanceof Map<?, ?> map) {
            map.forEach((k, val) -> {
                if (val != null) {
                    digests.put(String.valueOf(k), val.toString());
                }
            });
        }
        return new Provenance(n, v, digests);
    }

    /** The sha256 digest (hex) the provenance file states for {@code archiveName}, if any. */
    public Optional<String> sha256Of(String archiveName) {
        String value = files.get(archiveName);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(value.startsWith("sha256:") ? value.substring("sha256:".length()) : value);
    }

    private static ChartArchive.InvalidChartException invalid(String message) {
        return new ChartArchive.InvalidChartException(message);
    }
}
