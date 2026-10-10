package dev.granary.format.pub;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Reads what the server needs from a package archive: a gzip-compressed tar file (what {@code dart pub
 * publish} uploads) with {@code pubspec.yaml} at its root. Every entry is walked so that archives the pub
 * client would refuse to extract, or that would write outside the package directory, are rejected at
 * upload time rather than discovered by the first consumer.
 *
 * <p>Understands POSIX ustar, GNU long names ({@code L}/{@code K}), pax extended headers ({@code x}/{@code g})
 * and base-256 sizes. Header checksums are verified. Limits keep a hostile archive from exhausting memory or
 * CPU: the uncompressed size, the number of entries and the size of {@code pubspec.yaml} are bounded.
 *
 * @param pubspec          the bytes of the root {@code pubspec.yaml}
 * @param entries          how many entries the archive has
 * @param uncompressedSize the total size of the tar stream
 */
public record PubArchive(byte[] pubspec, int entries, long uncompressedSize) {

    /** Thrown for anything that makes the archive unacceptable; the message is shown to the publisher. */
    public static final class InvalidArchiveException extends Exception {
        public InvalidArchiveException(String message) {
            super(message);
        }
    }

    /** Bounds applied while reading. */
    public record Limits(long maxUncompressedBytes, int maxEntries, int maxPubspecBytes) {
        public static final Limits DEFAULT = new Limits(1024L * 1024 * 1024, 100_000, 1024 * 1024);
    }

    private static final int BLOCK = 512;

    public static PubArchive read(InputStream gzip) throws IOException, InvalidArchiveException {
        return read(gzip, Limits.DEFAULT);
    }

    public static PubArchive read(InputStream gzip, Limits limits) throws IOException, InvalidArchiveException {
        InputStream tar;
        try {
            tar = new GZIPInputStream(gzip, 64 * 1024);
        } catch (ZipException | EOFException e) {
            throw new InvalidArchiveException("The package archive is not gzip-compressed (expected a .tar.gz file)");
        }
        try {
            return walk(new CountingStream(tar, limits.maxUncompressedBytes()), limits);
        } catch (LimitExceeded e) {
            throw new InvalidArchiveException(e.getMessage());
        } catch (ZipException | EOFException e) {
            throw new InvalidArchiveException("The package archive is truncated or corrupt: " + e.getMessage());
        }
    }

    private static PubArchive walk(CountingStream in, Limits limits) throws IOException, InvalidArchiveException {
        byte[] pubspec = null;
        int entries = 0;
        String longName = null;
        String longLink = null;
        String paxPath = null;
        String paxLink = null;
        byte[] header = new byte[BLOCK];
        while (true) {
            if (!readBlock(in, header)) {
                break; // a missing end-of-archive marker is tolerated, as tar implementations do
            }
            if (isZero(header)) {
                break;
            }
            verifyChecksum(header);
            char type = (char) (header[156] & 0xff);
            long size = size(header);
            if (size < 0) {
                throw new InvalidArchiveException("The package archive has an entry with a negative size");
            }
            switch (type) {
                case 'L' -> {
                    longName = cString(readSmall(in, size, 64 * 1024, "long file name"));
                    continue;
                }
                case 'K' -> {
                    longLink = cString(readSmall(in, size, 64 * 1024, "long link name"));
                    continue;
                }
                case 'x' -> {
                    byte[] records = readSmall(in, size, 1024 * 1024, "pax header");
                    String path = paxRecord(records, "path");
                    if (path != null) {
                        paxPath = path;
                    }
                    String link = paxRecord(records, "linkpath");
                    if (link != null) {
                        paxLink = link;
                    }
                    continue;
                }
                case 'g' -> {
                    skip(in, size);
                    continue;
                }
                default -> {
                    // a real entry
                }
            }
            String name = paxPath != null ? paxPath : longName != null ? longName : headerName(header);
            // a link target longer than 100 bytes is only complete in a GNU K entry or a pax linkpath record
            String linkTarget = paxLink != null ? paxLink : longLink != null ? longLink : cString(slice(header, 157, 100));
            longName = null;
            longLink = null;
            paxPath = null;
            paxLink = null;
            if (++entries > limits.maxEntries()) {
                throw new InvalidArchiveException("The package archive has more than " + limits.maxEntries() + " entries");
            }
            String normalised = normalise(name);
            boolean regular = type == '0' || type == '\0' || type == '7';
            if (regular && normalised.equals("pubspec.yaml")) {
                if (pubspec != null) {
                    throw new InvalidArchiveException("The package archive contains pubspec.yaml twice");
                }
                pubspec = readSmall(in, size, limits.maxPubspecBytes(), "pubspec.yaml");
                continue;
            }
            if (type == '1' || type == '2') {
                checkLinkTarget(normalised, linkTarget, type == '1');
            }
            // directories, links and devices carry no data; their size field is ignored by tar readers
            if (!isDataless(type)) {
                skip(in, size);
            }
        }
        if (pubspec == null) {
            throw new InvalidArchiveException("The package archive has no pubspec.yaml at its root");
        }
        return new PubArchive(pubspec, entries, in.count());
    }

