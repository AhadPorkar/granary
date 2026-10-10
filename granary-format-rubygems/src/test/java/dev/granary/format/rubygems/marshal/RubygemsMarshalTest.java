package dev.granary.format.rubygems.marshal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Marshal reader and writer against bytes produced by Ruby 3.3's {@code Marshal.dump} (the hex strings below were
 * printed by Ruby itself), plus malformed input that must fail with {@link MarshalException} and nothing else.
 */
class RubygemsMarshalTest {

    /**
     * {@code Marshal.dump(["a", :b, :b, 1, -1, 0, 122, 123, -123, -124, 255, 256, 65536, 2**30-1, 2**30, -2**30,
     * -2**30-1, 2**62, -(2**64), nil, true, false, {"k"=>"v", :s=>[1]}, "ünï", :"ü", "a"])}.
     */
    static final String VALUES = "04085b1f49220661063a0645543a06623b06690669fa6900697f69017b698069ff846901ff69020001"
            + "69030000016904ffffff3f6c2b070000004069fc000000c06c2d07010000406c2b0900000000000000406c2d0a00000000000000"
            + "0001003054467b074922066b063b005449220676063b00543a06735b06690649220ac3bc6ec3af063b0054493a07c3bc063b0054"
            + "49220661063b0054";
    /** {@code Marshal.dump(Gem::Version.new("1.0"))}. */
    static final String VERSION = "0408553a1147656d3a3a56657273696f6e5b06492208312e30063a064554";
    /** {@code Marshal.dump(Gem::Requirement.new(">= 1.0", "< 2"))}. */
    static final String REQUIREMENT = "0408553a1547656d3a3a526571756972656d656e745b065b075b074922073e3d063a064554553a1147"
            + "656d3a3a56657273696f6e5b06492208312e30063b06545b074922063c063b0654553b075b0649220632063b0654";
    /** {@code Marshal.dump(Time.utc(2026, 10, 10))}. */
    static final String TIME = "040849753a0954696d650d40a51fc000000000063a097a6f6e65492208555443063a064546";
    /** {@code Marshal.dump("\xff\x00".b)}. */
    static final String BINARY = "04082207ff00";
    /** {@code Marshal.dump([1.5, -0.25, 0.0, 100.0])}. */
    static final String FLOATS = "04085b096608312e35660a2d302e32356606306608316532";
    /** {@code Marshal.dump([[[[]]], {}, [nil]])}. */
    static final String NESTED = "04085b085b065b065b007b005b0630";
    /** {@code s = "shared"; Marshal.dump([s, s, [s], :x, :x])}. */
    static final String LINKS = "04085b0a49220b736861726564063a06455440065b0640063a06783b06";

    static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    static List<Object> values() {
        List<Object> values = new ArrayList<>();
        values.add("a");
        values.add(Ruby.symbol("b"));
        values.add(Ruby.symbol("b"));
        values.add(1);
        values.add(-1);
        values.add(0);
        values.add(122);
        values.add(123);
        values.add(-123);
        values.add(-124);
        values.add(255);
        values.add(256);
        values.add(65536);
        values.add((1L << 30) - 1);
        values.add(1L << 30);
        values.add(-(1L << 30));
        values.add(-(1L << 30) - 1);
        values.add(1L << 62);
        values.add(BigInteger.TWO.pow(64).negate());
        values.add(null);
        values.add(true);
        values.add(false);
        Map<Object, Object> hash = new LinkedHashMap<>();
        hash.put("k", "v");
        hash.put(Ruby.symbol("s"), List.of(1L));
        values.add(hash);
        values.add("ünï");
        values.add(Ruby.symbol("ü"));
        values.add("a");
        return values;
    }

    @Test
    void writesWhatRubyWrites() {
        assertThat(hex(MarshalWriter.dump(values()))).isEqualTo(VALUES);
        assertThat(hex(MarshalWriter.dump(Ruby.gemVersion("1.0")))).isEqualTo(VERSION);
        assertThat(hex(MarshalWriter.dump(new byte[] {(byte) 0xff, 0}))).isEqualTo(BINARY);
        assertThat(hex(MarshalWriter.dump(List.of(List.of(List.of(List.of())), Map.of(), java.util.Arrays.asList((Object) null)))))
                .isEqualTo(NESTED);
    }

