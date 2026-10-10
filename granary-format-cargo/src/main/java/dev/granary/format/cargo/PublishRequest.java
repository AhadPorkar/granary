package dev.granary.format.cargo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.node.ObjectNode;

/**
 * The body of {@code PUT /api/v1/crates/new}: a little-endian {@code u32} length, the JSON metadata, another
 * {@code u32} length and the {@code .crate} file. The crate is streamed into a scratch file while its SHA-256
 * is computed, so a large crate never sits in memory; close the request to delete that file.
 */
final class PublishRequest implements AutoCloseable {

    /** Far above anything cargo sends (the metadata is a few kilobytes plus the readme). */
    static final long MAX_METADATA_BYTES = 16L * 1024 * 1024;

    private final ObjectNode metadata;
    private final Path crateFile;
    private final long crateSize;
    private final String sha256;

    private PublishRequest(ObjectNode metadata, Path crateFile, long crateSize, String sha256) {
        this.metadata = metadata;
        this.crateFile = crateFile;
        this.crateSize = crateSize;
        this.sha256 = sha256;
    }

    /**
     * Reads a publish body. Every malformation (truncation, a length that does not fit, metadata that is not a
     * JSON object, trailing bytes) is a {@link CargoError} with status 400.
     *
     * @param tempDir where the crate is buffered; created when missing
     */
    static PublishRequest read(InputStream in, Path tempDir) throws IOException {
        long jsonLength = readLength(in, "metadata length");
        if (jsonLength > MAX_METADATA_BYTES) {
            throw CargoError.badRequest("publish metadata of " + jsonLength + " bytes is larger than the limit of "
                    + MAX_METADATA_BYTES + " bytes");
        }
        byte[] json = in.readNBytes((int) jsonLength);
        if (json.length != jsonLength) {
            throw CargoError.badRequest("the publish request is truncated: expected " + jsonLength
                    + " bytes of metadata, got " + json.length);
        }
        ObjectNode metadata = CargoJson.parseObject(new String(json, StandardCharsets.UTF_8))
                .orElseThrow(() -> CargoError.badRequest("the publish metadata is not a JSON object"));
        long crateLength = readLength(in, "crate length");
        Files.createDirectories(tempDir);
        Path file = Files.createTempFile(tempDir, "publish-", ".crate");
        try {
            MessageDigest digest = newDigest();
            long copied;
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(file), digest)) {
                copied = copy(in, out, crateLength);
            }
            if (copied != crateLength) {
                throw CargoError.badRequest("the publish request is truncated: expected " + crateLength
                        + " bytes of crate, got " + copied);
            }
            if (in.read() != -1) {
                throw CargoError.badRequest("unexpected data after the crate file in the publish request");
            }
            if (crateLength == 0) {
                throw CargoError.badRequest("the crate file is empty");
            }
            return new PublishRequest(metadata, file, crateLength, HexFormat.of().formatHex(digest.digest()));
        } catch (RuntimeException | IOException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    ObjectNode metadata() {
        return metadata;
    }

    Path crateFile() {
        return crateFile;
    }

    long crateSize() {
        return crateSize;
    }

    /** SHA-256 of the crate file, lower-case hex: the index's {@code cksum}. */
    String sha256() {
        return sha256;
    }

    InputStream openCrate() throws IOException {
        return Files.newInputStream(crateFile);
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(crateFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long readLength(InputStream in, String what) throws IOException {
        byte[] b = in.readNBytes(4);
        if (b.length != 4) {
            throw CargoError.badRequest(b.length == 0 && what.startsWith("metadata")
                    ? "the publish request is empty"
                    : "the publish request is truncated: missing the " + what);
        }
        return (b[0] & 0xffL) | (b[1] & 0xffL) << 8 | (b[2] & 0xffL) << 16 | (b[3] & 0xffL) << 24;
    }

    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        while (total < limit) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, limit - total));
            if (n < 0) {
                break;
            }
            out.write(buffer, 0, n);
            total += n;
        }
        return total;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds a body the way cargo does; used by tests and handy for tooling. */
    static byte[] encode(byte[] json, byte[] crate) {
        byte[] out = new byte[8 + json.length + crate.length];
        writeLength(out, 0, json.length);
        System.arraycopy(json, 0, out, 4, json.length);
        writeLength(out, 4 + json.length, crate.length);
        System.arraycopy(crate, 0, out, 8 + json.length, crate.length);
        return out;
    }

    private static void writeLength(byte[] out, int offset, int value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >>> 8);
        out[offset + 2] = (byte) (value >>> 16);
        out[offset + 3] = (byte) (value >>> 24);
    }
}
