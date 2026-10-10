package dev.granary.format.cargo;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Checks that a {@code .crate} file is what {@code cargo package} produces: a gzip-compressed tar archive whose
 * entries all live below {@code <name>-<version>/} and which contains {@code <name>-<version>/Cargo.toml}.
 * A registry that accepted anything here would hand out files cargo cannot unpack, and an entry escaping the
 * crate directory is how a malicious archive overwrites files on the machine that unpacks it. As on crates.io,
 * only regular files and directories are accepted: links, devices and FIFOs are refused.
 *
 * <p>The reader understands plain ustar entries, GNU long names ({@code L}) and pax headers ({@code x}),
 * which covers everything the {@code tar} crate that cargo uses writes.
 */
final class CrateArchive {

    private static final int BLOCK = 512;
    /** Generous ceiling for the uncompressed size, so a gzip bomb cannot keep a request thread busy forever. */
    static final long MAX_UNPACKED_BYTES = 4L * 1024 * 1024 * 1024;

    private CrateArchive() {
    }

    /** Throws a 400 {@link CargoError} describing the first problem found. */
    static void verify(InputStream crate, String name, String version) throws IOException {
        String root = name + "-" + version + "/";
        boolean manifest = false;
        int entries = 0;
        long unpacked = 0;
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(crate, 64 * 1024), 64 * 1024)) {
            byte[] header = new byte[BLOCK];
            String longName = null;
            String paxPath = null;
            while (true) {
                if (!readBlock(in, header)) {
                    break; // archives cut short after the last entry are tolerated, like tar does
                }
                if (isZero(header)) {
                    break;
                }
                if (!checksumMatches(header)) {
                    throw CargoError.badRequest("the crate file is not a valid tar archive (bad header checksum)");
                }
                long size = size(header);
                char type = (char) header[156];
                unpacked += size;
                if (unpacked > MAX_UNPACKED_BYTES) {
                    throw CargoError.badRequest("the crate file unpacks to more than " + MAX_UNPACKED_BYTES + " bytes");
                }
                if (type == 'L') {
                    longName = trimNul(new String(readData(in, size), StandardCharsets.UTF_8));
                    continue;
                }
                if (type == 'x') {
                    String path = paxValue(readData(in, size), "path");
                    if (path != null) {
                        paxPath = path;
                    }
                    continue;
                }
                if (type == 'g' || type == 'K') {
                    skipData(in, size);
                    continue;
                }
                String path = paxPath != null ? paxPath : longName != null ? longName : headerName(header);
                longName = null;
                paxPath = null;
                checkEntry(path, root, type);
                entries++;
                String normalised = path.startsWith("./") ? path.substring(2) : path;
                if (normalised.equals(root + "Cargo.toml") && (type == '0' || type == '\0')) {
                    manifest = true;
                }
                skipData(in, size);
            }
        } catch (ZipException | java.io.EOFException e) {
            throw CargoError.badRequest("the crate file is not a valid gzip-compressed tar archive: " + e.getMessage());
        }
        if (entries == 0) {
            throw CargoError.badRequest("the crate file is an empty archive");
        }
        if (!manifest) {
            throw CargoError.badRequest("the crate file has no " + root + "Cargo.toml");
        }
    }

    private static void checkEntry(String path, String root, char type) {
        if (path.isEmpty()) {
            throw CargoError.badRequest("the crate file contains an entry without a name");
        }
        String normalised = path.startsWith("./") ? path.substring(2) : path;
        if (!normalised.startsWith(root) && !normalised.equals(root.substring(0, root.length() - 1))) {
            throw CargoError.badRequest("the crate file contains `" + path + "`, which is outside the `" + root
                    + "` directory");
        }
        for (String segment : normalised.split("/")) {
            if (segment.equals("..")) {
                throw CargoError.badRequest("the crate file contains the path `" + path + "` with a `..` segment");
            }
        }
        if (normalised.indexOf('\\') >= 0 || normalised.indexOf('\0') >= 0) {
            throw CargoError.badRequest("the crate file contains the illegal path `" + path.replace("\0", "\\0") + "`");
        }
        if (type != '0' && type != '\0' && type != '5') {
            // like crates.io: other entry types extract differently from tool to tool, and a link can point anywhere
            throw CargoError.badRequest("the crate file contains `" + path + "`, which is " + describe(type)
                    + "; a crate may only contain regular files and directories");
        }
    }

    private static String describe(char type) {
        return switch (type) {
            case '1' -> "a hard link";
            case '2' -> "a symbolic link";
            case '3', '4' -> "a device";
            case '6' -> "a FIFO";
            default -> "an entry of tar type `" + (type >= ' ' && type < 127 ? String.valueOf(type) : "\\" + (int) type) + "`";
        };
    }

    private static String headerName(byte[] header) {
        String name = field(header, 0, 100);
        String magic = field(header, 257, 6);
        if (magic.startsWith("ustar")) {
            String prefix = field(header, 345, 155);
            if (!prefix.isEmpty()) {
                return prefix + "/" + name;
            }
        }
        return name;
    }

    private static long size(byte[] header) {
        if ((header[124] & 0x80) != 0) {
            // base-256 encoding for sizes that do not fit the octal field
            long value = 0;
            for (int i = 125; i < 136; i++) {
                value = (value << 8) | (header[i] & 0xff);
            }
            if (value < 0) {
                throw CargoError.badRequest("the crate file contains an entry with an impossible size");
            }
            return value;
        }
        String octal = field(header, 124, 12).trim();
        if (octal.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(octal, 8);
        } catch (NumberFormatException e) {
            throw CargoError.badRequest("the crate file is not a valid tar archive (bad size field)");
        }
    }

    private static boolean checksumMatches(byte[] header) {
        String stored = field(header, 148, 8).trim();
        if (stored.isEmpty()) {
            return false;
        }
        long expected;
        try {
            expected = Long.parseLong(stored, 8);
        } catch (NumberFormatException e) {
            return false;
        }
        long unsigned = 0;
        long signed = 0;
        for (int i = 0; i < BLOCK; i++) {
            int b = i >= 148 && i < 156 ? ' ' : header[i];
            unsigned += b & 0xff;
            signed += i >= 148 && i < 156 ? ' ' : header[i];
        }
        return expected == unsigned || expected == signed;
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String trimNul(String s) {
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }

    /** Reads one {@code key=value} record from a pax extended header ({@code "<len> key=value\n"} records, lengths in bytes). */
    static String paxValue(byte[] records, String key) {
        int pos = 0;
        String found = null;
        while (pos < records.length) {
            int space = pos;
            while (space < records.length && records[space] != ' ') {
                space++;
            }
            if (space >= records.length) {
                break;
            }
            int length;
            try {
                length = Integer.parseInt(new String(records, pos, space - pos, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                throw CargoError.badRequest("the crate file contains a malformed pax header");
            }
            if (length <= space - pos || pos + length > records.length) {
                throw CargoError.badRequest("the crate file contains a malformed pax header");
            }
            String record = new String(records, space + 1, pos + length - space - 1, StandardCharsets.UTF_8);
            if (record.endsWith("\n")) {
                record = record.substring(0, record.length() - 1);
            }
            int eq = record.indexOf('=');
            if (eq > 0 && record.substring(0, eq).equals(key)) {
                found = record.substring(eq + 1);
            }
            pos += length;
        }
        return found;
    }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int n = in.readNBytes(block, 0, BLOCK);
        if (n == 0) {
            return false;
        }
        if (n != BLOCK) {
            throw CargoError.badRequest("the crate file is not a valid tar archive (truncated header)");
        }
        return true;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > 1024 * 1024) {
            throw CargoError.badRequest("the crate file contains an oversized extended header");
        }
        byte[] data = in.readNBytes((int) size);
        if (data.length != size) {
            throw CargoError.badRequest("the crate file is not a valid tar archive (truncated entry)");
        }
        skipPadding(in, size);
        return data;
    }

    private static void skipData(InputStream in, long size) throws IOException {
        long remaining = (size + BLOCK - 1) / BLOCK * BLOCK;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                throw CargoError.badRequest("the crate file is not a valid tar archive (truncated entry)");
            }
            remaining -= n;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long rest = (BLOCK - size % BLOCK) % BLOCK;
        if (rest > 0) {
            in.readNBytes((int) rest);
        }
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