    @Test
    void readsWhatRubyWrites() {
        Object read = MarshalReader.load(hex(VALUES));
        List<Object> expected = new ArrayList<>();
        for (Object v : values()) {
            expected.add(v instanceof Integer i ? Long.valueOf(i) : v);
        }
        assertThat(read).isEqualTo(expected);
    }

    @Test
    void roundTripsEveryValue() {
        assertThat(MarshalReader.load(MarshalWriter.dump(values()))).isEqualTo(MarshalReader.load(hex(VALUES)));
    }

    @Test
    void readsGemVersionAndRequirement() {
        assertThat(MarshalReader.load(hex(VERSION))).isEqualTo(new Ruby.UserMarshal("Gem::Version", List.of("1.0")));
        Object requirement = MarshalReader.load(hex(REQUIREMENT));
        assertThat(requirement).isInstanceOf(Ruby.UserMarshal.class);
        Ruby.UserMarshal u = (Ruby.UserMarshal) requirement;
        assertThat(u.className()).isEqualTo("Gem::Requirement");
        assertThat(u.data()).isEqualTo(List.of(List.of(
                List.of(">=", Ruby.gemVersion("1.0")),
                List.of("<", Ruby.gemVersion("2")))));
    }

    @Test
    void readsTimeWithZone() {
        Object time = MarshalReader.load(hex(TIME));
        assertThat(time).isInstanceOf(Ruby.UserDefined.class);
        Ruby.UserDefined u = (Ruby.UserDefined) time;
        assertThat(u.className()).isEqualTo("Time");
        assertThat(u.data()).hasSize(8);
        assertThat(u.ivars()).containsEntry("zone", "UTC");
    }

    @Test
    void readsBinaryStringsFloatsAndLinks() {
        assertThat(MarshalReader.load(hex(BINARY))).isEqualTo("ÿ\u0000");
        assertThat(MarshalReader.load(hex(FLOATS))).isEqualTo(List.of(1.5, -0.25, 0.0, 100.0));
        assertThat(MarshalReader.load(hex(NESTED))).isEqualTo(
                List.of(List.of(List.of(List.of())), Map.of(), java.util.Arrays.asList((Object) null)));
        assertThat(MarshalReader.load(hex(LINKS))).isEqualTo(
                List.of("shared", "shared", List.of("shared"), Ruby.symbol("x"), Ruby.symbol("x")));
    }

