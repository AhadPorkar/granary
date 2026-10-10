package dev.granary.format.raw;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A forward-only reader for the tar archives source distributions come in (ustar, GNU long names, pax
 * extended headers). It reads one entry at a time from the stream and never buffers an archive, so the
 * size of a tarball does not matter; only the entries the caller asks for are read into memory.
 */
public final class TarReader implements Closeable {

    private static final int BLOCK = 512;
    /** GNU long names and pax headers are a few hundred bytes; anything this large is a malformed or hostile archive. */
    static final long MAX_HEADER_DATA = 1024 * 1024;

    /** One archive member. {@code type} is the raw typeflag byte. */
    public record Entry(String name, long size, char type) {

        public boolean isFile() {
            return type == '0' || type == '\0' || type == '7';
        }

        public boolean isDirectory() {
            return type == '5';
        }

        /** The name without the leading top-level directory, e.g. {@code pkg/DESCRIPTION} becomes {@code DESCRIPTION}. */
        public String nameBelowRoot() {
            int slash = name.indexOf('/');
            return slash < 0 ? "" : name.substring(slash + 1);
        }

        public String fileName() {
            return RawPaths.fileName(RawPaths.withoutTrailingSlash(name));
        }
    }

    private final InputStream in;
    private long remaining;
    private long padding;

    public TarReader(InputStream in) {
        this.in = in;
    }

    public static TarReader gzip(InputStream in) throws IOException {
        return new TarReader(new GZIPInputStream(in));
    }

    /** Advances to the next entry, skipping whatever of the current one was not read. {@code null} at the end. */
    public Entry next() throws IOException {
        skipCurrent();
        String longName = null;
        String paxPath = null;
        Long paxSize = null;
        while (true) {
            byte[] header = in.readNBytes(BLOCK);
            if (header.length < BLOCK || allZero(header)) {
                return null;
            }
            char type = (char) (header[156] & 0xff);
            long size = parseSize(header);
            long pad = (BLOCK - (size % BLOCK)) % BLOCK;
            switch (type) {
                case 'L' -> {
                    longName = trimNul(new String(readExactly(headerData(size)), StandardCharsets.UTF_8));
                    in.skipNBytes(pad);
                }
                case 'K', 'g' -> in.skipNBytes(size + pad);
                case 'x' -> {
                    Map<String, String> pax = parsePax(readExactly(headerData(size)));
                    in.skipNBytes(pad);
                    if (pax.containsKey("path")) {
                        paxPath = pax.get("path");
                    }
                    if (pax.containsKey("size")) {
                        try {
                            long value = Long.parseLong(pax.get("size").trim());
                            if (value >= 0) {
                                paxSize = value;
                            }
                        } catch (NumberFormatException ignored) {
                            // keep the header size
                        }
                    }
                }
                default -> {
                    String name = paxPath != null ? paxPath : longName != null ? longName : ustarName(header);
                    long actual = paxSize != null ? paxSize : size;
                    remaining = actual;
                    padding = (BLOCK - (actual % BLOCK)) % BLOCK;
                    return new Entry(name, actual, type);
                }
            }
        }
    }

    /** The size of a long-name or pax header entry, refused when it could only exhaust memory. */
    private static long headerData(long size) throws IOException {
        if (size > MAX_HEADER_DATA) {
            throw new IOException("malformed tar archive: extended header of " + size + " bytes");
        }
        return size;
    }

    /** Reads the whole current entry; refuses entries larger than {@code max} bytes. */
    public byte[] readAll(long max) throws IOException {
        if (remaining > max) {
            throw new IOException("tar entry of " + remaining + " bytes exceeds the limit of " + max);
        }
        byte[] data = readExactly(remaining);
        remaining = 0;
        return data;
    }

    private void skipCurrent() throws IOException {
        if (remaining > 0) {
            in.skipNBytes(remaining);
            remaining = 0;
        }
        if (padding > 0) {
            in.skipNBytes(padding);
            padding = 0;
        }
    }

    private byte[] readExactly(long size) throws IOException {
        if (size > Integer.MAX_VALUE - 16) {
            throw new IOException("tar entry too large: " + size);
        }
        byte[] data = in.readNBytes((int) size);
        if (data.length < size) {
            throw new IOException("truncated tar archive");
        }
        return data;
    }

    private static boolean allZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String ustarName(byte[] header) {
        String name = trimNul(new String(header, 0, 100, StandardCharsets.UTF_8));
        String magic = new String(header, 257, 5, StandardCharsets.US_ASCII);
        if ("ustar".equals(magic)) {
            String prefix = trimNul(new String(header, 345, 155, StandardCharsets.UTF_8));
            if (!prefix.isEmpty()) {
                return prefix + "/" + name;
            }
        }
        return name;
    }

    private static long parseSize(byte[] header) throws IOException {
        if ((header[124] & 0x80) != 0) {
            // GNU base-256: bit 6 of the first byte is the sign; a negative or overflowing size is malformed
            if ((header[124] & 0x40) != 0) {
                throw new IOException("malformed tar header: negative entry size");
            }
            long value = header[124] & 0x3f;
            for (int i = 125; i < 136; i++) {
                if (value > (Long.MAX_VALUE >> 8)) {
                    throw new IOException("malformed tar header: entry size out of range");
                }
                value = (value << 8) | (header[i] & 0xff);
            }
            return value;
        }
        String octal = trimNul(new String(header, 124, 12, StandardCharsets.US_ASCII)).trim();
        if (octal.isEmpty()) {
            return 0;
        }
        long value;
        try {
            value = Long.parseLong(octal, 8);
        } catch (NumberFormatException e) {
            throw new IOException("malformed tar header size '" + octal + "'");
        }
        if (value < 0) {
            throw new IOException("malformed tar header size '" + octal + "'");
        }
        return value;
    }

    private static Map<String, String> parsePax(byte[] data) {
        Map<String, String> records = new HashMap<>();
        int pos = 0;
        while (pos < data.length) {
            int space = indexOf(data, (byte) ' ', pos);
            if (space < 0) {
                break;
            }
            int length;
            try {
                length = Integer.parseInt(new String(data, pos, space - pos, StandardCharsets.US_ASCII).trim());
            } catch (NumberFormatException e) {
                break;
            }
            if (length <= 0 || pos + length > data.length) {
                break;
            }
            String record = new String(data, space + 1, pos + length - space - 1, StandardCharsets.UTF_8);
            int eq = record.indexOf('=');
            if (eq > 0) {
                String value = record.substring(eq + 1);
                if (value.endsWith("\n")) {
                    value = value.substring(0, value.length() - 1);
                }
                records.put(record.substring(0, eq), value);
            }
            pos += length;
        }
        return records;
    }

    private static int indexOf(byte[] data, byte b, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == b) {
                return i;
            }
        }
        return -1;
    }

    private static String trimNul(String s) {
        int nul = s.indexOf('\0');
        return nul < 0 ? s : s.substring(0, nul);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
