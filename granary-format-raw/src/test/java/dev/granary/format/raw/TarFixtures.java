package dev.granary.format.raw;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds small tar, tar.gz and zip archives in memory, in the header variants real tools produce. */
final class TarFixtures {

    record File(String name, byte[] data) {
        File(String name, String text) {
            this(name, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private TarFixtures() {
    }

    /** A ustar archive; names longer than 100 characters use GNU {@code ././@LongLink} entries. */
    static byte[] tar(List<File> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (File file : files) {
            boolean directory = file.name().endsWith("/");
            if (file.name().length() > 100) {
                byte[] longName = (file.name() + "\0").getBytes(StandardCharsets.UTF_8);
                out.writeBytes(header("././@LongLink", longName.length, 'L', ""));
                out.writeBytes(padded(longName));
            }
            String name = file.name().length() > 100 ? file.name().substring(0, 100) : file.name();
            out.writeBytes(header(name, directory ? 0 : file.data().length, directory ? '5' : '0', ""));
            if (!directory) {
                out.writeBytes(padded(file.data()));
            }
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    /** One entry whose name is split into the ustar prefix and name fields. */
    static byte[] tarWithPrefix(String prefix, String name, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header(name, data.length, '0', prefix));
        out.writeBytes(padded(data));
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    /** One entry preceded by a pax extended header carrying its real path and size. */
    static byte[] paxTar(String realName, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String pathRecord = paxRecord("path", realName);
        String sizeRecord = paxRecord("size", Long.toString(data.length));
        byte[] pax = (pathRecord + sizeRecord).getBytes(StandardCharsets.UTF_8);
        out.writeBytes(header("PaxHeaders/x", pax.length, 'x', ""));
        out.writeBytes(padded(pax));
        out.writeBytes(header("truncated-name", data.length, '0', ""));
        out.writeBytes(padded(data));
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    static byte[] tarGz(List<File> files) {
        return gzip(tar(files));
    }

    static byte[] tarGz(String... nameContentPairs) {
        List<File> files = new ArrayList<>();
        for (int i = 0; i + 1 < nameContentPairs.length; i += 2) {
            files.add(new File(nameContentPairs[i], nameContentPairs[i + 1]));
        }
        return tarGz(files);
    }

    static byte[] zip(String... nameContentPairs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i + 1 < nameContentPairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameContentPairs[i]));
                zip.write(nameContentPairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
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

    private static String paxRecord(String key, String value) {
        String body = " " + key + "=" + value + "\n";
        int length = body.length() + 1;
        while (Integer.toString(length).length() + body.length() != length) {
            length++;
        }
        return length + body;
    }

    static byte[] header(String name, long size, char type, String prefix) {
        byte[] h = new byte[512];
        put(h, 0, name, 100);
        put(h, 100, "0000644\0", 8);
        put(h, 108, "0000000\0", 8);
        put(h, 116, "0000000\0", 8);
        put(h, 124, String.format(Locale.ROOT, "%011o\0", size), 12);
        put(h, 136, String.format(Locale.ROOT, "%011o\0", 0L), 12);
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        h[156] = (byte) type;
        put(h, 257, "ustar\0", 6);
        put(h, 263, "00", 2);
        put(h, 345, prefix, 155);
        int sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, String.format(Locale.ROOT, "%06o\0 ", sum), 8);
        return h;
    }

    private static void put(byte[] h, int offset, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, h, offset, Math.min(bytes.length, length));
    }

    private static byte[] padded(byte[] data) {
        int pad = (512 - (data.length % 512)) % 512;
        byte[] result = new byte[data.length + pad];
        System.arraycopy(data, 0, result, 0, data.length);
        return result;
    }
}
