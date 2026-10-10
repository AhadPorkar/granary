package dev.granary.format.helm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Builds tar and tar.gz archives byte by byte (ustar headers, pax records for long names) for the chart tests. */
final class TarFixtures {

    record Entry(String name, byte[] data, char type) {
        static Entry file(String name, String text) {
            return new Entry(name, text.getBytes(StandardCharsets.UTF_8), '0');
        }

        static Entry dir(String name) {
            return new Entry(name, new byte[0], '5');
        }
    }

    private TarFixtures() {
    }

    /** A packaged chart the way {@code helm package} lays it out: {@code <name>/Chart.yaml}, values and a template. */
    static byte[] chart(String name, String version) {
        return chart(name, version, "");
    }

    /** A chart whose Chart.yaml has the given extra lines appended. */
    static byte[] chart(String name, String version, String extraChartYaml) {
        String chartYaml = "apiVersion: v2\nname: " + name + "\ndescription: A Helm chart for Kubernetes\ntype: application\n"
                + "version: " + version + "\nappVersion: \"1.16.0\"\n" + extraChartYaml;
        return tgz(List.of(
                Entry.file(name + "/Chart.yaml", chartYaml),
                Entry.file(name + "/values.yaml", "replicaCount: 1\n"),
                Entry.file(name + "/templates/deployment.yaml", "kind: Deployment\n"),
                Entry.file(name + "/.helmignore", ".git\n")));
    }

    /** A chart with an arbitrary Chart.yaml. */
    static byte[] chartWithYaml(String dir, String chartYaml) {
        return tgz(List.of(Entry.file(dir + "/Chart.yaml", chartYaml), Entry.file(dir + "/values.yaml", "a: 1\n")));
    }

    static byte[] tgz(List<Entry> entries) {
        return gzip(tar(entries));
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static byte[] tar(List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Entry e : entries) {
            byte[] nameBytes = e.name().getBytes(StandardCharsets.UTF_8);
            if (nameBytes.length > 100) {
                byte[] pax = paxRecord("path", e.name());
                writeHeader(out, "PaxHeaders/x", pax.length, 'x');
                writeData(out, pax);
                writeHeader(out, e.name().substring(0, 50), e.data().length, e.type());
            } else {
                writeHeader(out, e.name(), e.data().length, e.type());
            }
            writeData(out, e.data());
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    static byte[] paxRecord(String key, String value) {
        String body = " " + key + "=" + value + "\n";
        int len = body.getBytes(StandardCharsets.UTF_8).length;
        int total = len + Integer.toString(len).length();
        if (Integer.toString(total).length() != Integer.toString(len).length()) {
            total++;
        }
        return (total + body).getBytes(StandardCharsets.UTF_8);
    }

    static void writeHeader(ByteArrayOutputStream out, String name, long size, char type) {
        byte[] h = new byte[512];
        put(h, 0, name.getBytes(StandardCharsets.UTF_8), 100);
        put(h, 100, "0000644\0".getBytes(StandardCharsets.US_ASCII), 8);
        put(h, 108, "0000000\0".getBytes(StandardCharsets.US_ASCII), 8);
        put(h, 116, "0000000\0".getBytes(StandardCharsets.US_ASCII), 8);
        put(h, 124, (String.format("%011o", size) + "\0").getBytes(StandardCharsets.US_ASCII), 12);
        put(h, 136, (String.format("%011o", 1_700_000_000L) + "\0").getBytes(StandardCharsets.US_ASCII), 12);
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        h[156] = (byte) type;
        put(h, 257, "ustar\0".getBytes(StandardCharsets.US_ASCII), 6);
        put(h, 263, "00".getBytes(StandardCharsets.US_ASCII), 2);
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, (String.format("%06o", sum) + "\0 ").getBytes(StandardCharsets.US_ASCII), 8);
        out.writeBytes(h);
    }

    static void writeData(ByteArrayOutputStream out, byte[] data) {
        out.writeBytes(data);
        int pad = (512 - (data.length % 512)) % 512;
        out.writeBytes(new byte[pad]);
    }

    private static void put(byte[] header, int offset, byte[] value, int max) {
        System.arraycopy(value, 0, header, offset, Math.min(value.length, max));
    }

    /** A test resource as bytes, e.g. {@code charts/demo-app-0.2.0.tgz} packaged and signed by helm 3.19. */
    static byte[] resource(String path) {
        try (InputStream in = TarFixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("no test resource " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Entries for tests that need several files. */
    static List<Entry> entries(Entry... entries) {
        return new ArrayList<>(List.of(entries));
    }
}
