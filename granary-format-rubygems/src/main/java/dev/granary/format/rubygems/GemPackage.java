package dev.granary.format.rubygems;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Reads a {@code .gem}: a plain (uncompressed) tar holding {@code metadata.gz} (the YAML gemspec, gzipped),
 * {@code data.tar.gz} (the files) and usually {@code checksums.yaml.gz}. The checksums, when present, are verified
 * against the other two entries so a corrupted upload is refused instead of being served.
 */
public final class GemPackage {

    static final String METADATA = "metadata.gz";
    static final String DATA = "data.tar.gz";
    static final String CHECKSUMS = "checksums.yaml.gz";

    /** Bounds for what is held in memory; real gemspecs are a few kilobytes. */
    static final int MAX_METADATA_BYTES = 16 * 1024 * 1024;
    static final int MAX_METADATA_YAML_BYTES = 32 * 1024 * 1024;

    private GemPackage() {
    }

    /**
     * What a gem contains.
     *
     * @param spec      the parsed gemspec
     * @param yaml      the gemspec as YAML text
     * @param entries   the names of the tar entries, in order
     */
    public record Contents(GemSpec spec, String yaml, List<String> entries) {
    }

    /** Reads and validates a gem; {@link InvalidGemException} when it is not one. */
    public static Contents read(InputStream gem) throws IOException {
        byte[] metadata = null;
        Map<String, String> sha256 = new LinkedHashMap<>();
        Map<String, String> sha512 = new LinkedHashMap<>();
        byte[] checksums = null;
        List<String> names = new java.util.ArrayList<>();
        String longName = null;
        int entries = 0;
        while (true) {
            byte[] header = gem.readNBytes(512);
            if (header.length == 0) {
                break;
            }
            if (header.length < 512) {
                throw new InvalidGemException("The gem is truncated (incomplete tar header)");
            }
            if (isZeroBlock(header)) {
                break;
            }
            if (++entries > 10_000) {
                throw new InvalidGemException("The gem has too many entries");
            }
            verifyHeaderChecksum(header, entries);
            String name = longName != null ? longName : entryName(header);
            longName = null;
            long size = octal(header, 124, 12);
            char type = (char) header[156];
            if (size < 0) {
                throw new InvalidGemException("The gem has a tar entry with an invalid size");
            }
            long padded = (size + 511) / 512 * 512;
            if (type == 'L') {
                if (size > 4096) {
                    throw new InvalidGemException("The gem has an oversized long file name");
                }
                byte[] raw = readFully(gem, (int) size);
                skipFully(gem, padded - size);
                longName = cString(raw, 0, raw.length);
                continue;
            }
            if (type == 'x' || type == 'g') {
                skipFully(gem, padded);
                continue;
            }
            names.add(name);
            switch (name) {
                case METADATA -> {
                    if (size > MAX_METADATA_BYTES) {
                        throw new InvalidGemException("The gem's metadata.gz is too large");
                    }
                    metadata = readFully(gem, (int) size);
                    skipFully(gem, padded - size);
                    sha256.put(METADATA, hex("SHA-256", metadata));
                    sha512.put(METADATA, hex("SHA-512", metadata));
                }
                case CHECKSUMS -> {
                    if (size > MAX_METADATA_BYTES) {
                        throw new InvalidGemException("The gem's checksums.yaml.gz is too large");
                    }
                    checksums = readFully(gem, (int) size);
                    skipFully(gem, padded - size);
                }
                case DATA -> {
                    MessageDigest d256 = digest("SHA-256");
                    MessageDigest d512 = digest("SHA-512");
                    copyDigesting(gem, size, d256, d512);
                    skipFully(gem, padded - size);
                    sha256.put(DATA, HexFormat.of().formatHex(d256.digest()));
                    sha512.put(DATA, HexFormat.of().formatHex(d512.digest()));
                }
                default -> skipFully(gem, padded);
            }
        }
        if (metadata == null) {
            throw new InvalidGemException(names.isEmpty()
                    ? "The upload is not a gem (no tar entries)"
                    : "The gem has no metadata.gz (entries: " + String.join(", ", names) + ")");
        }
        if (!names.contains(DATA)) {
            throw new InvalidGemException("The gem has no data.tar.gz");
        }
        if (checksums != null) {
            verifyChecksums(gunzipText(checksums, "checksums.yaml.gz"), sha256, sha512);
        }
        String yaml = gunzipText(metadata, METADATA);
        return new Contents(GemspecYaml.parse(yaml), yaml, List.copyOf(names));
    }

