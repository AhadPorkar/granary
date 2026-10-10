package dev.granary.format.hex;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parser for the Erlang term syntax {@code metadata.config} is written in: tuples, lists, maps,
 * binaries ({@code <<"text">>}, {@code <<"ü"/utf8>>}, {@code <<1,2,3>>}), strings, atoms, integers
 * (including {@code 16#FF} and {@code $c}) and floats, with the escape sequences the Erlang scanner
 * accepts. Binaries and strings both become {@link String}; atoms {@code true}/{@code false} become
 * {@link Boolean}; other atoms become {@link Atom}; tuples become {@link Tuple}; maps keep insertion order.
 */
public final class ErlangTerms {

    /** Nesting deeper than this is not metadata, it is an attack on the stack. */
    static final int MAX_DEPTH = 256;

    public record Atom(String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    public record Tuple(List<Object> elements) {
        public Tuple {
            elements = List.copyOf(elements);
        }

        public int size() {
            return elements.size();
        }

        public Object get(int index) {
            return elements.get(index);
        }
    }

    public static final class ParseException extends IllegalArgumentException {
        ParseException(String message) {
            super(message);
        }
    }

    private final String src;
    private int pos;
    private int depth;

    private ErlangTerms(String src) {
        this.src = src;
    }

    /** Parses a sequence of dot-terminated terms, the file format {@code file:consult/1} reads. */
    public static List<Object> parseAll(String text) {
        ErlangTerms parser = new ErlangTerms(text);
        return parser.guarded(() -> {
            List<Object> terms = new ArrayList<>();
            parser.skipWhitespace();
            while (!parser.atEnd()) {
                terms.add(parser.term());
                parser.skipWhitespace();
                parser.expect('.');
                if (!parser.atEnd() && !Character.isWhitespace(parser.peek()) && parser.peek() != '%') {
                    throw parser.error("expected whitespace after the terminating dot");
                }
                parser.skipWhitespace();
            }
            return terms;
        });
    }

    /** Parses exactly one term (an optional trailing dot is accepted). */
    public static Object parseOne(String text) {
        ErlangTerms parser = new ErlangTerms(text);
        return parser.guarded(() -> {
            parser.skipWhitespace();
            Object term = parser.term();
            parser.skipWhitespace();
            if (!parser.atEnd() && parser.peek() == '.') {
                parser.pos++;
                parser.skipWhitespace();
            }
            if (!parser.atEnd()) {
                throw parser.error("unexpected input after the term");
            }
            return term;
        });
    }

    /**
     * Runs a parse so that every malformed input surfaces as a {@link ParseException}: a literal the checks below
     * do not anticipate (a number too large for its context, a code point outside Unicode) must never escape as
     * an unchecked JDK exception and turn a refused upload into a server error.
     */
    private <T> T guarded(java.util.function.Supplier<T> parse) {
        try {
            return parse.get();
        } catch (ParseException e) {
            throw e;
        } catch (IllegalArgumentException | ArithmeticException | IndexOutOfBoundsException e) {
            throw error("malformed term (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** The {@code {Key, Value}.} pairs of a consult file as a map; keys may be binaries, strings or atoms. */
    public static Map<String, Object> consult(String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Object term : parseAll(text)) {
            if (!(term instanceof Tuple tuple) || tuple.size() != 2) {
                throw new ParseException("expected {Key, Value} pairs, got " + describe(term));
            }
            String key = asString(tuple.get(0));
            if (key == null) {
                throw new ParseException("expected a binary, string or atom key, got " + describe(tuple.get(0)));
            }
            if (result.containsKey(key)) {
                throw new ParseException("duplicate key " + key);
            }
            result.put(key, tuple.get(1));
        }
        return result;
    }

    /** Binaries, strings and atoms as text; anything else answers {@code null}. */
    public static String asString(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Atom a) {
            return a.name();
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        return null;
    }

    /** A property list ({@code [{Key, Value}, …]}) or a map with text keys as a map; anything else answers {@code null}. */
    public static Map<String, Object> asProperties(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = asString(e.getKey());
                if (key == null) {
                    return null;
                }
                result.put(key, e.getValue());
            }
            return result;
        }
        if (value instanceof List<?> list) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Object element : list) {
                if (!(element instanceof Tuple tuple) || tuple.size() != 2) {
                    return null;
                }
                String key = asString(tuple.get(0));
                if (key == null) {
                    return null;
                }
                result.put(key, tuple.get(1));
            }
            return result;
        }
        return null;
    }

    static String describe(Object term) {
        if (term == null) {
            return "nothing";
        }
        if (term instanceof Tuple t) {
            return "a tuple of size " + t.size();
        }
        if (term instanceof List<?>) {
            return "a list";
        }
        if (term instanceof Map<?, ?>) {
            return "a map";
        }
        if (term instanceof String) {
            return "a binary";
        }
        return term.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    // --- parser --------------------------------------------------------------------------------

    private Object term() {
        if (++depth > MAX_DEPTH) {
            throw error("term nesting too deep");
        }
        try {
            skipWhitespace();
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            char c = peek();
            if (c == '{') {
                return tuple();
            }
            if (c == '[') {
                return list();
            }
            if (c == '#') {
                return map();
            }
            if (c == '<') {
                return binary();
            }
            if (c == '"') {
                return string();
            }
            if (c == '\'') {
                return new Atom(quoted('\''));
            }
            if (c == '$') {
                pos++;
                return (long) charLiteral();
            }
            if (c == '-' || c == '+' || isDigit(c)) {
                return number();
            }
            if (c >= 'a' && c <= 'z') {
                return atom();
            }
            throw error("unexpected character '" + c + "'");
        } finally {
            depth--;
        }
    }

    private Tuple tuple() {
        expect('{');
        List<Object> elements = new ArrayList<>();
        skipWhitespace();
        if (peekIs('}')) {
            pos++;
            return new Tuple(elements);
        }
        while (true) {
            elements.add(term());
            skipWhitespace();
            if (peekIs(',')) {
                pos++;
                continue;
            }
            expect('}');
            return new Tuple(elements);
        }
    }

    private List<Object> list() {
        expect('[');
        List<Object> elements = new ArrayList<>();
        skipWhitespace();
        if (peekIs(']')) {
            pos++;
            return elements;
        }
        while (true) {
            elements.add(term());
            skipWhitespace();
            if (peekIs(',')) {
                pos++;
                continue;
            }
            if (peekIs('|')) {
                pos++;
                Object tail = term();
                if (tail instanceof List<?> tailList) {
                    elements.addAll(tailList);
                } else {
                    throw error("improper lists are not supported");
                }
                skipWhitespace();
            }
            expect(']');
            return elements;
        }
    }

    private Map<Object, Object> map() {
        expect('#');
        expect('{');
        Map<Object, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (peekIs('}')) {
            pos++;
            return result;
        }
        while (true) {
            Object key = term();
            skipWhitespace();
            if (peekIs('=')) {
                expect('=');
                expect('>');
            } else {
                expect(':');
                expect('=');
            }
            Object value = term();
            result.put(key, value);
            skipWhitespace();
            if (peekIs(',')) {
                pos++;
                continue;
            }
            expect('}');
            return result;
        }
    }

    private String binary() {
        expect('<');
        expect('<');
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        skipWhitespace();
        if (peekIs('>')) {
            expect('>');
            expect('>');
            return "";
        }
        while (true) {
            skipWhitespace();
            if (peekIs('"')) {
                String text = string();
                String type = segmentType();
                appendText(bytes, text, type);
            } else if (peekIs('$')) {
                pos++;
                int ch = charLiteral();
                String type = segmentType();
                appendText(bytes, new String(Character.toChars(codePoint(ch))), type);
            } else {
                Object value = number();
                if (!(value instanceof Long n)) {
                    throw error("only integer segments are supported in binaries");
                }
                String type = segmentType();
                if (type.contains("utf8") || type.contains("utf16") || type.contains("utf32")) {
                    appendText(bytes, new String(Character.toChars(codePoint(n))), type);
                } else {
                    bytes.write((int) (n & 0xFF));
                }
            }
            skipWhitespace();
            if (peekIs(',')) {
                pos++;
                continue;
            }
            expect('>');
            expect('>');
            return decodeBinary(bytes.toByteArray());
        }
    }

    /** The {@code :Size} and {@code /Type-Spec} part of a binary segment; sizes are accepted and ignored. */
    private String segmentType() {
        skipWhitespace();
        if (peekIs(':')) {
            pos++;
            while (!atEnd() && isDigit(peek())) {
                pos++;
            }
            skipWhitespace();
        }
        if (!peekIs('/')) {
            return "";
        }
        pos++;
        int start = pos;
        while (!atEnd() && (Character.isLetterOrDigit(peek()) || peek() == '-' || peek() == ':')) {
            pos++;
        }
        return src.substring(start, pos).toLowerCase(java.util.Locale.ROOT);
    }

    private void appendText(ByteArrayOutputStream bytes, String text, String type) {
        if (type.contains("utf8")) {
            bytes.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        } else if (type.contains("utf16")) {
            bytes.writeBytes(text.getBytes(type.contains("little") ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE));
        } else if (type.contains("utf32")) {
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                bytes.writeBytes(ByteBuffer.allocate(4).putInt(cp).array());
            }
        } else {
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (cp > 255) {
                    throw error("character " + cp + " does not fit in a byte; use /utf8");
                }
                bytes.write(cp);
            }
        }
    }

    /** Binary bytes as text: UTF-8 when they decode cleanly, otherwise Latin-1, the way hex_core falls back. */
    static String decodeBinary(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private String string() {
        String s = quoted('"');
        skipWhitespace();
        while (peekIs('"')) { // adjacent string literals concatenate
            s += quoted('"');
            skipWhitespace();
        }
        return s;
    }

    private String quoted(char quote) {
        expect(quote);
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw error("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == quote) {
                return sb.toString();
            }
            if (c == '\\') {
                sb.appendCodePoint(codePoint(escape()));
            } else {
                sb.append(c);
            }
        }
    }

    private int charLiteral() {
        if (atEnd()) {
            throw error("unterminated character literal");
        }
        char c = src.charAt(pos++);
        if (c == '\\') {
            return escape();
        }
        if (Character.isHighSurrogate(c) && !atEnd()) {
            return Character.toCodePoint(c, src.charAt(pos++));
        }
        return c;
    }

    /** The escape after a backslash, as the Erlang scanner reads it. */
    private int escape() {
        if (atEnd()) {
            throw error("unterminated escape");
        }
        char c = src.charAt(pos++);
        switch (c) {
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            case 'v': return 0x0B;
            case 'b': return '\b';
            case 'f': return '\f';
            case 'e': return 0x1B;
            case 's': return ' ';
            case 'd': return 0x7F;
            case '^': {
                if (atEnd()) {
                    throw error("unterminated control escape");
                }
                return src.charAt(pos++) & 0x1F;
            }
            case 'x': {
                if (peekIs('{')) {
                    pos++;
                    int start = pos;
                    while (!atEnd() && isHex(peek())) {
                        pos++;
                    }
                    if (start == pos || pos - start > 8) {
                        throw error("malformed \\x{…} escape");
                    }
                    long value = Long.parseLong(src.substring(start, pos), 16);
                    if (value > Character.MAX_CODE_POINT) {
                        throw error("\\x{…} escape beyond the last Unicode code point");
                    }
                    expect('}');
                    return (int) value;
                }
                if (pos + 2 > src.length() || !isHex(src.charAt(pos)) || !isHex(src.charAt(pos + 1))) {
                    throw error("malformed \\x escape");
                }
                int value = Integer.parseInt(src.substring(pos, pos + 2), 16);
                pos += 2;
                return value;
            }
            default: {
                if (c >= '0' && c <= '7') {
                    int value = c - '0';
                    for (int i = 0; i < 2 && !atEnd() && peek() >= '0' && peek() <= '7'; i++) {
                        value = value * 8 + (src.charAt(pos++) - '0');
                    }
                    return value;
                }
                return c;
            }
        }
    }

    /** A Unicode scalar value, or a parse error: surrogates and values past U+10FFFF are not characters. */
    private int codePoint(long value) {
        boolean surrogate = value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE;
        if (value < 0 || value > Character.MAX_CODE_POINT || surrogate) {
            throw error("character " + value + " is not a Unicode code point");
        }
        return (int) value;
    }

    private Object atom() {
        int start = pos;
        while (!atEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_' || peek() == '@')) {
            pos++;
        }
        String name = src.substring(start, pos);
        if (name.equals("true")) {
            return Boolean.TRUE;
        }
        if (name.equals("false")) {
            return Boolean.FALSE;
        }
        return new Atom(name);
    }

    private Object number() {
        int start = pos;
        boolean negative = false;
        if (peekIs('-') || peekIs('+')) {
            negative = peek() == '-';
            pos++;
        }
        int digitsStart = pos;
        while (!atEnd() && isDigit(peek())) {
            pos++;
        }
        if (digitsStart == pos) {
            throw error("expected a number");
        }
        if (peekIs('#')) {
            int radix = pos - digitsStart > 2 ? 0 : Integer.parseInt(src.substring(digitsStart, pos));
            if (radix < 2 || radix > 36) {
                throw error("invalid number base " + radix);
            }
            pos++;
            int bodyStart = pos;
            while (!atEnd() && Character.digit(peek(), radix) >= 0) {
                pos++;
            }
            if (bodyStart == pos) {
                throw error("expected digits after the base");
            }
            BigInteger value = new BigInteger(src.substring(bodyStart, pos), radix);
            return narrow(negative ? value.negate() : value);
        }
        if (peekIs('.') && pos + 1 < src.length() && isDigit(src.charAt(pos + 1))) {
            pos++;
            while (!atEnd() && isDigit(peek())) {
                pos++;
            }
            if (peekIs('e') || peekIs('E')) {
                pos++;
                if (peekIs('-') || peekIs('+')) {
                    pos++;
                }
                int expStart = pos;
                while (!atEnd() && isDigit(peek())) {
                    pos++;
                }
                if (expStart == pos) {
                    throw error("malformed float exponent");
                }
            }
            return Double.parseDouble(src.substring(start, pos));
        }
        return narrow(new BigInteger(src.substring(start, pos)));
    }

    private static Object narrow(BigInteger value) {
        return value.bitLength() < 64 ? (Object) value.longValue() : value;
    }

    // --- scanning helpers ----------------------------------------------------------------------

    private void skipWhitespace() {
        while (!atEnd()) {
            char c = peek();
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '%') {
                while (!atEnd() && peek() != '\n') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }

    private void expect(char c) {
        if (atEnd() || peek() != c) {
            throw error("expected '" + c + "'" + (atEnd() ? " at end of input" : " but found '" + peek() + "'"));
        }
        pos++;
    }

    private boolean peekIs(char c) {
        return !atEnd() && peek() == c;
    }

    private char peek() {
        return src.charAt(pos);
    }

    private boolean atEnd() {
        return pos >= src.length();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private ParseException error(String message) {
        return new ParseException(message + " at offset " + pos);
    }
}
