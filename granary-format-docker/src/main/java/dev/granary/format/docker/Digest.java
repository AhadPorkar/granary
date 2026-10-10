package dev.granary.format.docker;

import dev.granary.core.storage.Checksums;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A content digest as the distribution protocol writes it: {@code sha256:<64 lower-case hex characters>}.
 *
 * <p>Only SHA-256 is accepted. It is the one algorithm every client in use emits, and it is also the digest
 * the blob store computes for every file, which lets a stored manifest or blob be verified against its
 * claimed digest without reading it twice.
 */
public record Digest(String algorithm, String hex) {

    public static final String SHA256 = "sha256";

    private static final Pattern DIGEST = Pattern.compile("^sha256:([a-f0-9]{64})$");

    public Digest {
        if (!SHA256.equals(algorithm) || hex == null || hex.length() != 64) {
            throw new IllegalArgumentException("unsupported digest " + algorithm + ":" + hex);
        }
    }

    /** Parses a digest string; anything but a well-formed lower-case SHA-256 digest is rejected. */
    public static Optional<Digest> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        var matcher = DIGEST.matcher(value.trim());
        return matcher.matches() ? Optional.of(new Digest(SHA256, matcher.group(1))) : Optional.empty();
    }

    public static boolean isDigest(String value) {
        return parse(value).isPresent();
    }

    /** Wraps a hex digest the blob store already computed. */
    public static Digest sha256(String hex) {
        return new Digest(SHA256, hex.toLowerCase(Locale.ROOT));
    }

    public static Digest of(byte[] bytes) {
        return sha256(Checksums.hex("SHA-256", bytes));
    }

    public static Digest of(InputStream in) throws IOException {
        return sha256(Checksums.hex("SHA-256", in));
    }

    @Override
    public String toString() {
        return algorithm + ":" + hex;
    }
}
