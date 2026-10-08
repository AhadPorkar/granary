package dev.granary.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Hex digests of stored content, for the {@code .sha1}/{@code .md5}/… sidecar files package managers ask for. */
public final class Checksums {

    private static final Map<String, String> ALGORITHMS = Map.of(
            "md5", "MD5",
            "sha1", "SHA-1",
            "sha256", "SHA-256",
            "sha512", "SHA-512");

    private Checksums() {
    }

    /** Maps a file extension such as {@code sha1} to the JCA algorithm name, if it is a known checksum extension. */
    public static Optional<String> algorithmFor(String extension) {
        return Optional.ofNullable(extension == null ? null : ALGORITHMS.get(extension.toLowerCase(Locale.ROOT)));
    }

    public static boolean isChecksumExtension(String extension) {
        return extension != null && ALGORITHMS.containsKey(extension.toLowerCase(Locale.ROOT));
    }

    public static String hex(String algorithm, InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("unknown digest " + algorithm, e);
        }
    }

    public static String hex(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("unknown digest " + algorithm, e);
        }
    }

    public static String of(StoredContent content, String algorithm) throws IOException {
        if ("SHA-256".equals(algorithm)) {
            return content.asset().getBlobSha256();
        }
        try (InputStream in = content.open()) {
            return hex(algorithm, in);
        }
    }
}