    private static boolean isDataless(char type) {
        return type == '1' || type == '2' || type == '3' || type == '4' || type == '5' || type == '6';
    }

    /**
     * The entry name relative to the package root, without {@code ./} and trailing slashes. Absolute names
     * and names with {@code ..} segments would escape the directory the client extracts into: refused.
     */
    static String normalise(String name) throws InvalidArchiveException {
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.matches("^[A-Za-z]:.*")) {
            throw new InvalidArchiveException("The package archive contains an absolute path: " + printable(name));
        }
        StringBuilder out = new StringBuilder();
        for (String segment : n.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new InvalidArchiveException("The package archive contains a path outside the package: "
                        + printable(name));
            }
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(segment);
        }
        return out.toString();
    }

    /**
     * Links may point anywhere inside the package, never outside of it. A symbolic link is resolved from the
     * directory of its entry, a hard link from the root of the archive.
     */
    private static void checkLinkTarget(String entry, String target, boolean hard) throws InvalidArchiveException {
        String t = target.replace('\\', '/');
        if (t.startsWith("/") || t.matches("^[A-Za-z]:.*")) {
            throw new InvalidArchiveException("The package archive contains a link to an absolute path: " + printable(entry));
        }
        int depth = hard || entry.isEmpty() ? 0 : entry.split("/").length - 1;
        for (String segment : t.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            depth += segment.equals("..") ? -1 : 1;
            if (depth < 0) {
                throw new InvalidArchiveException("The package archive contains a link pointing outside the package: "
                        + printable(entry));
            }
        }
    }

    private static String printable(String s) {
        String cut = s.length() > 200 ? s.substring(0, 200) + "…" : s;
        return cut.replaceAll("[\\p{Cntrl}]", "?");
    }

    // --- header fields -----------------------------------------------------------------------------

    private static String headerName(byte[] header) {
        String name = cString(slice(header, 0, 100));
        String magic = new String(header, 257, 5, StandardCharsets.US_ASCII);
        if (magic.equals("ustar")) {
            String prefix = cString(slice(header, 345, 155));
            if (!prefix.isEmpty()) {
                return prefix + "/" + name;
            }
        }
        return name;
    }

    /** Size in octal, or base-256 when the high bit of the first byte is set (GNU and star extension). */
    static long size(byte[] header) throws InvalidArchiveException {
        if ((header[124] & 0x80) != 0) {
            if ((header[124] & 0x40) != 0) {
                return -1; // negative base-256 number
            }
            long value = header[124] & 0x3f;
            for (int i = 125; i < 136; i++) {
                if (value > (Long.MAX_VALUE >> 8)) {
                    throw new InvalidArchiveException("The package archive has an entry that is too large");
                }
                value = (value << 8) | (header[i] & 0xff);
            }
            return value;
        }
        return octal(header, 124, 12);
    }

    private static long octal(byte[] header, int offset, int length) throws InvalidArchiveException {
        long value = 0;
        int i = offset;
        int end = offset + length;
        while (i < end && (header[i] == ' ' || header[i] == 0)) {
            i++;
        }
        boolean any = false;
        for (; i < end; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') {
                break;
            }
            if (b < '0' || b > '7') {
                throw new InvalidArchiveException("The package archive has a corrupt tar header");
            }
            if (value > (Long.MAX_VALUE >> 3)) {
                throw new InvalidArchiveException("The package archive has a corrupt tar header");
            }
            value = (value << 3) + (b - '0');
            any = true;
        }
        return any ? value : 0;
    }

    private static void verifyChecksum(byte[] header) throws InvalidArchiveException {
        long expected = octal(header, 148, 8);
        long unsigned = 0;
        long signed = 0;
        for (int i = 0; i < BLOCK; i++) {
            byte b = (i >= 148 && i < 156) ? (byte) ' ' : header[i];
            unsigned += b & 0xff;
            signed += b;
        }
        if (expected != unsigned && expected != signed) {
            throw new InvalidArchiveException("The package archive is not a valid tar file (header checksum mismatch)");
        }
    }

    /** The {@code path} record of a pax extended header ({@code "<len> path=<value>\n"} records). */
    static String paxPath(byte[] data) throws InvalidArchiveException {
        return paxRecord(data, "path");
    }

    /** The value of the record {@code key} of a pax extended header ({@code "<len> <key>=<value>\n"}), or null. */
    static String paxRecord(byte[] data, String key) throws InvalidArchiveException {
        int pos = 0;
        String value = null;
        while (pos < data.length) {
            int space = pos;
            while (space < data.length && data[space] != ' ') {
                space++;
            }
            if (space >= data.length) {
                break;
            }
            int length;
            try {
                length = Integer.parseInt(new String(data, pos, space - pos, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                throw new InvalidArchiveException("The package archive has a corrupt pax header");
            }
            if (length <= space - pos || pos + length > data.length) {
                throw new InvalidArchiveException("The package archive has a corrupt pax header");
            }
            String record = new String(data, space + 1, pos + length - space - 1, StandardCharsets.UTF_8);
            if (record.endsWith("\n")) {
                record = record.substring(0, record.length() - 1);
            }
            int eq = record.indexOf('=');
            if (eq > 0 && record.substring(0, eq).equals(key)) {
                value = record.substring(eq + 1);
            }
            pos += length;
        }
        return value;
    }

    private static byte[] slice(byte[] b, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(b, offset, out, 0, length);
        return out;
    }

    private static String cString(byte[] bytes) {
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    // --- stream handling ---------------------------------------------------------------------------

    /** Reads one block; false at a clean end of stream, an exception for a partial block. */
    private static boolean readBlock(InputStream in, byte[] block) throws IOException, InvalidArchiveException {
        int read = in.readNBytes(block, 0, BLOCK);
        if (read == 0) {
            return false;
        }
        if (read < BLOCK) {
            throw new InvalidArchiveException("The package archive is truncated");
        }
        return true;
    }

    /** Reads an entry's data that is expected to be small, plus its padding. */
    private static byte[] readSmall(InputStream in, long size, int max, String what)
            throws IOException, InvalidArchiveException {
        if (size > max) {
            throw new InvalidArchiveException("The " + what + " in the package archive is larger than " + max + " bytes");
        }
        byte[] data = in.readNBytes((int) size);
        if (data.length < size) {
            throw new InvalidArchiveException("The package archive is truncated");
        }
        skipFully(in, padding(size));
        return data;
    }

    private static void skip(InputStream in, long size) throws IOException, InvalidArchiveException {
        skipFully(in, size + padding(size));
    }

    private static long padding(long size) {
        return (BLOCK - (size % BLOCK)) % BLOCK;
    }

    private static void skipFully(InputStream in, long n) throws IOException, InvalidArchiveException {
        long remaining = n;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new InvalidArchiveException("The package archive is truncated");
            }
            remaining -= read;
        }
    }

    /** Counts the uncompressed bytes and stops a decompression bomb at the configured limit. */
    private static final class CountingStream extends InputStream {
        private final InputStream in;
        private final long limit;
        private long count;

        CountingStream(InputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                count += n;
                if (count > limit) {
                    throw new LimitExceeded(limit);
                }
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Raised from inside the stream; {@link #read(InputStream, Limits)} turns it into a clean rejection. */
    static final class LimitExceeded extends IOException {
        LimitExceeded(long limit) {
            super("The package archive expands to more than " + (limit / (1024 * 1024)) + " MiB");
        }
    }
}
