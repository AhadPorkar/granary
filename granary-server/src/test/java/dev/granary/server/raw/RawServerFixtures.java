package dev.granary.server.raw;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Small archives for the index-generating profiles (CPAN distributions, CRAN source packages). */
final class RawServerFixtures {

    private RawServerFixtures() {
    }

    /** A gzipped ustar archive of (name, content) pairs. */
    static byte[] tarGz(String... nameContentPairs) {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (int i = 0; i + 1 < nameContentPairs.length; i += 2) {
            byte[] data = nameContentPairs[i + 1].getBytes(StandardCharsets.UTF_8);
            tar.writeBytes(header(nameContentPairs[i], data.length));
            tar.writeBytes(data);
            int pad = (512 - data.length % 512) % 512;
            tar.writeBytes(new byte[pad]);
        }
        tar.writeBytes(new byte[1024]);
        return gzip(tar.toByteArray());
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String gunzip(byte[] data) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] header(String name, long size) {
        byte[] h = new byte[512];
        put(h, 0, name, 100);
        put(h, 100, "0000644", 8);
        put(h, 108, "0000000", 8);
        put(h, 116, "0000000", 8);
        put(h, 124, String.format("%011o", size), 12);
        put(h, 136, String.format("%011o", 1_700_000_000L), 12);
        h[156] = '0';
        put(h, 257, "ustar", 6);
        put(h, 263, "00", 2);
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        int sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, String.format("%06o", sum), 7);
        h[155] = ' ';
        return h;
    }

    private static void put(byte[] h, int offset, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, h, offset, Math.min(bytes.length, length));
    }
}
