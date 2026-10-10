package dev.granary.format.hex;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The Erlang external term format ({@code term_to_binary}) for the JSON-like values the Hex HTTP API
 * exchanges as {@code application/vnd.hex+erlang}: maps with binary keys, binaries, lists, integers,
 * floats, the atoms {@code true}, {@code false} and {@code nil}. Decoding accepts every tag a client
 * could reasonably send, including compressed terms, and never creates atoms.
 */
public final class Etf {

    static final int VERSION = 131;
    static final int COMPRESSED = 80;
    static final int NEW_FLOAT_EXT = 70;
    static final int BIT_BINARY_EXT = 77;
    static final int SMALL_INTEGER_EXT = 97;
    static final int INTEGER_EXT = 98;
    static final int FLOAT_EXT = 99;
    static final int ATOM_EXT = 100;
    static final int SMALL_TUPLE_EXT = 104;
    static final int LARGE_TUPLE_EXT = 105;
    static final int NIL_EXT = 106;
    static final int STRING_EXT = 107;
    static final int LIST_EXT = 108;
    static final int BINARY_EXT = 109;
    static final int SMALL_BIG_EXT = 110;
    static final int LARGE_BIG_EXT = 111;
    static final int SMALL_ATOM_EXT = 115;
    static final int MAP_EXT = 116;
    static final int ATOM_UTF8_EXT = 118;
    static final int SMALL_ATOM_UTF8_EXT = 119;

    static final int MAX_DEPTH = 256;
    static final int MAX_UNCOMPRESSED = 16 * 1024 * 1024;

    private Etf() {
    }

    public static final class DecodeException extends IllegalArgumentException {
        DecodeException(String message) {
            super(message);
        }
    }

    // --- encoding --------------------------------------------------------------------------------

