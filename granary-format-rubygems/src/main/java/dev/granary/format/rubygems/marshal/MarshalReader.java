package dev.granary.format.rubygems.marshal;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Ruby Marshal 4.x streams into the {@link Ruby} object model, following {@code marshal.c} exactly where it
 * matters: the variable-length integer encoding, symbol links ({@code ;}) and object links ({@code @}) numbered in
 * the order Ruby registers objects (arrays, hashes, plain and {@code U} objects before their contents, {@code u}
 * objects after their instance variables), and the {@code I} wrapper that carries string encodings.
 *
 * <p>Input is untrusted: every length is checked against sane limits, nesting depth is bounded, and anything
 * malformed ends in a {@link MarshalException}, never in an unchecked runtime error or an out-of-memory condition.
 *
 * <p>In <em>lean</em> mode (for the 1.7-million-entry {@code specs.4.8} of rubygems.org) the reader does not keep
 * arrays in its link table, interns strings and {@code Gem::Version} objects, and so keeps memory proportional to
 * the number of distinct values instead of the number of entries. A link to an array is then refused.
 */
public final class MarshalReader {

    /** Deepest nesting accepted; gem metadata never goes beyond a handful of levels. */
    static final int MAX_DEPTH = 200;
    /** Longest single string or byte sequence accepted. */
    static final int MAX_BYTES = 64 * 1024 * 1024;

    private static final Object PLACEHOLDER = new Object();

    private final InputStream in;
    private final boolean lean;
    private final List<String> symbols = new ArrayList<>();
    private final ObjectTable objects = new ObjectTable();
    private final Map<String, String> internedStrings;
    private final Map<String, Ruby.UserMarshal> internedVersions;
    private long position;
    private int depth;

    public MarshalReader(InputStream in) {
        this(in, false);
    }

    public MarshalReader(InputStream in, boolean lean) {
        this.in = in instanceof BufferedInputStream || in instanceof ByteArrayInputStream ? in : new BufferedInputStream(in, 64 * 1024);
        this.lean = lean;
        this.internedStrings = lean ? new HashMap<>() : null;
        this.internedVersions = lean ? new HashMap<>() : null;
    }

    /** {@code Marshal.load(bytes)}. */
    public static Object load(byte[] bytes) {
        MarshalReader reader = new MarshalReader(new ByteArrayInputStream(bytes));
        reader.readHeader();
        return reader.readObject();
    }

    /** Symbols defined so far; a writer continuing this stream numbers its own symbols after them. */
    public int symbolCount() {
        return symbols.size();
    }

    /** Objects registered so far (the link table size). */
    public int objectCount() {
        return objects.size();
    }

    /** Bytes consumed so far. */
    public long position() {
        return position;
    }

    /** The {@code 04 08} version header; any 4.x minor version up to 8 is accepted. */
    public void readHeader() {
        int major = readByte();
        int minor = readByte();
        if (major != 4 || minor > 8) {
            throw new MarshalException("not a Ruby Marshal 4.8 stream (version " + major + "." + minor + ")");
        }
    }

    /**
     * Reads the start of an array, registers it, and returns its length; the caller then reads that many elements
     * with {@link #readObject()}. Lets a huge top-level array be streamed instead of materialised.
     */
    public long readArrayHeader() {
        int type = readByte();
        if (type != '[') {
            throw new MarshalException("expected an array but found type '" + (char) type + "'");
        }
        long length = readLong();
        if (length < 0) {
            throw new MarshalException("negative array length");
        }
        objects.add(PLACEHOLDER);
        return length;
    }

    public Object readObject() {
        return readObject0(false).value;
    }

