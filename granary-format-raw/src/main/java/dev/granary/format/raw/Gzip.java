package dev.granary.format.raw;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** gzip of in-memory index documents ({@code PACKAGES.gz}, {@code 02packages.details.txt.gz}). */
final class Gzip {

    private Gzip() {
    }

    static byte[] compress(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 3));
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Decompresses an index document, refusing anything that inflates beyond {@code max} bytes. */
    static byte[] decompress(byte[] data, int max) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            byte[] result = in.readNBytes(max + 1);
            if (result.length > max) {
                throw new IOException("decompressed document exceeds " + max + " bytes");
            }
            return result;
        }
    }
}
