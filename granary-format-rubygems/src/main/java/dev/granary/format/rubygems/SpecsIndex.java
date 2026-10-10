package dev.granary.format.rubygems;

import dev.granary.format.rubygems.marshal.MarshalException;
import dev.granary.format.rubygems.marshal.MarshalReader;
import dev.granary.format.rubygems.marshal.MarshalWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The legacy (pre compact index) indexes {@code gem search}, {@code gem fetch}, {@code gem list -r} and older
 * clients read: {@code specs.4.8.gz} (every released version), {@code latest_specs.4.8.gz} (the newest released
 * version per gem and platform) and {@code prerelease_specs.4.8.gz}, each a gzipped {@code Marshal.dump} of an
 * array of {@code [name, Gem::Version, platform]}.
 *
 * <p>The rubygems.org {@code specs.4.8.gz} holds 1.7 million tuples, so reading is streamed with a lean
 * {@link MarshalReader} and merging copies the biggest index through byte for byte instead of re-encoding it.
 */
public final class SpecsIndex {

    public static final String SPECS = "specs.4.8.gz";
    public static final String LATEST_SPECS = "latest_specs.4.8.gz";
    public static final String PRERELEASE_SPECS = "prerelease_specs.4.8.gz";

    private static final int BUFFER = 64 * 1024;

    private SpecsIndex() {
    }

    /** Opens a stream again; merges read their base index twice. */
    @FunctionalInterface
    public interface Source {
        InputStream open() throws IOException;
    }

    // --- selecting -----------------------------------------------------------------------------------

    /** Released versions, sorted: the content of {@code specs.4.8}. */
    public static List<SpecTuple> released(Collection<SpecTuple> tuples) {
        return sorted(tuples.stream().filter(t -> !t.isPrerelease()).toList());
    }

    /** Prerelease versions, sorted: the content of {@code prerelease_specs.4.8}. */
    public static List<SpecTuple> prerelease(Collection<SpecTuple> tuples) {
        return sorted(tuples.stream().filter(SpecTuple::isPrerelease).toList());
    }

    /** The newest released version of every gem on every platform, sorted: {@code latest_specs.4.8}. */
    public static List<SpecTuple> latest(Collection<SpecTuple> tuples) {
        Map<String, SpecTuple> latest = new HashMap<>();
        for (SpecTuple t : tuples) {
            if (t.isPrerelease()) {
                continue;
            }
            latest.merge(t.latestKey(), t, (a, b) -> GemVersion.compareStrings(a.version(), b.version()) >= 0 ? a : b);
        }
        return sorted(latest.values());
    }

    /** Distinct tuples in RubyGems order. */
    public static List<SpecTuple> sorted(Collection<SpecTuple> tuples) {
        Map<String, SpecTuple> distinct = new LinkedHashMap<>();
        for (SpecTuple t : tuples) {
            distinct.putIfAbsent(t.key(), t);
        }
        List<SpecTuple> list = new ArrayList<>(distinct.values());
        list.sort(null);
        return list;
    }

    // --- writing -------------------------------------------------------------------------------------

    /** Writes {@code gzip(Marshal.dump(tuples))}, linking repeated strings the way {@code Marshal.dump} does. */
    public static void write(List<SpecTuple> tuples, OutputStream out) throws IOException {
        GZIPOutputStream gzip = new GZIPOutputStream(out, BUFFER);
        try {
            MarshalWriter writer = new MarshalWriter(gzip).sharingStrings();
            writer.writeHeader();
            writer.writeArrayHeader(tuples.size());
            for (SpecTuple t : tuples) {
                writer.write(t.toMarshal());
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        gzip.finish();
    }

    public static byte[] gzip(List<SpecTuple> tuples) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            write(tuples, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // --- reading -------------------------------------------------------------------------------------

    /** Every usable tuple of a gzipped index; entries that are not tuples are skipped. */
    public static List<SpecTuple> read(InputStream gzipped) throws IOException {
        List<SpecTuple> tuples = new ArrayList<>();
        forEach(gzipped, tuples::add);
        return tuples;
    }

    /**
     * Streams the tuples of a gzipped index. {@link MarshalException} when it is not a marshalled array,
     * {@link IOException} when it is not gzip data.
     */
    public static void forEach(InputStream gzipped, Consumer<SpecTuple> consumer) throws IOException {
        try (InputStream in = new GZIPInputStream(gzipped, BUFFER)) {
            MarshalReader reader = new MarshalReader(in, true);
            reader.readHeader();
            long length = reader.readArrayHeader();
            for (long i = 0; i < length; i++) {
                SpecTuple t = SpecTuple.fromMarshal(reader.readObject());
                if (t != null) {
                    consumer.accept(t);
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    // --- merging -------------------------------------------------------------------------------------

    /**
     * Writes the union of {@code base} and {@code others} as a gzipped index: the elements of {@code base} are copied
     * through unchanged (no re-encoding, so the 25 MB rubygems.org index costs two decompressions and a copy) and
     * the tuples of {@code others} that {@code base} lacks are appended after them.
     *
     * @return the number of tuples appended
     */
    public static int mergeOnto(Source base, Collection<SpecTuple> others, OutputStream out) throws IOException {
        Map<String, SpecTuple> pending = new LinkedHashMap<>();
        for (SpecTuple t : sorted(others)) {
            pending.put(t.key(), t);
        }
        long length;
        long start;
        long end;
        int symbols;
        int objects;
        try (InputStream in = new GZIPInputStream(base.open(), BUFFER)) {
            MarshalReader reader = new MarshalReader(in, true);
            reader.readHeader();
            length = reader.readArrayHeader();
            start = reader.position();
            for (long i = 0; i < length; i++) {
                SpecTuple t = SpecTuple.fromMarshal(reader.readObject());
                if (t != null && !pending.isEmpty()) {
                    pending.remove(t.key());
                }
            }
            end = reader.position();
            symbols = reader.symbolCount();
            objects = reader.objectCount();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        GZIPOutputStream gzip = new GZIPOutputStream(out, BUFFER);
        try {
            MarshalWriter header = new MarshalWriter(gzip);
            header.writeHeader();
            header.writeArrayHeader(length + pending.size());
            try (InputStream in = new GZIPInputStream(base.open(), BUFFER)) {
                in.skipNBytes(start);
                copy(in, gzip, end - start);
            }
            MarshalWriter tail = MarshalWriter.continuing(gzip, symbols, objects).sharingStrings();
            for (SpecTuple t : pending.values()) {
                tail.write(t.toMarshal());
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        gzip.finish();
        return pending.size();
    }

    private static void copy(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[BUFFER];
        long remaining = count;
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                throw new IOException("index changed while it was merged");
            }
            out.write(buffer, 0, n);
            remaining -= n;
        }
    }
}