    public static byte[] encode(Object term) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        out.write(VERSION);
        write(out, term, 0);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Object term, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("term nesting too deep");
        }
        switch (term) {
            case null -> atom(out, "nil");
            case Boolean b -> atom(out, b ? "true" : "false");
            case ErlangTerms.Atom a -> atom(out, a.name());
            case String s -> binary(out, s.getBytes(StandardCharsets.UTF_8));
            case byte[] bytes -> binary(out, bytes);
            case Integer i -> integer(out, i);
            case Long l -> integer(out, l);
            case Short s -> integer(out, s);
            case Byte b -> integer(out, b);
            case BigInteger big -> bigInteger(out, big);
            case Double d -> newFloat(out, d);
            case Float f -> newFloat(out, f);
            case Map<?, ?> map -> {
                out.write(MAP_EXT);
                int32(out, map.size());
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    write(out, e.getKey(), depth + 1);
                    write(out, e.getValue(), depth + 1);
                }
            }
            case ErlangTerms.Tuple tuple -> {
                if (tuple.size() < 256) {
                    out.write(SMALL_TUPLE_EXT);
                    out.write(tuple.size());
                } else {
                    out.write(LARGE_TUPLE_EXT);
                    int32(out, tuple.size());
                }
                for (Object element : tuple.elements()) {
                    write(out, element, depth + 1);
                }
            }
            case Iterable<?> list -> {
                List<Object> elements = new ArrayList<>();
                list.forEach(elements::add);
                if (elements.isEmpty()) {
                    out.write(NIL_EXT);
                } else {
                    out.write(LIST_EXT);
                    int32(out, elements.size());
                    for (Object element : elements) {
                        write(out, element, depth + 1);
                    }
                    out.write(NIL_EXT);
                }
            }
            case Object[] array -> write(out, List.of(array), depth);
            default -> throw new IllegalArgumentException("cannot encode " + term.getClass().getName() + " as an Erlang term");
        }
    }

    private static void atom(ByteArrayOutputStream out, String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 256) {
            out.write(SMALL_ATOM_UTF8_EXT);
            out.write(bytes.length);
        } else {
            out.write(ATOM_UTF8_EXT);
            out.write(bytes.length >>> 8);
            out.write(bytes.length);
        }
        out.writeBytes(bytes);
    }

    private static void binary(ByteArrayOutputStream out, byte[] bytes) {
        out.write(BINARY_EXT);
        int32(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void integer(ByteArrayOutputStream out, long value) {
        if (value >= 0 && value < 256) {
            out.write(SMALL_INTEGER_EXT);
            out.write((int) value);
        } else if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            out.write(INTEGER_EXT);
            int32(out, (int) value);
        } else {
            bigInteger(out, BigInteger.valueOf(value));
        }
    }

    private static void bigInteger(ByteArrayOutputStream out, BigInteger value) {
        if (value.bitLength() < 31) {
            integer(out, value.longValue());
            return;
        }
        byte[] magnitude = value.abs().toByteArray();
        int start = magnitude.length > 1 && magnitude[0] == 0 ? 1 : 0; // drop the sign byte BigInteger adds
        int n = magnitude.length - start;
        if (n < 256) {
            out.write(SMALL_BIG_EXT);
            out.write(n);
        } else {
            out.write(LARGE_BIG_EXT);
            int32(out, n);
        }
        out.write(value.signum() < 0 ? 1 : 0);
        for (int i = magnitude.length - 1; i >= start; i--) { // little-endian digits
            out.write(magnitude[i]);
        }
    }

    private static void newFloat(ByteArrayOutputStream out, double value) {
        out.write(NEW_FLOAT_EXT);
        out.writeBytes(ByteBuffer.allocate(8).putDouble(value).array());
    }

    private static void int32(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    // --- decoding --------------------------------------------------------------------------------

    /** Decodes one term; binaries become {@link String}, maps keep order, {@code nil} becomes {@code null}. */
    public static Object decode(byte[] data) {
        if (data == null || data.length == 0) {
            throw new DecodeException("empty term");
        }
        if ((data[0] & 0xFF) != VERSION) {
            throw new DecodeException("not an external term (version byte " + (data[0] & 0xFF) + ")");
        }
        Decoder decoder = new Decoder(data, 1);
        if (decoder.peek() == COMPRESSED) {
            decoder.pos++;
            long size = decoder.uint32();
            if (size > MAX_UNCOMPRESSED) {
                throw new DecodeException("compressed term too large");
            }
            byte[] inflated = inflate(data, decoder.pos, (int) size);
            decoder = new Decoder(inflated, 0);
        }
        Object term = decoder.term(0);
        if (decoder.pos != decoder.data.length) {
            throw new DecodeException("trailing bytes after the term");
        }
        return term;
    }

    /** A decoded map as text-keyed map, for request bodies; {@code null} when the term is not a map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object term) {
        if (!(term instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = ErlangTerms.asString(e.getKey());
            result.put(key == null ? String.valueOf(e.getKey()) : key, e.getValue());
        }
        return result;
    }

    private static byte[] inflate(byte[] data, int offset, int expected) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, data.length - offset);
            byte[] out = new byte[expected];
            int total = 0;
            while (total < expected && !inflater.finished()) {
                int n = inflater.inflate(out, total, expected - total);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                total += n;
            }
            if (total != expected) {
                throw new DecodeException("compressed term is truncated");
            }
            return out;
        } catch (DataFormatException e) {
            throw new DecodeException("compressed term is corrupt");
        } finally {
            inflater.end();
        }
    }

    private static final class Decoder {
        private final byte[] data;
        private int pos;

        Decoder(byte[] data, int pos) {
            this.data = data;
            this.pos = pos;
        }

        int peek() {
            need(1);
            return data[pos] & 0xFF;
        }

        Object term(int depth) {
            if (depth > MAX_DEPTH) {
                throw new DecodeException("term nesting too deep");
            }
            int tag = uint8();
            return switch (tag) {
                case SMALL_INTEGER_EXT -> (long) uint8();
                case INTEGER_EXT -> (long) (int) uint32();
                case NEW_FLOAT_EXT -> {
                    need(8);
                    double d = ByteBuffer.wrap(data, pos, 8).getDouble();
                    pos += 8;
                    yield d;
                }
                case FLOAT_EXT -> {
                    String text = new String(bytes(31), StandardCharsets.US_ASCII).trim();
                    try {
                        yield Double.parseDouble(text.replace("\0", ""));
                    } catch (NumberFormatException e) {
                        throw new DecodeException("malformed float");
                    }
                }
                case ATOM_EXT -> atom(bytes((int) uint16()), StandardCharsets.ISO_8859_1);
                case SMALL_ATOM_EXT -> atom(bytes(uint8()), StandardCharsets.ISO_8859_1);
                case ATOM_UTF8_EXT -> atom(bytes((int) uint16()), StandardCharsets.UTF_8);
                case SMALL_ATOM_UTF8_EXT -> atom(bytes(uint8()), StandardCharsets.UTF_8);
                case SMALL_TUPLE_EXT -> tuple(uint8(), depth);
                case LARGE_TUPLE_EXT -> tuple(checkedCount(uint32()), depth);
                case NIL_EXT -> new ArrayList<>();
                case STRING_EXT -> {
                    byte[] chars = bytes((int) uint16());
                    List<Object> list = new ArrayList<>(chars.length);
                    for (byte b : chars) {
                        list.add((long) (b & 0xFF));
                    }
                    yield list;
                }
                case LIST_EXT -> {
                    int count = checkedCount(uint32());
                    List<Object> list = new ArrayList<>(Math.min(count, 1024));
                    for (int i = 0; i < count; i++) {
                        list.add(term(depth + 1));
                    }
                    Object tail = term(depth + 1);
                    if (tail instanceof List<?> tailList) {
                        list.addAll(tailList);
                    } else {
                        throw new DecodeException("improper lists are not supported");
                    }
                    yield list;
                }
                case BINARY_EXT -> ErlangTerms.decodeBinary(bytes(checkedCount(uint32())));
                case BIT_BINARY_EXT -> {
                    int length = checkedCount(uint32());
                    uint8(); // bits used in the last byte
                    yield ErlangTerms.decodeBinary(bytes(length));
                }
                case SMALL_BIG_EXT -> big(uint8());
                case LARGE_BIG_EXT -> big(checkedCount(uint32()));
                case MAP_EXT -> {
                    int count = checkedCount(uint32());
                    Map<Object, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < count; i++) {
                        Object key = term(depth + 1);
                        Object value = term(depth + 1);
                        map.put(key, value);
                    }
                    yield map;
                }
                default -> throw new DecodeException("unsupported term tag " + tag);
            };
        }

        private Object atom(byte[] raw, java.nio.charset.Charset charset) {
            String name = new String(raw, charset);
            return switch (name) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "nil" -> null;
                default -> new ErlangTerms.Atom(name);
            };
        }

        private ErlangTerms.Tuple tuple(int arity, int depth) {
            List<Object> elements = new ArrayList<>(Math.min(arity, 1024));
            for (int i = 0; i < arity; i++) {
                elements.add(term(depth + 1));
            }
            return new ErlangTerms.Tuple(elements);
        }

        private Object big(int n) {
            int sign = uint8();
            byte[] little = bytes(n);
            byte[] bigEndian = new byte[n];
            for (int i = 0; i < n; i++) {
                bigEndian[i] = little[n - 1 - i];
            }
            BigInteger value = new BigInteger(1, bigEndian);
            if (sign != 0) {
                value = value.negate();
            }
            return value.bitLength() < 64 ? (Object) value.longValue() : value;
        }

        private int checkedCount(long count) {
            if (count < 0 || count > data.length - pos) {
                throw new DecodeException("length " + count + " exceeds the term");
            }
            return (int) count;
        }

        private int uint8() {
            need(1);
            return data[pos++] & 0xFF;
        }

        private long uint16() {
            need(2);
            long v = ((data[pos] & 0xFFL) << 8) | (data[pos + 1] & 0xFFL);
            pos += 2;
            return v;
        }

        private long uint32() {
            need(4);
            long v = ((data[pos] & 0xFFL) << 24) | ((data[pos + 1] & 0xFFL) << 16)
                    | ((data[pos + 2] & 0xFFL) << 8) | (data[pos + 3] & 0xFFL);
            pos += 4;
            return v;
        }

        private byte[] bytes(int length) {
            need(length);
            byte[] out = new byte[length];
            System.arraycopy(data, pos, out, 0, length);
            pos += length;
            return out;
        }

        private void need(int count) {
            if (count < 0 || pos + count > data.length) {
                throw new DecodeException("truncated term");
            }
        }
    }
}
