package dev.granary.format.helm;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Reads a packaged chart ({@code <name>-<version>.tgz}) the way Helm's loader does: a gzip-compressed tar whose
 * members all live below one top-level directory, with the chart's {@code Chart.yaml} directly inside it. The
 * whole archive is read, so a truncated or corrupt upload is refused instead of stored; for {@code apiVersion: v1}
 * charts the dependencies are taken from {@code requirements.yaml} as Helm 2 kept them there.
 */
public final class ChartArchive {

    /** Helm refuses charts that expand beyond 100 MiB; anything larger is not a chart. */
    public static final long MAX_EXPANDED_SIZE = 100L * 1024 * 1024;

    /**
     * What the archive says about itself.
     *
     * @param chartYaml    the parsed {@code Chart.yaml}
     * @param requirements the parsed {@code requirements.yaml}, or {@code null} when the archive has none
     */
    public record Contents(Map<String, Object> chartYaml, Map<String, Object> requirements) {
    }

    private ChartArchive() {
    }

    /** Reads the chart from {@code in}; throws {@link InvalidChartException} for anything Helm could not load. */
    public static Contents read(InputStream in) throws IOException {
        Map<String, Object> chartYaml = null;
        Map<String, Object> requirements = null;
        try {
            TarReader tar = new TarReader(new LimitedInputStream(new GZIPInputStream(in, 64 * 1024), MAX_EXPANDED_SIZE));
            int entries = 0;
            for (TarReader.Entry entry = tar.next(); entry != null; entry = tar.next()) {
                entries++;
                String path = belowTopDirectory(entry.name());
                if (path == null || !entry.isFile()) {
                    continue;
                }
                if (path.equals("Chart.yaml") && chartYaml == null) {
                    chartYaml = yaml(tar, "Chart.yaml");
                } else if (path.equals("requirements.yaml") && requirements == null) {
                    requirements = yaml(tar, "requirements.yaml");
                }
            }
            if (entries == 0) {
                throw new InvalidChartException("the archive is empty");
            }
        } catch (ZipException e) {
            throw new InvalidChartException("not a gzip-compressed tar archive", e);
        } catch (java.io.EOFException e) {
            throw new InvalidChartException("the archive is truncated", e);
        } catch (InvalidChartException e) {
            throw e;
        } catch (IOException e) {
            if (e.getCause() instanceof InvalidChartException invalid) {
                throw invalid;
            }
            throw new InvalidChartException(e.getMessage() == null ? "unreadable archive" : e.getMessage(), e);
        }
        if (chartYaml == null) {
            throw new InvalidChartException("Chart.yaml file is missing");
        }
        return new Contents(chartYaml, requirements);
    }

    private static Map<String, Object> yaml(TarReader tar, String file) throws IOException {
        byte[] bytes;
        try {
            bytes = tar.read(HelmYaml.SMALL_DOCUMENT_LIMIT);
        } catch (IOException e) {
            throw new InvalidChartException(file + " is too large", e);
        }
        try {
            return HelmYaml.loadMap(bytes, HelmYaml.SMALL_DOCUMENT_LIMIT);
        } catch (HelmYaml.InvalidYamlException e) {
            throw new InvalidChartException(file + " is not valid YAML: " + e.getMessage(), e);
        }
    }

    /**
     * The member's path with the top-level directory removed, as Helm's loader sees it; {@code null} for the
     * directory itself. Members outside the base directory or with absolute or parent paths make the chart invalid.
     */
    static String belowTopDirectory(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        int slash = n.indexOf('/');
        if (slash < 0) {
            if (n.isEmpty()) {
                return null;
            }
            throw new InvalidChartException("chart illegally contains content outside the base directory: " + name);
        }
        String rest = n.substring(slash + 1);
        if (rest.isEmpty()) {
            return null;
        }
        if (rest.startsWith("/")) {
            throw new InvalidChartException("chart illegally contains absolute paths");
        }
        StringBuilder clean = new StringBuilder();
        int depth = 0;
        for (String segment : rest.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new InvalidChartException("chart illegally references parent directory");
            }
            if (depth++ > 0) {
                clean.append('/');
            }
            clean.append(segment);
        }
        return clean.isEmpty() ? null : clean.toString();
    }

    /** Thrown for archives Helm would refuse to load; the message is shown to the uploader. */
    public static final class InvalidChartException extends RuntimeException {
        public InvalidChartException(String message) {
            super(message);
        }

        public InvalidChartException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Stops decompression bombs: refuses to produce more than {@code limit} bytes. */
    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) {
                count(skipped);
            }
            return skipped;
        }

        private void count(long n) throws IOException {
            count += n;
            if (count > limit) {
                throw new IOException(new InvalidChartException("the chart expands to more than " + (limit / (1024 * 1024)) + " MiB"));
            }
        }
    }
}