    @Test
    void sharingWriterLinksRepeatedStringsLikeRuby() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MarshalWriter writer = new MarshalWriter(out).sharingStrings();
        writer.writeHeader();
        writer.write(List.of("shared", "shared", List.of("shared"), Ruby.symbol("x"), Ruby.symbol("x")));
        assertThat(hex(out.toByteArray())).isEqualTo(LINKS);
    }

    @Test
    void continuingWriterNumbersLinksAfterTheStreamItExtends() {
        // the first part: [ "a", :s ] written by one writer, two more elements by a continuing one
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MarshalWriter first = new MarshalWriter(out);
        first.writeHeader();
        first.writeArrayHeader(4);
        first.write("a");
        first.write(Ruby.symbol("s"));
        MarshalWriter rest = MarshalWriter.continuing(out, first.symbolCount(), first.objectCount()).sharingStrings();
        rest.write(Ruby.symbol("s"));
        rest.write(List.of("b", "b"));
        Object read = MarshalReader.load(out.toByteArray());
        assertThat(read).isEqualTo(List.of("a", Ruby.symbol("s"), Ruby.symbol("s"), List.of("b", "b")));
    }

    @Test
    void objectsAndUserDefinedRoundTrip() {
        Map<String, Object> ivars = new LinkedHashMap<>();
        ivars.put("@name", "rake");
        ivars.put("@type", Ruby.symbol("runtime"));
        ivars.put("@prerelease", false);
        Ruby.RObject dep = new Ruby.RObject("Gem::Dependency", ivars);
        Ruby.UserDefined spec = new Ruby.UserDefined("Gem::Specification", MarshalWriter.dump(List.of("x")), Map.of());
        Ruby.UserDefined time = new Ruby.UserDefined("Time", new byte[8], Map.of("zone", "UTC"));
        List<Object> value = List.of(dep, spec, time, new Ruby.ClassRef("Gem::Version", false),
                new Ruby.Regexp("^a+$", 0));
        Object read = MarshalReader.load(MarshalWriter.dump(value));
        assertThat(read).isEqualTo(value);
    }

    @Test
    void hashWithDefaultRoundTrips() {
        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put("a", 1L);
        Ruby.HashWithDefault hash = new Ruby.HashWithDefault(entries, 0L);
        assertThat(MarshalReader.load(MarshalWriter.dump(hash))).isEqualTo(hash);
    }

    @Test
    void largeIntegersBecomeBignums() {
        BigInteger huge = BigInteger.TWO.pow(100).add(BigInteger.valueOf(12345));
        List<Object> value = List.of(huge, huge.negate(), Long.MAX_VALUE, Long.MIN_VALUE);
        assertThat(MarshalReader.load(MarshalWriter.dump(value)))
                .isEqualTo(List.of(huge, huge.negate(), Long.MAX_VALUE, Long.MIN_VALUE));
    }

    @Test
    void streamsALargeArrayElementByElement() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MarshalWriter writer = new MarshalWriter(out).sharingStrings();
        writer.writeHeader();
        writer.writeArrayHeader(3000);
        for (int i = 0; i < 3000; i++) {
            writer.write(List.of("gem" + (i % 7), Ruby.gemVersion("1." + i), "ruby"));
        }
        MarshalReader reader = new MarshalReader(new ByteArrayInputStream(out.toByteArray()), true);
        reader.readHeader();
        long length = reader.readArrayHeader();
        assertThat(length).isEqualTo(3000);
        for (int i = 0; i < 3000; i++) {
            Object element = reader.readObject();
            assertThat(element).isEqualTo(List.of("gem" + (i % 7), Ruby.gemVersion("1." + i), "ruby"));
        }
        assertThat(reader.atEnd()).isTrue();
        assertThat(reader.objectCount()).isEqualTo(writer.objectCount());
        assertThat(reader.symbolCount()).isEqualTo(writer.symbolCount());
        assertThat(reader.position()).isEqualTo(out.size());
    }

    @Test
    void leanReaderRefusesLinksToArrays() {
        // [a = [], a]
        byte[] bytes = hex("04085b075b0040" + "06");
        assertThatThrownBy(() -> {
            MarshalReader reader = new MarshalReader(new ByteArrayInputStream(bytes), true);
            reader.readHeader();
            reader.readObject();
        }).isInstanceOf(MarshalException.class);
        assertThat(MarshalReader.load(bytes)).isEqualTo(List.of(List.of(), List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",                 // nothing
            "0409",             // version 4.9
            "0308",             // version 3.8
            "0408",             // header only
            "04085b",           // array without length
            "04085b0a",         // array shorter than announced
            "0408220a6162",     // string shorter than announced
            "04083b00",         // symbol link before any symbol
            "04084006",         // object link to nothing
            "04087a",           // unknown type
            "04086c2b",         // bignum without length
            "0408660431",       // float shorter than announced
            "04085bfcffffff7f", // absurd array length
            "040822fcffffff7f", // absurd string length
            "04084922066106",   // ivar count without ivars
    })
    void malformedInputFailsCleanly(String hex) {
        assertThatThrownBy(() -> MarshalReader.load(hex(hex))).isInstanceOf(MarshalException.class);
    }

    @Test
    void deepNestingIsRefused() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(hex("0408"));
        for (int i = 0; i < 10_000; i++) {
            out.writeBytes(hex("5b06"));
        }
        out.writeBytes(hex("30"));
        assertThatThrownBy(() -> MarshalReader.load(out.toByteArray()))
                .isInstanceOf(MarshalException.class)
                .hasMessageContaining("nesting");
    }

    @Test
    void writerRefusesUnknownTypes() {
        assertThatThrownBy(() -> MarshalWriter.dump(new Object())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gemVersionStringReadsBothShapes() {
        assertThat(Ruby.gemVersionString(Ruby.gemVersion("2.1"))).isEqualTo("2.1");
        assertThat(Ruby.gemVersionString(new Ruby.RObject("Gem::Version", new LinkedHashMap<>(Map.of("@version", "0.9")))))
                .isEqualTo("0.9");
        assertThat(Ruby.gemVersionString("1.0")).isNull();
        assertThat(Ruby.gemVersionString(new Ruby.UserMarshal("Gem::Version", List.of()))).isNull();
    }
}
