package dev.granary.format.terraform;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The conventional name of a provider archive, {@code terraform-provider-<type>_<version>_<os>_<arch>.zip},
 * as HashiCorp's release tooling and {@code goreleaser} produce it. The registry protocols identify a
 * platform by the {@code os}/{@code arch} in the request path and never by the file name, but when only a
 * file name is known (a direct {@code files/…} request on a proxy before any download document was cached)
 * the convention tells which platform's document to fetch.
 */
public record ProviderFilename(String type, String version, String os, String arch) {

    private static final Pattern CONVENTIONAL =
            Pattern.compile("^terraform-provider-(.+)_(v?\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?)_([a-z0-9]+)_([a-z0-9]+)\\.zip$");

    public static Optional<ProviderFilename> parse(String filename) {
        if (filename == null) {
            return Optional.empty();
        }
        Matcher m = CONVENTIONAL.matcher(filename);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ProviderFilename(m.group(1), m.group(2), m.group(3), m.group(4)));
    }

    public static String of(String type, String version, String os, String arch) {
        return "terraform-provider-" + type + "_" + version + "_" + os + "_" + arch + ".zip";
    }

    /** {@code *SHA256SUMS}: the checksum document, whatever prefix the release tooling gave it. */
    public static boolean isShaSums(String filename) {
        return filename != null && filename.endsWith(TerraformPath.SHASUMS);
    }

    /** {@code *SHA256SUMS.sig}: the detached signature of the checksum document. */
    public static boolean isSignature(String filename) {
        return filename != null && filename.endsWith(TerraformPath.SHASUMS_SIG);
    }

    public String platformKey() {
        return os + "_" + arch;
    }
}
