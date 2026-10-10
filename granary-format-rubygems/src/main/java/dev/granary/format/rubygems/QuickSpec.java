package dev.granary.format.rubygems;

import dev.granary.format.rubygems.marshal.MarshalException;
import dev.granary.format.rubygems.marshal.MarshalReader;
import dev.granary.format.rubygems.marshal.MarshalWriter;
import dev.granary.format.rubygems.marshal.Ruby;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * {@code quick/Marshal.4.8/<full name>.gemspec.rz}: a {@code Gem::Specification} marshalled through its
 * {@code _dump} (an array of 19 fields in a fixed order, see {@code Gem::Specification#_dump}) and
 * zlib-deflated. {@code gem install} and {@code gem fetch} load it with {@code Gem::SafeMarshal}, so only the
 * classes, ivars and symbols that loader permits are written.
 */
public final class QuickSpec {

    /** Upper bound for an inflated spec; real ones are a few kilobytes. */
    static final int MAX_INFLATED = 16 * 1024 * 1024;

    private QuickSpec() {
    }

    /** The {@code .gemspec.rz} bytes of a spec. */
    public static byte[] deflated(GemSpec spec) {
        return deflate(marshal(spec));
    }

    /** {@code Marshal.dump(spec)}: {@code u:Gem::Specification} around the dumped field array. */
    public static byte[] marshal(GemSpec spec) {
        return MarshalWriter.dump(new Ruby.UserDefined("Gem::Specification", MarshalWriter.dump(fields(spec)), Map.of()));
    }

    /** The {@code _dump} array of {@code Gem::Specification} (specification version 4). */
    static List<Object> fields(GemSpec spec) {
        List<Object> deps = new ArrayList<>();
        for (GemDependency d : spec.dependencies()) {
            deps.add(d.toMarshal());
        }
        Map<Object, Object> metadata = new LinkedHashMap<>(spec.metadata());
        Object email = spec.emailIsList() ? new ArrayList<Object>(spec.email())
                : spec.email().isEmpty() ? null : spec.email().getFirst();
        boolean ruby = GemPlatform.isRuby(spec.platform());
        List<Object> fields = new ArrayList<>();
        fields.add(spec.rubygemsVersion());
        fields.add(spec.specificationVersion());
        fields.add(spec.name());
        fields.add(Ruby.gemVersion(spec.version()));
        fields.add(time(spec.date()));
        fields.add(spec.summary());
        fields.add(spec.requiredRubyVersion().toMarshal());
        fields.add(spec.requiredRubygemsVersion().toMarshal());
        fields.add(spec.platform());
        fields.add(deps);
        fields.add("");
        fields.add(email);
        fields.add(new ArrayList<Object>(spec.authors()));
        fields.add(spec.description());
        fields.add(spec.homepage());
        fields.add(Boolean.TRUE);
        fields.add(ruby ? GemPlatform.RUBY : GemPlatform.parse(spec.platform()).toMarshal());
        fields.add(new ArrayList<Object>(spec.licenses()));
        fields.add(metadata);
        return fields;
    }

    /**
     * Midnight UTC of {@code date} as {@code Time#_dump} writes it: two little-endian 32-bit words packing the
     * broken-down time, and the {@code zone} ivar.
     */
    static Ruby.UserDefined time(LocalDate date) {
        LocalDate d = date.getYear() < 1900 || date.getYear() > 1900 + 0xffff ? LocalDate.of(1980, 1, 2) : date;
        long p = 1L << 31 | 1L << 30 | (long) (d.getYear() - 1900) << 14 | (long) (d.getMonthValue() - 1) << 10
                | (long) d.getDayOfMonth() << 5;
        long s = 0;
        byte[] bytes = new byte[8];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) (p >>> (8 * i));
            bytes[4 + i] = (byte) (s >>> (8 * i));
        }
        Map<String, Object> ivars = new LinkedHashMap<>();
        ivars.put("zone", new Ruby.AsciiString("UTC"));
        return new Ruby.UserDefined("Time", bytes, ivars);
    }

    /** The date of a marshalled {@code Time}, or {@code null}. */
    static LocalDate date(Object value) {
        if (!(value instanceof Ruby.UserDefined u) || !u.className().equals("Time") || u.data().length < 8) {
            return null;
        }
        long p = 0;
        for (int i = 0; i < 4; i++) {
            p |= (long) (u.data()[i] & 0xff) << (8 * i);
        }
        if ((p & (1L << 31)) == 0) {
            return null;
        }
        int year = (int) ((p >>> 14) & 0xffff) + 1900;
        int month = (int) ((p >>> 10) & 0xf) + 1;
        int day = (int) ((p >>> 5) & 0x1f);
        try {
            return LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /** Reads a {@code .gemspec.rz}; {@link MarshalException} when it is not a marshalled specification. */
    public static GemSpec read(byte[] deflated) {
        Object outer = MarshalReader.load(inflate(deflated));
        if (!(outer instanceof Ruby.UserDefined u) || !u.className().equals("Gem::Specification")) {
            throw new MarshalException("not a marshalled Gem::Specification");
        }
        if (!(MarshalReader.load(u.data()) instanceof List<?> f) || f.size() < 17) {
            throw new MarshalException("not a Gem::Specification field array");
        }
        String name = f.get(2) instanceof String s ? s : null;
        String version = Ruby.gemVersionString(f.get(3));
        if (name == null || version == null) {
            throw new MarshalException("Gem::Specification without name or version");
        }
        List<GemDependency> dependencies = new ArrayList<>();
        if (f.get(9) instanceof List<?> deps) {
            for (Object dep : deps) {
                GemDependency d = GemDependency.fromMarshal(dep);
                if (d != null) {
                    dependencies.add(d);
                }
            }
        }
        Object email = f.get(11);
        return new GemSpec(name, version, GemPlatform.fromMarshal(f.get(16)),
                f.get(5) instanceof String s ? s : "",
                f.get(13) instanceof String s ? s : null,
                strings(f.get(12)),
                strings(email),
                email instanceof List<?>,
                f.get(14) instanceof String s ? s : null,
                f.size() > 17 ? strings(f.get(17)) : List.of(),
                f.size() > 18 ? metadata(f.get(18)) : Map.of(),
                dependencies,
                GemRequirement.fromMarshal(f.get(6)),
                GemRequirement.fromMarshal(f.get(7)),
                f.get(0) instanceof String s ? s : null,
                f.get(1) instanceof Long l ? l.intValue() : 4,
                date(f.get(4)));
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    result.add(s);
                } else if (o instanceof List<?> nested) {
                    result.addAll(strings(nested));
                }
            }
        } else if (value instanceof String s && !s.isEmpty()) {
            result.add(s);
        }
        return result;
    }

    private static Map<String, String> metadata(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k instanceof String key && v instanceof String s) {
                    result.put(key, s);
                }
            });
        }
        return result;
    }

    static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    static byte[] inflate(byte[] data) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 4));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new MarshalException("truncated zlib data");
                }
                out.write(buffer, 0, n);
                if (out.size() > MAX_INFLATED) {
                    throw new MarshalException("inflated spec is too large");
                }
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new MarshalException("not zlib data: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }
}
