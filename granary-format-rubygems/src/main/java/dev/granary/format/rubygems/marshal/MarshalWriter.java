package dev.granary.format.rubygems.marshal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes Ruby Marshal 4.8 the way {@code Marshal.dump} does, for the subset RubyGems indexes need: nil, booleans,
 * integers, floats, strings (UTF-8, tagged with the {@code E} encoding ivar), binary strings, symbols (with symbol
 * links for repeats), arrays, hashes and the {@link Ruby} records.
 *
 * <p>Symbols are linked ({@code ;}) after their first occurrence. Objects are written out in full, except that a
 * writer created with {@link #sharingStrings} links ({@code @}) a string equal to one it wrote recently instead
 * of repeating it, as {@code Marshal.dump} does for the shared name and platform strings of an index. To get
 * those links right the writer numbers every object exactly as {@code Marshal.load} registers them.
 *
 * <p>A writer can continue a stream that something else started (see {@link #continuing}), which is how a large
 * upstream index is passed through unchanged and extended with a few entries.
 */
public final class MarshalWriter {

    private static final long FIXNUM_MIN = -(1L << 30);
    private static final long FIXNUM_MAX = (1L << 30) - 1;

    /** How many recently written strings a sharing writer remembers for links. */
    private static final int SHARED_STRINGS = 1024;

    private final OutputStream out;
    private final Map<String, Integer> symbols = new HashMap<>();
    private int symbolBase;
    private long objects;
    private Map<String, Long> sharedStrings;

    public MarshalWriter(OutputStream out) {
        this.out = out;
    }

    /**
     * A writer for objects appended to a stream that already defined {@code existingSymbols} symbols and
     * registered {@code existingObjects} objects: its own symbol and object links are numbered after them.
     */
    public static MarshalWriter continuing(OutputStream out, int existingSymbols, long existingObjects) {
        MarshalWriter writer = new MarshalWriter(out);
        writer.symbolBase = existingSymbols;
        writer.objects = existingObjects;
        return writer;
    }

    /** Makes this writer link repeated strings to their earlier occurrence instead of writing them again. */
    public MarshalWriter sharingStrings() {
        this.sharedStrings = new HashMap<>();
        return this;
    }

    /** Objects registered so far (including those of the stream this writer continues). */
    public long objectCount() {
        return objects;
    }

    /** {@code Marshal.dump(value)}: the version header followed by the value. */
    public static byte[] dump(Object value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MarshalWriter writer = new MarshalWriter(bytes);
        writer.writeHeader();
        writer.write(value);
        return bytes.toByteArray();
    }

    /** Symbols this writer has defined so far. */
    public int symbolCount() {
        return symbols.size();
    }

    public void writeHeader() {
        writeByte(4);
        writeByte(8);
    }

    /** The start of an array whose elements the caller writes next, one {@link #write} each. */
    public void writeArrayHeader(long length) {
        objects++;
        writeByte('[');
        writeLong(length);
    }

    public void write(Object value) {
        switch (value) {
            case null -> writeByte('0');
            case Boolean b -> writeByte(b ? 'T' : 'F');
            case Integer i -> writeInteger(i);
            case Long l -> writeInteger(l);
            case Short s -> writeInteger(s);
            case Byte b -> writeInteger(b);
            case BigInteger big -> writeBigInteger(big);
            case Double d -> writeFloat(d);
            case Float f -> writeFloat(f);
            case String s -> writeString(s);
            case Ruby.AsciiString a -> {
                objects++;
                writeByte('I');
                writeByte('"');
                writeBytes(a.value().getBytes(StandardCharsets.US_ASCII));
                writeLong(1);
                writeSymbol("E");
                writeByte('F');
            }
            case byte[] bytes -> {
                objects++;
                writeByte('"');
                writeBytes(bytes);
            }
            case Ruby.Symbol s -> writeSymbol(s.name());
            case List<?> list -> {
                writeArrayHeader(list.size());
                for (Object element : list) {
                    write(element);
                }
            }
            case Ruby.HashWithDefault h -> {
                objects++;
                writeByte('}');
                writeHashEntries(h.entries());
                write(h.defaultValue());
            }
            case Map<?, ?> map -> {
                objects++;
                writeByte('{');
                writeHashEntries(map);
            }
            case Ruby.RObject o -> {
                objects++;
                writeByte('o');
                writeSymbol(o.className());
                writeIvars(o.ivars());
            }
            case Ruby.UserMarshal u -> {
                objects++;
                writeByte('U');
                writeSymbol(u.className());
                write(u.data());
            }
            case Ruby.UserDefined u -> {
                boolean ivars = !u.ivars().isEmpty();
                if (ivars) {
                    writeByte('I');
                }
                writeByte('u');
                writeSymbol(u.className());
                writeBytes(u.data());
                if (ivars) {
                    writeIvars(u.ivars());
                }
                objects++; // Marshal.load registers a _load object after its ivars
            }
            case Ruby.Struct s -> {
                objects++;
                writeByte('S');
                writeSymbol(s.className());
                writeLong(s.members().size());
                for (Map.Entry<String, Object> member : s.members().entrySet()) {
                    writeSymbol(member.getKey());
                    write(member.getValue());
                }
            }
            case Ruby.Regexp r -> {
                objects++;
                writeByte('I');
                writeByte('/');
                writeBytes(r.source().getBytes(StandardCharsets.UTF_8));
                writeByte(r.options());
                writeLong(1);
                writeSymbol("E");
                writeByte('T');
            }
            case Ruby.ClassRef c -> {
                objects++;
                writeByte(c.module() ? 'm' : 'c');
                writeBytes(c.name().getBytes(StandardCharsets.UTF_8));
            }
            default -> throw new IllegalArgumentException("cannot marshal " + value.getClass().getName());
        }
    }

    private void writeHashEntries(Map<?, ?> map) {
        writeLong(map.size());
        for (Map.Entry<?, ?> e : map.entrySet()) {
            write(e.getKey());
            write(e.getValue());
        }
    }

    private void writeIvars(Map<String, Object> ivars) {
        writeLong(ivars.size());
        for (Map.Entry<String, Object> e : ivars.entrySet()) {
            writeSymbol(e.getKey());
            write(e.getValue());
        }
    }

    /** A UTF-8 string: {@code I"<bytes>} plus the {@code E: true} encoding ivar, exactly as Ruby writes one. */
    private void writeString(String s) {
        if (sharedStrings != null) {
            Long index = sharedStrings.get(s);
            if (index != null) {
                writeByte('@');
                writeLong(index);
                return;
            }
            if (sharedStrings.size() >= SHARED_STRINGS) {
                sharedStrings.clear();
            }
            sharedStrings.put(s, objects);
        }
        objects++;
        writeByte('I');
        writeByte('"');
        writeBytes(s.getBytes(StandardCharsets.UTF_8));
        writeLong(1);
        writeSymbol("E");
        writeByte('T');
    }

    private void writeSymbol(String name) {
        Integer index = symbols.get(name);
        if (index != null) {
            writeByte(';');
            writeLong(index);
            return;
        }
        symbols.put(name, symbolBase + symbols.size());
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        boolean ascii = true;
        for (byte b : bytes) {
            ascii &= b >= 0;
        }
        if (ascii) {
            writeByte(':');
            writeBytes(bytes);
        } else {
            writeByte('I');
            writeByte(':');
            writeBytes(bytes);
            writeLong(1);
            writeSymbol("E");
            writeByte('T');
        }
    }

    private void writeInteger(long value) {
        if (value >= FIXNUM_MIN && value <= FIXNUM_MAX) {
            writeByte('i');
            writeLong(value);
        } else {
            writeBigInteger(BigInteger.valueOf(value));
        }
    }

    private void writeBigInteger(BigInteger value) {
        if (value.bitLength() < 31) {
            writeInteger(value.longValue());
            return;
        }
        objects++;
        writeByte('l');
        writeByte(value.signum() < 0 ? '-' : '+');
        byte[] magnitude = value.abs().toByteArray(); // big-endian, may have a leading zero byte
        int start = magnitude[0] == 0 ? 1 : 0;
        int length = magnitude.length - start;
        int shorts = (length + 1) / 2;
        writeLong(shorts);
        for (int i = 0; i < shorts * 2; i++) {
            int index = magnitude.length - 1 - i;
            writeByte(index >= start ? magnitude[index] & 0xff : 0);
        }
    }

    private void writeFloat(double d) {
        String text;
        if (Double.isNaN(d)) {
            text = "nan";
        } else if (Double.isInfinite(d)) {
            text = d > 0 ? "inf" : "-inf";
        } else if (d == 0.0) {
            text = (1.0 / d) < 0 ? "-0" : "0";
        } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            text = Long.toString((long) d);
        } else {
            text = Double.toString(d);
        }
        objects++;
        writeByte('f');
        writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }

    /** Ruby's variable-length integer ({@code w_long}). */
    void writeLong(long x) {
        if (x == 0) {
            writeByte(0);
            return;
        }
        if (x > 0 && x < 123) {
            writeByte((int) (x + 5));
            return;
        }
        if (x < 0 && x > -124) {
            writeByte((int) ((x - 5) & 0xff));
            return;
        }
        byte[] buf = new byte[9];
        int i;
        for (i = 1; i < 9; i++) {
            buf[i] = (byte) (x & 0xff);
            x >>= 8;
            if (x == 0) {
                buf[0] = (byte) i;
                break;
            }
            if (x == -1) {
                buf[0] = (byte) -i;
                break;
            }
        }
        write(buf, 0, i + 1);
    }

    private void writeBytes(byte[] bytes) {
        writeLong(bytes.length);
        write(bytes, 0, bytes.length);
    }

    private void writeByte(int b) {
        try {
            out.write(b);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(byte[] bytes, int offset, int length) {
        try {
            out.write(bytes, offset, length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
