package dev.granary.format.hex;

import static dev.granary.format.hex.HexTestSupport.golden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.format.hex.ErlangTerms.Atom;
import dev.granary.format.hex.ErlangTerms.Tuple;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HexErlangTermsTest {

    @Test
    void readsTheMetadataConfigHexTarballWrote() {
        Map<String, byte[]> files = HexTarball.entries(golden("acme_lib-1.2.3.tar"));
        String text = ErlangTerms.decodeBinary(files.get("metadata.config"));
        Map<String, Object> all = ErlangTerms.consult(text);
        assertThat(all).containsKeys("name", "version", "app", "description", "licenses", "links", "build_tools",
                "elixir", "files", "requirements");
        assertThat(all.get("name")).isEqualTo("acme_lib");
        assertThat(all.get("description")).isEqualTo("Ünïcode description with \"quotes\" and a\nnewline");
        assertThat(all.get("licenses")).isEqualTo(List.of("Apache-2.0", "MIT"));
        assertThat(all.get("links")).isEqualTo(List.of(new Tuple(List.of("GitHub", "https://example.test/acme"))));
        @SuppressWarnings("unchecked")
        List<Object> requirements = (List<Object>) all.get("requirements");
        assertThat(requirements).hasSize(2);
        assertThat(ErlangTerms.asProperties(requirements.get(1))).containsEntry("name", "telemetry")
                .containsEntry("optional", true).containsEntry("requirement", "~> 1.0");
    }

    @Test
    void parsesEveryLiteralTheErlangScannerAccepts() {
        assertThat(ErlangTerms.parseOne("{ok, 'Quoted Atom', node@host, [1, -2, +3], 16#FF, 2#1010, -16#10}"))
                .isEqualTo(new Tuple(List.of(new Atom("ok"), new Atom("Quoted Atom"), new Atom("node@host"),
                        List.of(1L, -2L, 3L), 255L, 10L, -16L)));
        assertThat(ErlangTerms.parseOne("[1.5, -2.0e3, 3.25E-2, 99999999999999999999]")).isEqualTo(List.of(1.5, -2000.0,
                0.0325, new BigInteger("99999999999999999999")));
        assertThat(ErlangTerms.parseOne("[$a, $\\n, $\\s, $\\x41, $ü]")).isEqualTo(List.of(97L, 10L, 32L, 65L, 252L));
        assertThat(ErlangTerms.parseOne("\"tab\\there\\\\ \\\"q\\\" \\x{1F600} \\101 \\^A\\e\\d\"."))
                .isEqualTo("tab\there\\ \"q\" 😀 A \u0001\u001b\u007f");
        assertThat(ErlangTerms.parseOne("\"con\" \"cat\" \"enated\"")).isEqualTo("concatenated");
        assertThat(ErlangTerms.parseOne("{}")).isEqualTo(new Tuple(List.of()));
        assertThat(ErlangTerms.parseOne("[]")).isEqualTo(List.of());
        assertThat(ErlangTerms.parseOne("true")).isEqualTo(true);
        assertThat(ErlangTerms.parseOne("false")).isEqualTo(false);
        assertThat(ErlangTerms.parseOne("[a | [b, c]]")).isEqualTo(List.of(new Atom("a"), new Atom("b"), new Atom("c")));
    }

    @Test
    void parsesBinariesInEveryEncoding() {
        assertThat(ErlangTerms.parseOne("<<>>")).isEqualTo("");
        assertThat(ErlangTerms.parseOne("<<\"plain\">>")).isEqualTo("plain");
        assertThat(ErlangTerms.parseOne("<<\"ü✓\"/utf8>>")).isEqualTo("ü✓");
        assertThat(ErlangTerms.parseOne("<<\"Ünï\">>")).isEqualTo("Ünï"); // Latin-1 bytes, not UTF-8
        assertThat(ErlangTerms.parseOne("<<104,105>>")).isEqualTo("hi");
        assertThat(ErlangTerms.parseOne("<<195,188>>")).isEqualTo("ü"); // UTF-8 bytes written as integers
        assertThat(ErlangTerms.parseOne("<<\"a\", $b, 99, \"d\"/utf8>>")).isEqualTo("abcd");
        assertThat(ErlangTerms.parseOne("<<252/utf8>>")).isEqualTo("ü");
        assertThat(ErlangTerms.parseOne("<<1:8, 2>>")).isEqualTo("\u0001\u0002");
        assertThat(ErlangTerms.parseOne("<<\"hi\"/utf16>>")).isEqualTo("\u0000h\u0000i");
        assertThat(ErlangTerms.parseOne("<<\"A\"/utf32>>")).isEqualTo("\u0000\u0000\u0000A");
    }

    @Test
    void parsesMapsWithBothOperators() {
        Object term = ErlangTerms.parseOne("#{<<\"app\">> => <<\"jason\">>, optional => false, n := 1}");
        assertThat(term).isInstanceOf(Map.class);
        assertThat(ErlangTerms.asProperties(term)).containsEntry("app", "jason").containsEntry("optional", false)
                .containsEntry("n", 1L);
        assertThat(ErlangTerms.parseOne("#{}")).isEqualTo(Map.of());
    }

    @Test
    void consultReadsDotTerminatedPairsWithComments() {
        Map<String, Object> all = ErlangTerms.consult("""
                %% a comment line
                {<<"name">>, <<"x">>}. % trailing comment
                {"version", "1.0.0"}.
                {app, x}.
                """);
        assertThat(all).containsEntry("name", "x").containsEntry("version", "1.0.0").containsEntry("app", new Atom("x"));
        assertThat(ErlangTerms.consult("")).isEmpty();
        assertThat(ErlangTerms.parseAll("1. 2.\n3.")).containsExactly(1L, 2L, 3L);
    }

    @Test
    void consultRefusesWhatIsNotAPropertyFile() {
        assertThatThrownBy(() -> ErlangTerms.consult("{a, 1}. {a, 2}.")).isInstanceOf(ErlangTerms.ParseException.class)
                .hasMessageContaining("duplicate key a");
        assertThatThrownBy(() -> ErlangTerms.consult("[1, 2].")).isInstanceOf(ErlangTerms.ParseException.class)
                .hasMessageContaining("expected {Key, Value} pairs, got a list");
        assertThatThrownBy(() -> ErlangTerms.consult("{1, 2}.")).isInstanceOf(ErlangTerms.ParseException.class)
                .hasMessageContaining("key");
        assertThatThrownBy(() -> ErlangTerms.consult("{a, 1}")).isInstanceOf(ErlangTerms.ParseException.class)
                .hasMessageContaining("expected '.'");
        assertThatThrownBy(() -> ErlangTerms.parseAll("1.a.")).isInstanceOf(ErlangTerms.ParseException.class)
                .hasMessageContaining("whitespace after the terminating dot");
        assertThat(ErlangTerms.parseAll("1.2.")).containsExactly(1.2);
    }

    @Test
    void refusesMalformedSyntax() {
        for (String bad : List.of("{a, b", "[1, 2", "\"unterminated", "<<\"x\"", "#{a => }", "Variable", "{a b}",
                "<<1.5>>", "<<\"✓\">>", "$", "\"\\x{}\"", "\"\\xZZ\"", "36#", "37#1", "1.0e", "[a | b]", "@", "-")) {
            assertThatThrownBy(() -> ErlangTerms.parseOne(bad)).as(bad).isInstanceOf(ErlangTerms.ParseException.class);
        }
        assertThatThrownBy(() -> ErlangTerms.parseOne("1 2")).isInstanceOf(ErlangTerms.ParseException.class)
                .hasMessageContaining("unexpected input");
        assertThatThrownBy(() -> ErlangTerms.parseOne("")).isInstanceOf(ErlangTerms.ParseException.class)
                .hasMessageContaining("end of input");
    }

    @Test
    void literalsOutOfRangeAreParseErrorsNotJdkExceptions() {
        // each of these used to escape as NumberFormatException or IllegalArgumentException (a 500 on publish)
        for (String bad : List.of("99999999999#1", "007#1", "\"\\x{FFFFFFFF}\"", "\"\\x{110000}\"", "$\\x{110000}",
                "<<1114112/utf8>>", "<<55296/utf8>>", "<<$\\x{110000}/utf8>>", "<<\"\\x{D800}\"/utf8>>",
                "<<4294967296/utf8>>", "<<-1/utf8>>")) {
            assertThatThrownBy(() -> ErlangTerms.parseOne(bad)).as(bad).isInstanceOf(ErlangTerms.ParseException.class);
            assertThatThrownBy(() -> ErlangTerms.consult("{<<\"x\">>, " + bad + "}.")).as(bad)
                    .isInstanceOf(ErlangTerms.ParseException.class);
        }
        assertThat(ErlangTerms.parseOne("\"\\x{10FFFF}\"")).isEqualTo(new String(Character.toChars(0x10FFFF)));
        assertThat(ErlangTerms.parseOne("<<128512/utf8>>")).isEqualTo("😀");
        assertThat(ErlangTerms.parseOne("36#Z")).isEqualTo(35L);
    }

    @Test
    void refusesNestingDeepEnoughToHurt() {
        String deep = "[".repeat(ErlangTerms.MAX_DEPTH + 1) + "]".repeat(ErlangTerms.MAX_DEPTH + 1);
        assertThatThrownBy(() -> ErlangTerms.parseOne(deep)).isInstanceOf(ErlangTerms.ParseException.class)
                .hasMessageContaining("too deep");
        String fine = "[".repeat(100) + "]".repeat(100);
        assertThat(ErlangTerms.parseOne(fine)).isInstanceOf(List.class);
    }

    @Test
    void helpers() {
        assertThat(ErlangTerms.asString("x")).isEqualTo("x");
        assertThat(ErlangTerms.asString(new Atom("y"))).isEqualTo("y");
        assertThat(ErlangTerms.asString(true)).isEqualTo("true");
        assertThat(ErlangTerms.asString(1L)).isNull();
        assertThat(ErlangTerms.asProperties(List.of(new Tuple(List.of("k", 1L))))).containsEntry("k", 1L);
        assertThat(ErlangTerms.asProperties(List.of(1L))).isNull();
        assertThat(ErlangTerms.asProperties(List.of(new Tuple(List.of(1L, 2L))))).isNull();
        assertThat(ErlangTerms.asProperties(Map.of(1L, 2L))).isNull();
        assertThat(ErlangTerms.asProperties("x")).isNull();
        assertThat(ErlangTerms.describe(null)).isEqualTo("nothing");
        assertThat(ErlangTerms.describe(new Tuple(List.of(1L)))).isEqualTo("a tuple of size 1");
        assertThat(ErlangTerms.describe(Map.of())).isEqualTo("a map");
        assertThat(ErlangTerms.describe("s")).isEqualTo("a binary");
        assertThat(ErlangTerms.describe(1L)).isEqualTo("long");
        assertThat(new Atom("ok").toString()).isEqualTo("ok");
        assertThat(ErlangTerms.decodeBinary("ü".getBytes(StandardCharsets.UTF_8))).isEqualTo("ü");
        assertThat(ErlangTerms.decodeBinary(new byte[] {(byte) 0xFC})).isEqualTo("ü");
    }
}