    /** Whether the stream has no more bytes. */
    public boolean atEnd() {
        try {
            in.mark(1);
            int b = in.read();
            in.reset();
            return b < 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- objects ---------------------------------------------------------------------------------------

    /**
     * A value read from the stream.
     *
     * @param consumedIvars whether the value already read the instance variables of an enclosing {@code I}
     *                      (symbols, regexps and {@code _dump} objects do; everything else leaves them to the wrapper)
     * @param index         its slot in the link table, or -1
     */
    private record Read(Object value, boolean consumedIvars, int index) {
    }

    private Read readObject0(boolean ivarWrapped) {
        if (++depth > MAX_DEPTH) {
            throw new MarshalException("nesting deeper than " + MAX_DEPTH + " levels");
        }
        try {
            int type = readByte();
            return switch (type) {
                case '0' -> new Read(null, false, -1);
                case 'T' -> new Read(Boolean.TRUE, false, -1);
                case 'F' -> new Read(Boolean.FALSE, false, -1);
                case 'i' -> new Read(readLong(), false, -1);
                case ':' -> new Read(readSymbolBody(ivarWrapped), true, -1);
                case ';' -> new Read(new Ruby.Symbol(symbolAt(readLong())), false, -1);
                case '@' -> {
                    long index = readLong();
                    Object linked = objects.get(index);
                    if (linked == PLACEHOLDER || linked instanceof byte[]) {
                        throw new MarshalException("link to object " + index + " that cannot be resolved here");
                    }
                    yield new Read(linked, false, -1);
                }
                case 'I' -> {
                    Read inner = readObject0(true);
                    if (inner.consumedIvars) {
                        yield new Read(inner.value, false, inner.index);
                    }
                    Map<String, Object> ivars = readIvars();
                    Object value = applyIvars(inner.value, ivars);
                    if (inner.index >= 0) {
                        objects.set(inner.index, value);
                    }
                    yield new Read(value, false, inner.index);
                }
                case '"' -> {
                    byte[] bytes = readBytes();
                    if (ivarWrapped) {
                        // decoded once the wrapper has read the encoding
                        int index = objects.add(bytes);
                        yield new Read(bytes, false, index);
                    }
                    String s = intern(decode(bytes, null));
                    int index = objects.add(s);
                    yield new Read(s, false, index);
                }
                case '[' -> {
                    long length = readLong();
                    checkCount(length);
                    List<Object> list = new ArrayList<>((int) Math.min(length, 1024));
                    int index = objects.add(lean ? PLACEHOLDER : list);
                    for (long i = 0; i < length; i++) {
                        list.add(readObject());
                    }
                    yield new Read(list, false, index);
                }
                case '{', '}' -> {
                    long length = readLong();
                    checkCount(length);
                    Map<Object, Object> map = new LinkedHashMap<>();
                    int index = objects.add(map);
                    for (long i = 0; i < length; i++) {
                        Object key = readObject();
                        map.put(hashKey(key), readObject());
                    }
                    if (type == '}') {
                        Ruby.HashWithDefault hash = new Ruby.HashWithDefault(map, readObject());
                        objects.set(index, hash);
                        yield new Read(hash, false, index);
                    }
                    yield new Read(map, false, index);
                }
                case 'o' -> {
                    String className = readSymbolName();
                    Ruby.RObject object = new Ruby.RObject(className, new LinkedHashMap<>());
                    int index = objects.add(object);
                    object.ivars().putAll(readIvars());
                    yield new Read(object, false, index);
                }
                case 'U' -> {
                    String className = readSymbolName();
                    int index = objects.add(PLACEHOLDER);
                    Object data = readObject();
                    Ruby.UserMarshal value = internVersion(new Ruby.UserMarshal(className, data));
                    objects.set(index, value);
                    yield new Read(value, false, index);
                }
                case 'u' -> {
                    String className = readSymbolName();
                    byte[] data = readBytes();
                    Map<String, Object> ivars = ivarWrapped ? readIvars() : Map.of();
                    Ruby.UserDefined value = new Ruby.UserDefined(className, data, ivars);
                    int index = objects.add(value);
                    yield new Read(value, true, index);
                }
                case 'e' -> {
                    readSymbolName(); // the module the object was extended with; irrelevant here
                    yield readObject0(ivarWrapped);
                }
                case 'C' -> {
                    readSymbolName(); // a subclass of String, Array or Hash; the value is what matters
                    yield readObject0(ivarWrapped);
                }
                case 'S' -> {
                    String className = readSymbolName();
                    long length = readLong();
                    checkCount(length);
                    Map<String, Object> members = new LinkedHashMap<>();
                    Ruby.Struct struct = new Ruby.Struct(className, members);
                    int index = objects.add(struct);
                    for (long i = 0; i < length; i++) {
                        String member = readSymbolName();
                        members.put(member, readObject());
                    }
                    yield new Read(struct, false, index);
                }
                case '/' -> {
                    byte[] source = readBytes();
                    int options = readByte();
                    int index = objects.add(PLACEHOLDER);
                    String encoding = ivarWrapped ? encodingOf(readIvars()) : null;
                    Ruby.Regexp regexp = new Ruby.Regexp(decode(source, encoding), options);
                    objects.set(index, regexp);
                    yield new Read(regexp, true, index);
                }
                case 'f' -> {
                    Double d = parseFloat(readBytes());
                    int index = objects.add(d);
                    yield new Read(d, false, index);
                }
                case 'l' -> {
                    Object big = readBignum();
                    int index = objects.add(big);
                    yield new Read(big, false, index);
                }
                case 'c', 'm', 'M' -> {
                    Ruby.ClassRef ref = new Ruby.ClassRef(decode(readBytes(), null), type != 'c');
                    int index = objects.add(ref);
                    yield new Read(ref, false, index);
                }
                default -> throw new MarshalException("unsupported Marshal type 0x" + Integer.toHexString(type)
                        + " at byte " + (position - 1));
            };
        } finally {
            depth--;
        }
    }

    /** Applies the instance variables of an {@code I} wrapper: string encodings; anything else is dropped. */
    private Object applyIvars(Object value, Map<String, Object> ivars) {
        if (value instanceof byte[] bytes) {
            return intern(decode(bytes, encodingOf(ivars)));
        }
        return value;
    }

    private static String encodingOf(Map<String, Object> ivars) {
        Object e = ivars.get("E");
        if (Boolean.TRUE.equals(e)) {
            return "UTF-8";
        }
        if (Boolean.FALSE.equals(e)) {
            return "US-ASCII";
        }
        Object encoding = ivars.get("encoding");
        return encoding instanceof String s ? s : null;
    }

    private Map<String, Object> readIvars() {
        long count = readLong();
        checkCount(count);
        Map<String, Object> ivars = new LinkedHashMap<>();
        for (long i = 0; i < count; i++) {
            String name = readSymbolName();
            ivars.put(name, readObject());
        }
        return ivars;
    }

    // --- symbols ---------------------------------------------------------------------------------------

    private String readSymbolName() {
        int type = readByte();
        return switch (type) {
            case ':' -> readSymbolBody(false).name();
            case ';' -> symbolAt(readLong());
            case 'I' -> {
                int inner = readByte();
                if (inner != ':') {
                    throw new MarshalException("expected a symbol but found type '" + (char) inner + "'");
                }
                yield readSymbolBody(true).name();
            }
            default -> throw new MarshalException("expected a symbol but found type '" + (char) type + "'");
        };
    }

    /** The bytes of a new symbol, registered before its own encoding ivars are read, as Ruby does. */
    private Ruby.Symbol readSymbolBody(boolean withIvars) {
        byte[] bytes = readBytes();
        int index = symbols.size();
        symbols.add(null);
        String encoding = null;
        if (withIvars) {
            encoding = encodingOf(readIvars());
        }
        String name = intern(decode(bytes, encoding));
        symbols.set(index, name);
        return new Ruby.Symbol(name);
    }

    private String symbolAt(long index) {
        if (index < 0 || index >= symbols.size() || symbols.get((int) index) == null) {
            throw new MarshalException("symbol link " + index + " points nowhere");
        }
        return symbols.get((int) index);
    }

    // --- scalars ---------------------------------------------------------------------------------------

    /** Ruby's {@code r_long}. */
    long readLong() {
        int c = (byte) readByte();
        if (c == 0) {
            return 0;
        }
        if (c > 0) {
            if (c > 4 && c < 128) {
                return c - 5;
            }
            long x = 0;
            for (int i = 0; i < c; i++) {
                x |= (long) readByte() << (8 * i);
            }
            return x;
        }
        if (c > -129 && c < -4) {
            return c + 5;
        }
        int n = -c;
        long x = -1;
        for (int i = 0; i < n; i++) {
            x &= ~(0xffL << (8 * i));
            x |= (long) readByte() << (8 * i);
        }
        return x;
    }

    private Object readBignum() {
        int sign = readByte();
        if (sign != '+' && sign != '-') {
            throw new MarshalException("bad bignum sign");
        }
        long shorts = readLong();
        if (shorts < 0 || shorts > MAX_BYTES / 2) {
            throw new MarshalException("bad bignum length");
        }
        byte[] little = readExactly((int) (shorts * 2));
        byte[] big = new byte[little.length + 1];
        for (int i = 0; i < little.length; i++) {
            big[big.length - 1 - i] = little[i];
        }
        BigInteger value = new BigInteger(big);
        if (sign == '-') {
            value = value.negate();
        }
        return value.bitLength() < 64 ? (Object) value.longValue() : value;
    }

    private static Double parseFloat(byte[] bytes) {
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        String text = new String(bytes, 0, end, StandardCharsets.US_ASCII);
        return switch (text) {
            case "nan" -> Double.NaN;
            case "inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> {
                try {
                    yield Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    throw new MarshalException("bad float '" + text + "'");
                }
            }
        };
    }

    private byte[] readBytes() {
        long length = readLong();
        if (length < 0 || length > MAX_BYTES) {
            throw new MarshalException("bad byte sequence length " + length);
        }
        return readExactly((int) length);
    }

    private byte[] readExactly(int length) {
        try {
            byte[] bytes = in.readNBytes(length);
            if (bytes.length != length) {
                throw new MarshalException("stream ends in the middle of a value", new EOFException());
            }
            position += length;
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int readByte() {
        try {
            int b = in.read();
            if (b < 0) {
                throw new MarshalException("stream ends in the middle of a value", new EOFException());
            }
            position++;
            return b;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void checkCount(long count) {
        if (count < 0 || count > Integer.MAX_VALUE - 16) {
            throw new MarshalException("bad element count " + count);
        }
    }

    /** Ruby strings carry their encoding; anything without a usable one is read as UTF-8, else ISO-8859-1. */
    static String decode(byte[] bytes, String encoding) {
        Charset charset = StandardCharsets.UTF_8;
        if (encoding != null && !encoding.equalsIgnoreCase("UTF-8") && !encoding.equalsIgnoreCase("US-ASCII")) {
            try {
                charset = Charset.forName(encoding);
            } catch (RuntimeException e) {
                charset = StandardCharsets.UTF_8;
            }
        }
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /** Hash keys must have value semantics in Java; byte arrays never reach here because strings are decoded. */
    private static Object hashKey(Object key) {
        return key instanceof byte[] bytes ? Arrays.toString(bytes) : key;
    }

    private String intern(String s) {
        if (internedStrings == null) {
            return s;
        }
        String existing = internedStrings.putIfAbsent(s, s);
        return existing == null ? s : existing;
    }

    private Ruby.UserMarshal internVersion(Ruby.UserMarshal value) {
        if (internedVersions == null) {
            return value;
        }
        String version = Ruby.gemVersionString(value);
        if (version == null) {
            return value;
        }
        Ruby.UserMarshal existing = internedVersions.putIfAbsent(version, value);
        return existing == null ? value : existing;
    }

    /** The link table: a growable array, cheaper than a list of boxed entries for millions of objects. */
    private static final class ObjectTable {
        private Object[] entries = new Object[256];
        private int size;

        int add(Object value) {
            if (size == entries.length) {
                entries = Arrays.copyOf(entries, entries.length * 2);
            }
            entries[size] = value;
            return size++;
        }

        void set(int index, Object value) {
            entries[index] = value;
        }

        Object get(long index) {
            if (index < 0 || index >= size) {
                throw new MarshalException("object link " + index + " points nowhere");
            }
            Object value = entries[(int) index];
            return value;
        }

        int size() {
            return size;
        }
    }
}
