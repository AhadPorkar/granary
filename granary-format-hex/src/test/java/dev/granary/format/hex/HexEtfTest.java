package dev.granary.format.hex;

import static dev.granary.format.hex.HexTestSupport.golden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The external term format against bytes {@code :erlang.term_to_binary/1,2} produced. */
class HexEtfTest {

    @Test
    void encodesTheErrorShapeExactlyLikeTermToBinary() {
        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("version", "already exists");
        Map<String, Object> body = new LinkedHashMap<>(); // Erlang writes small maps in key order
        body.put("errors", errors);
        body.put("message", "Validation error(s)");
        body.put("status", 422);
        assertThat(Etf.encode(body)).isEqualTo(golden("etf-error.bin"));
    }

    @Test
    void encodesTheRetireBodyMixSends() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "use 1.0.0");
        body.put("reason", "security");
        assertThat(Etf.encode(body)).isEqualTo(golden("etf-retire.bin"));
        assertThat(Etf.asMap(Etf.decode(golden("etf-retire.bin")))).containsEntry("reason", "security")
                .containsEntry("message", "use 1.0.0");
    }

    @Test
    void encodesListsOfMaps() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("inserted_at", "2024-01-01T00:00:00Z");
        entry.put("name", "acme_lib");
        assertThat(Etf.encode(List.of(entry))).isEqualTo(golden("etf-list.bin"));
    }

    @Test
    void decodesEveryKindOfTermTermToBinaryWrites() {
        @SuppressWarnings("unchecked")
        List<Object> terms = (List<Object>) Etf.decode(golden("etf-mixed.bin"));
        List<Object> expected = new ArrayList<>(List.of(0L, 255L, 256L, -1L, 2_147_483_647L, 2_147_483_648L, -2_147_483_649L,
                new BigInteger("12345678901234567890"), 1.5, true, false));
        expected.add(null);
        expected.add(new ErlangTerms.Atom("some_atom"));
        expected.add("üñî");
        expected.add(List.of());
        expected.add(new ErlangTerms.Tuple(List.of(1L, "a")));
        expected.add("\u0000ÿ"); // not UTF-8: read as Latin-1 the way hex_core falls back
        expected.add(List.of(97L, 98L, 99L)); // a charlist arrives as STRING_EXT
        assertThat(terms).containsExactlyElementsOf(expected);
    }

    @Test
    void decodesCompressedTerms() {
        Map<String, Object> map = Etf.asMap(Etf.decode(golden("etf-compressed.bin")));
        assertThat(map).containsOnlyKeys("data");
        assertThat((String) map.get("data")).hasSize(1000).matches("a+");
    }

    @Test
    void roundTripsWhatTheApiAnswers() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "acme_lib");
        body.put("html_url", null);
        body.put("has_docs", true);
        body.put("downloads", Map.of("all", 12L));
        body.put("checksum", "ab".repeat(32));
        body.put("size", Long.MAX_VALUE);
        body.put("negative", Long.MIN_VALUE);
        body.put("huge", new BigInteger("-123456789012345678901234567890"));
        body.put("ratio", 0.25);
        body.put("float", 1.5f);
        body.put("emoji", "✓ ünïcode 😀");
        body.put("tags", List.of("a", "b"));
        body.put("tuple", new ErlangTerms.Tuple(List.of(new ErlangTerms.Atom("ok"), 1)));
        body.put("array", new Object[] {1, "x"});
        body.put("bytes", new byte[] {1, 2, 3});
        body.put("short", (short) -5);
        body.put("byte", (byte) 7);
        Map<String, Object> decoded = Etf.asMap(Etf.decode(Etf.encode(body)));
        assertThat(decoded.get("name")).isEqualTo("acme_lib");
        assertThat(decoded).containsKey("html_url");
        assertThat(decoded.get("html_url")).isNull();
        assertThat(decoded.get("has_docs")).isEqualTo(true);
        assertThat(Etf.asMap(decoded.get("downloads"))).containsEntry("all", 12L);
        assertThat(decoded.get("size")).isEqualTo(Long.MAX_VALUE);
        assertThat(decoded.get("negative")).isEqualTo(Long.MIN_VALUE);
        assertThat(decoded.get("huge")).isEqualTo(new BigInteger("-123456789012345678901234567890"));
        assertThat(decoded.get("ratio")).isEqualTo(0.25);
        assertThat(decoded.get("float")).isEqualTo(1.5);
        assertThat(decoded.get("emoji")).isEqualTo("✓ ünïcode 😀");
        assertThat(decoded.get("tags")).isEqualTo(List.of("a", "b"));
        assertThat(decoded.get("tuple")).isEqualTo(new ErlangTerms.Tuple(List.of(new ErlangTerms.Atom("ok"), 1L)));
        assertThat(decoded.get("array")).isEqualTo(List.of(1L, "x"));
        assertThat(decoded.get("bytes")).isEqualTo("\u0001\u0002\u0003");
        assertThat(decoded.get("short")).isEqualTo(-5L);
        assertThat(decoded.get("byte")).isEqualTo(7L);
    }

    @Test
    void integersUseTheSmallestEncoding() {
        assertThat(Etf.encode(5)).containsExactly(131, 97, 5);
        assertThat(Etf.encode(-5)).containsExactly(131, 98, 0xFF, 0xFF, 0xFF, 0xFB);
        assertThat(Etf.encode(List.of())).containsExactly(131, 106);
        assertThat(Etf.encode(true)).containsExactly(131, 119, 4, 't', 'r', 'u', 'e');
        assertThat(Etf.encode(BigInteger.TEN)).containsExactly(131, 97, 10);
        assertThat(Etf.encode(BigInteger.ONE.shiftLeft(40))).containsExactly(131, 110, 6, 0, 0, 0, 0, 0, 0, 1);
    }

    @Test
    void asMapOnlyAcceptsMaps() {
        assertThat(Etf.asMap(List.of())).isNull();
        assertThat(Etf.asMap("x")).isNull();
        Map<Object, Object> atomKeys = new LinkedHashMap<>();
        atomKeys.put(new ErlangTerms.Atom("reason"), "security");
        atomKeys.put(7L, "seven");
        assertThat(Etf.asMap(atomKeys)).containsEntry("reason", "security").containsEntry("7", "seven");
    }

    @Test
    void refusesWhatIsNotATerm() {
        assertThatThrownBy(() -> Etf.decode(new byte[0])).isInstanceOf(Etf.DecodeException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> Etf.decode(null)).isInstanceOf(Etf.DecodeException.class);
        assertThatThrownBy(() -> Etf.decode(new byte[] {'{', '}'})).isInstanceOf(Etf.DecodeException.class)
                .hasMessageContaining("version byte");
        assertThatThrownBy(() -> Etf.decode(new byte[] {(byte) 131, 109, 0, 0, 0, 9, 'a'}))
                .isInstanceOf(Etf.DecodeException.class);
        assertThatThrownBy(() -> Etf.decode(new byte[] {(byte) 131, 97, 1, 2})).isInstanceOf(Etf.DecodeException.class)
                .hasMessageContaining("trailing");
        assertThatThrownBy(() -> Etf.decode(new byte[] {(byte) 131, 88, 0})).isInstanceOf(Etf.DecodeException.class)
                .hasMessageContaining("unsupported term tag");
        assertThatThrownBy(() -> Etf.decode(new byte[] {(byte) 131, 108, 0, 0, 0, 1, 97, 1, 97, 2}))
                .isInstanceOf(Etf.DecodeException.class).hasMessageContaining("improper");
        assertThatThrownBy(() -> Etf.decode(new byte[] {(byte) 131, 116, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}))
                .isInstanceOf(Etf.DecodeException.class).hasMessageContaining("exceeds");
    }

    @Test
    void refusesHostileCompressedTerms() {
        byte[] good = golden("etf-compressed.bin");
        byte[] liar = good.clone();
        liar[5] = (byte) (liar[5] + 1); // claims one byte more than the stream holds
        assertThatThrownBy(() -> Etf.decode(liar)).isInstanceOf(Etf.DecodeException.class).hasMessageContaining("truncated");
        byte[] huge = good.clone();
        huge[2] = 0x7F; // claims ~2 GiB uncompressed
        assertThatThrownBy(() -> Etf.decode(huge)).isInstanceOf(Etf.DecodeException.class).hasMessageContaining("too large");
        byte[] corrupt = good.clone();
        corrupt[7] ^= 0x55;
        corrupt[8] ^= 0x55;
        assertThatThrownBy(() -> Etf.decode(corrupt)).isInstanceOf(Etf.DecodeException.class);
    }

    @Test
    void refusesDeepNesting() {
        java.io.ByteArrayOutputStream nested = new java.io.ByteArrayOutputStream();
        nested.write(131);
        for (int i = 0; i < Etf.MAX_DEPTH + 10; i++) {
            nested.writeBytes(new byte[] {108, 0, 0, 0, 1}); // a one-element list whose element is the next list
        }
        nested.write(106);
        assertThatThrownBy(() -> Etf.decode(nested.toByteArray())).isInstanceOf(Etf.DecodeException.class)
                .hasMessageContaining("too deep");

        List<Object> deep = new ArrayList<>();
        List<Object> current = deep;
        for (int i = 0; i < Etf.MAX_DEPTH + 10; i++) {
            List<Object> next = new ArrayList<>();
            current.add(next);
            current = next;
        }
        assertThatThrownBy(() -> Etf.encode(deep)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToEncodeUnknownTypes() {
        assertThatThrownBy(() -> Etf.encode(new Object())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot encode");
    }
}
