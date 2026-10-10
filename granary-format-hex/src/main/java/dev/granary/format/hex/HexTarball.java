package dev.granary.format.hex;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A Hex release tarball (format version 3): a plain tar holding {@code VERSION}, {@code CHECKSUM},
 * {@code metadata.config} and {@code contents.tar.gz}. The inner checksum is SHA-256 over
 * VERSION + metadata.config + contents.tar.gz and must match the upper-case hex in {@code CHECKSUM};
 * the outer checksum is SHA-256 over the whole tarball. Both end up in the registry, so both are
 * computed here exactly as {@code hex_tarball} does.
 *
 * @param formatVersion the {@code VERSION} entry, always {@code 3}
 * @param innerChecksum raw SHA-256 of VERSION + metadata.config + contents.tar.gz
 * @param outerChecksum raw SHA-256 of the tarball bytes
 * @param metadataText  the {@code metadata.config} entry as text
 * @param metadata      the parsed metadata
 * @param contentsSize  size of {@code contents.tar.gz} in bytes
 */
public record HexTarball(String formatVersion, byte[] innerChecksum, byte[] outerChecksum, String metadataText,
                         HexMetadata metadata, long contentsSize) {

    static final String SUPPORTED_VERSION = "3";
    static final int BLOCK = 512;
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_METADATA_SIZE = 4 * 1024 * 1024;

    public static final class TarballException extends IllegalArgumentException {
        TarballException(String message) {
            super(message);
        }
    }

    public static HexTarball read(byte[] tarball) {
        Map<String, byte[]> files = entries(tarball);
        byte[] version = required(files, "VERSION");
        byte[] checksum = required(files, "CHECKSUM");
        byte[] metadataBytes = required(files, "metadata.config");
        byte[] contents = required(files, "contents.tar.gz");
        String formatVersion = new String(version, StandardCharsets.US_ASCII);
        if (!SUPPORTED_VERSION.equals(formatVersion)) {
            throw new TarballException("unsupported tarball version '" + formatVersion.strip() + "'");
        }
        if (metadataBytes.length > MAX_METADATA_SIZE) {
            throw new TarballException("metadata.config is too large");
        }
        byte[] inner = innerChecksum(version, metadataBytes, contents);
        String expected = new String(checksum, StandardCharsets.US_ASCII).strip().toLowerCase(Locale.ROOT);
        if (expected.length() != 64 || !expected.equals(HexFormat.of().formatHex(inner))) {
            throw new TarballException("tarball checksum mismatch: CHECKSUM does not match the contents");
        }
        HexMetadata metadata;
        try {
            metadata = HexMetadata.parse(ErlangTerms.decodeBinary(metadataBytes));
        } catch (HexMetadata.MetadataException e) {
            throw new TarballException(e.getMessage());
        }
        return new HexTarball(formatVersion, inner, sha256(tarball), ErlangTerms.decodeBinary(metadataBytes), metadata,
                contents.length);
    }

    public String innerChecksumHex() {
        return HexFormat.of().formatHex(innerChecksum);
    }

    public String outerChecksumHex() {
        return HexFormat.of().formatHex(outerChecksum);
    }

    static byte[] innerChecksum(byte[] version, byte[] metadata, byte[] contents) {
        MessageDigest digest = sha256Digest();
        digest.update(version);
        digest.update(metadata);
        digest.update(contents);
        return digest.digest();
    }

    static byte[] sha256(byte[] data) {
        return sha256Digest().digest(data);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] required(Map<String, byte[]> files, String name) {
        byte[] data = files.get(name);
        if (data == null) {
            throw new TarballException("tarball is missing " + name);
        }
        return data;
    }

    /** The regular files of a ustar/GNU/pax tar, by name; directories and links are skipped. */
    static Map<String, byte[]> entries(byte[] tar) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        int pos = 0;
        int count = 0;
        String longName = null;
        String paxPath = null;
        while (pos + BLOCK <= tar.length) {
            if (isZeroBlock(tar, pos)) {
                break;
            }
            if (++count > MAX_ENTRIES) {
                throw new TarballException("tarball has too many entries");
            }
            String name = field(tar, pos, 100);
            long size = size(tar, pos + 124, 12);
            char type = (char) (tar[pos + 156] & 0xFF);
            String magic = field(tar, pos + 257, 6);
            String prefix = magic.startsWith("ustar") ? field(tar, pos + 345, 155) : "";
            int dataStart = pos + BLOCK;
            if (size < 0 || size > tar.length - dataStart) {
                throw new TarballException("tarball entry '" + name + "' is truncated");
            }
            int dataEnd = dataStart + (int) size;
            pos = dataStart + (int) ((size + BLOCK - 1) / BLOCK * BLOCK);
            switch (type) {
                case 'L' -> longName = stripNul(new String(tar, dataStart, (int) size, StandardCharsets.UTF_8));
                case 'x' -> paxPath = paxPath(new String(tar, dataStart, (int) size, StandardCharsets.UTF_8));
                case 'g', 'K' -> { } // global pax header, GNU long link: nothing to keep
                case '0', '\0', '7' -> {
                    String entryName = longName != null ? longName : paxPath != null ? paxPath
                            : prefix.isEmpty() ? name : prefix + "/" + name;
                    longName = null;
                    paxPath = null;
                    entryName = normalise(entryName);
                    byte[] data = new byte[(int) size];
                    System.arraycopy(tar, dataStart, data, 0, (int) size);
                    if (files.containsKey(entryName)) {
                        throw new TarballException("duplicate entry '" + entryName + "' in tarball");
                    }
                    files.put(entryName, data);
                }
                default -> {
                    longName = null;
                    paxPath = null;
                }
            }
            if (dataEnd > tar.length) {
                throw new TarballException("tarball is truncated");
            }
        }
        if (files.isEmpty()) {
            throw new TarballException("not a tar archive");
        }
        return files;
    }

    private static String normalise(String name) {
        String n = name;
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        return n;
    }

    private static String paxPath(String records) {
        String path = null;
        int i = 0;
        while (i < records.length()) {
            int space = records.indexOf(' ', i);
            if (space < 0) {
                break;
            }
            int length;
            try {
                length = Integer.parseInt(records.substring(i, space));
            } catch (NumberFormatException e) {
                break;
            }
            if (length <= 0 || i + length > records.length()) {
                break;
            }
            String record = records.substring(space + 1, i + length);
            if (record.startsWith("path=")) {
                path = record.substring(5).stripTrailing();
            }
            i += length;
        }
        return path;
    }

    private static boolean isZeroBlock(byte[] tar, int pos) {
        for (int i = 0; i < BLOCK; i++) {
            if (tar[pos + i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static String field(byte[] tar, int offset, int length) {
        return stripNul(new String(tar, offset, length, StandardCharsets.ISO_8859_1));
    }

    private static String stripNul(String s) {
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }

    /** Octal sizes, or GNU base-256 for files over 8 GiB (not that a Hex package should be). */
    private static long size(byte[] tar, int offset, int length) {
        if ((tar[offset] & 0x80) != 0) {
            long value = 0;
            for (int i = 1; i < length; i++) {
                value = (value << 8) | (tar[offset + i] & 0xFF);
            }
            return value;
        }
        String text = field(tar, offset, length).trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException e) {
            throw new TarballException("malformed size in tar header");
        }
    }
}
