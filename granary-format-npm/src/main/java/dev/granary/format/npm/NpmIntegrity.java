package dev.granary.format.npm;

import dev.granary.core.storage.Checksums;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * The two digests npm records for every tarball: the legacy {@code shasum} (SHA-1 hex) and the
 * Subresource-Integrity string {@code sha512-<base64>} that modern clients verify downloads against.
 * Computing them server side means a publish document missing or lying about them is corrected, so
 * clients never fail an integrity check on a tarball this registry handed out.
 */
public final class NpmIntegrity {

    private NpmIntegrity() {
    }

    public static String shasum(byte[] tarball) {
        return Checksums.hex("SHA-1", tarball);
    }

    public static String integrity(byte[] tarball) {
        return "sha512-" + Base64.getEncoder().encodeToString(digest("SHA-512", tarball));
    }

    /**
     * Verifies an SRI string the client sent against the bytes. Only the first entry is checked; an
     * algorithm this server does not know is ignored rather than rejected, because SRI explicitly allows
     * clients to send what they like and expects the receiver to pick what it understands.
     */
    public static boolean matches(String sri, byte[] tarball) {
        Optional<String[]> parsed = parse(sri);
        if (parsed.isEmpty()) {
            return true;
        }
        String algorithm = switch (parsed.get()[0].toLowerCase(Locale.ROOT)) {
            case "sha512" -> "SHA-512";
            case "sha384" -> "SHA-384";
            case "sha256" -> "SHA-256";
            case "sha1" -> "SHA-1";
            default -> null;
        };
        if (algorithm == null) {
            return true;
        }
        byte[] expected;
        try {
            expected = Base64.getDecoder().decode(parsed.get()[1]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, digest(algorithm, tarball));
    }

    /** Splits {@code sha512-abc...} into algorithm and base64 digest, dropping any {@code ?options}. */
    private static Optional<String[]> parse(String sri) {
        if (sri == null || sri.isBlank()) {
            return Optional.empty();
        }
        String first = sri.trim().split("\\s+")[0];
        int dash = first.indexOf('-');
        if (dash <= 0 || dash == first.length() - 1) {
            return Optional.empty();
        }
        String digest = first.substring(dash + 1);
        int options = digest.indexOf('?');
        if (options >= 0) {
            digest = digest.substring(0, options);
        }
        return Optional.of(new String[] {first.substring(0, dash), digest});
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is mandatory in every JDK", e);
        }
    }
}
