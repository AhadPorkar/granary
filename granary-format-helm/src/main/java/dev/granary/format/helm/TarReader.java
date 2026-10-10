package dev.granary.format.helm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A forward-only reader of tar streams (ustar, GNU long names, pax extended headers): what {@code helm package}
 * writes and what other chart tooling produces. Entries are read one at a time; nothing is buffered except the
 * entries the caller asks for, and those only up to a limit.
 */
final class TarReader {

    private static final int BLOCK = 512;

    /** One member of the archive; {@code type} is the raw type flag. */
    record Entry(String name, long size, char type) {

        boolean isFile() {
            return type == '0' || type == '\0' || type == '7';
        }
    }

    private final InputStream in;
    private long remaining;
    private long padding;

    TarReader(InputStream in) {
        this.in = in;
    }

    /** Advances to the next entry, skipping the unread rest of the current one; {@code null} at the end. */
    Entry next() throws IOException {
        skipCurrent();
        String longName = null;
        String paxPath = null;
        Long paxSize = null;
        while (true) {
            byte[] header = in.readNBytes(BLOCK);
            if (header.length == 0 || allZero(header)) {
                return null;
            }
            if (header.length < BLOCK) {
                throw new IOException("truncated tar header");
            }
            verifyChecksum(header);
            char type = (char) (header[156] & 0xff);
            long size = parseSize(header);
            long pad = (BLOCK - (size % BLOCK)) % BLOCK;
            switch (type) {
                case 'L' -> {
                    longName = trimNul(new String(readExactly(size, 64 * 1024), StandardCharsets.UTF_8));
                    skip(pad);
                }
                case 'x' -> {
                    Map<String, String> pax = parsePax(readExactly(size, 1024 * 1024));
                    skip(pad);
                    if (pax.containsKey("path")) {
                        paxPath = pax.get("path");
                    }
                    if (pax.containsKey("size")) {
                        try {
                            paxSize = Long.parseLong(pax.get("size").trim());
                        } catch (NumberFormatException e) {
                            throw new IOException("malformed pax size");
                        }
                    }
                }
                case 'g', 'K' -> skip(size + pad);
                default -> {
                    String name = paxPath != null ? paxPath : longName != null ? longName : ustarName(header);
                    long actual = paxSize != null ? paxSize : size;
                    if (actual < 0) {
                        throw new IOException("negative tar entry size");
                    }
                    boolean hasData = type != '1' && type != '2' && type != '3' && type != '4' && type != '5' && type != '6';
                    remaining = hasData ? actual : 0;
                    padding = hasData ? (BLOCK - (actual % BLOCK)) % BLOCK : 0;
                    return new Entry(name, actual, type);
                }
            }
        }
    }

    /** Reads the whole current entry, refusing entries larger than {@code max} bytes. */
    byte[] read(int max) throws IOException {
        byte[] data = readExactly(remaining, max);
        remaining = 0;
        return data;
    }

    private void skipCurrent() throws IOException {
        if (remaining > 0) {
            skip(remaining);
            remaining = 0;
        }
        if (padding > 0) {
            skip(padding);
            padding = 0;
        }
    }

    private void skip(long n) throws IOException {
        try {
            in.skipNBytes(n);
        } catch (java.io.EOFException e) {
            throw new IOException("truncated tar archive", e);
        }
    }

    private byte[] readExactly(long size, int max) throws IOException {
        if (size > max) {
            throw new IOException("tar entry of " + size + " bytes exceeds the limit of " + max);
        }
        byte[] data = in.readNBytes((int) size);
        if (data.length < size) {
            throw new IOException("truncated tar archive");
        }
        return data;
    }

    private static void verifyChecksum(byte[] header) throws IOException {
        String field = trimNul(new String(header, 148, 8, StandardCharsets.US_ASCII)).trim();
        if (field.isEmpty()) {
            throw new IOException("not a tar archive");
        }
        long expected;
        try {
            expected = Long.parseLong(field, 8);
        } catch (NumberFormatException e) {
            throw new IOException("not a tar archive");
        }
        long unsigned = 0;
        long signed = 0;
        for (int i = 0; i < BLOCK; i++) {
            int b = (i >= 148 && i < 156) ? ' ' : header[i];
            unsigned += b & 0xff;
            signed += (byte) b;
        }
        if (expected != unsigned && expected != signed) {
            throw new IOException("tar header checksum mismatch");
        }
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
            long value = 0;
            for (int i = 125; i < 136; i++) {
                value = (value << 8) | (header[i] & 0xff);
            }
            return value;
        }
        String octal = trimNul(new String(header, 124, 12, StandardCharsets.US_ASCII)).trim();
        if (octal.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(octal, 8);
        } catch (NumberFormatException e) {
            throw new IOException("malformed tar entry size");
        }
    }

    private static Map<String, String> parsePax(byte[] data) {
        Map<String, String> records = new HashMap<>();
        int pos = 0;
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
                length = Integer.parseInt(new String(data, pos, space - pos, StandardCharsets.US_ASCII).trim());
            } catch (NumberFormatException e) {
                break;
            }
            if (length <= space - pos || pos + length > data.length) {
                break;
            }
            String record = new String(data, space + 1, pos + length - space - 1, StandardCharsets.UTF_8);
            int eq = record.indexOf('=');
            if (eq > 0) {
                String value = record.substring(eq + 1);
                records.put(record.substring(0, eq), value.endsWith("\n") ? value.substring(0, value.length() - 1) : value);
            }
            pos += length;
        }
        return records;
    }

    private static String trimNul(String s) {
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }
}