    /** Checks the {@code SHA256} (or {@code SHA512}) section of {@code checksums.yaml} against the entries. */
    static void verifyChecksums(String yaml, Map<String, String> sha256, Map<String, String> sha512) {
        Map<String, Map<String, String>> sections = ChecksumsYaml.parse(yaml);
        Map<String, String> expected = sections.getOrDefault("SHA256", Map.of());
        Map<String, String> actual = sha256;
        if (expected.isEmpty()) {
            expected = sections.getOrDefault("SHA512", Map.of());
            actual = sha512;
        }
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String have = actual.get(e.getKey());
            if (have != null && !have.equalsIgnoreCase(e.getValue().strip())) {
                throw new InvalidGemException("The checksum of " + e.getKey() + " does not match checksums.yaml.gz; "
                        + "the gem is corrupt");
            }
        }
    }

    private static void verifyHeaderChecksum(byte[] header, int entry) {
        long stored = octal(header, 148, 8);
        long unsigned = 0;
        long signed = 0;
        for (int i = 0; i < 512; i++) {
            int b = i >= 148 && i < 156 ? ' ' : header[i];
            unsigned += b & 0xff;
            signed += b;
        }
        if (stored < 0 || (stored != unsigned && stored != signed)) {
            throw new InvalidGemException(entry == 1
                    ? "The upload is not a gem (a gem is a tar archive)"
                    : "The gem has a corrupt tar header");
        }
    }

    private static String entryName(byte[] header) {
        String name = cString(header, 0, 100);
        boolean ustar = new String(header, 257, 5, StandardCharsets.US_ASCII).equals("ustar");
        if (ustar) {
            String prefix = cString(header, 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
        }
        return name.startsWith("./") ? name.substring(2) : name;
    }

    private static String cString(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    /** A tar number: octal digits, or GNU base-256 when the high bit is set. Negative when unreadable. */
    static long octal(byte[] header, int offset, int length) {
        if ((header[offset] & 0x80) != 0) {
            long value = 0;
            for (int i = offset + 1; i < offset + length; i++) {
                value = (value << 8) | (header[i] & 0xff);
                if (value < 0) {
                    return -1;
                }
            }
            return value;
        }
        long value = 0;
        boolean digits = false;
        for (int i = offset; i < offset + length; i++) {
            byte b = header[i];
            if (b == 0 || (b == ' ' && digits)) {
                break;
            }
            if (b == ' ') {
                continue;
            }
            if (b < '0' || b > '7') {
                return -1;
            }
            digits = true;
            value = value * 8 + (b - '0');
        }
        return digits ? value : 0;
    }

    private static boolean isZeroBlock(byte[] header) {
        for (byte b : header) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readFully(InputStream in, int size) throws IOException {
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) {
            throw new InvalidGemException("The gem is truncated");
        }
        return bytes;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new InvalidGemException("The gem is truncated");
            }
            remaining -= read;
        }
    }

    private static void copyDigesting(InputStream in, long size, MessageDigest... digests) throws IOException {
        long remaining = size;
        byte[] buffer = new byte[64 * 1024];
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new InvalidGemException("The gem is truncated");
            }
            for (MessageDigest d : digests) {
                d.update(buffer, 0, read);
            }
            remaining -= read;
        }
    }

    static String gunzipText(byte[] gz, String what) {
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_METADATA_YAML_BYTES) {
                    throw new InvalidGemException("The gem's " + what + " is too large");
                }
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (ZipException | java.io.EOFException e) {
            throw new InvalidGemException("The gem's " + what + " is not valid gzip data", e);
        } catch (IOException e) {
            throw new InvalidGemException("The gem's " + what + " cannot be read: " + e.getMessage(), e);
        }
    }

    static String hex(String algorithm, byte[] data) {
        return HexFormat.of().formatHex(digest(algorithm).digest(data));
    }

    static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 of a stream, as lower-case hex. */
    public static String sha256(InputStream in) throws IOException {
        MessageDigest d = digest("SHA-256");
        try (DigestInputStream digesting = new DigestInputStream(in, d)) {
            digesting.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(d.digest());
    }
}
