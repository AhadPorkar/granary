package dev.granary.format.hex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.format.hex.HexMetadata.Requirement;
import org.junit.jupiter.api.Test;

class HexMetadataTest {

    private static final String BASE = "{<<\"name\">>,<<\"acme\">>}.\n{<<\"version\">>,<<\"1.0.0\">>}.\n";

    @Test
    void requirementsAsTheCurrentMixWritesThem() {
        HexMetadata meta = HexMetadata.parse(BASE + """
                {<<"requirements">>,
                 [[{<<"name">>,<<"jason">>},{<<"repository">>,<<"hexpm">>},{<<"requirement">>,<<"~> 1.4">>},
                   {<<"optional">>,false},{<<"app">>,<<"jason">>}]]}.
                """);
        assertThat(meta.requirements()).containsExactly(new Requirement("jason", "jason", "~> 1.4", false, "hexpm"));
    }

    @Test
    void requirementsAsTuplesAndMaps() {
        HexMetadata tuples = HexMetadata.parse(BASE + """
                {<<"requirements">>,[{<<"plug">>,[{<<"app">>,<<"plug_app">>},{<<"optional">>,true},
                  {<<"requirement">>,<<">= 1.0.0">>}]}]}.
                """);
        assertThat(tuples.requirements()).containsExactly(new Requirement("plug", "plug_app", ">= 1.0.0", true, null));

        HexMetadata maps = HexMetadata.parse(BASE + """
                {<<"requirements">>,#{<<"cowboy">> => #{<<"app">> => <<"cowboy">>, <<"optional">> => <<"true">>,
                  <<"requirement">> => <<"~> 2.10">>, <<"repository">> => <<"acme">>}}}.
                """);
        assertThat(maps.requirements()).containsExactly(new Requirement("cowboy", "cowboy", "~> 2.10", true, "acme"));
    }

    @Test
    void defaultsAndLooseValues() {
        HexMetadata meta = HexMetadata.parse("""
                {name, acme}.
                {version, "1.0.0-beta.2+build.5"}.
                {licenses, <<"MIT">>}.
                {links, []}.
                {<<"description">>, 'An atom description'}.
                {<<"requirements">>, [[{<<"name">>,<<"dep">>},{<<"requirement">>,<<"1.0.0">>},{<<"app">>,<<>>},
                  {<<"repository">>,<<>>}]]}.
                {<<"extra">>, [1, 2, 3]}.
                """);
        assertThat(meta.name()).isEqualTo("acme");
        assertThat(meta.app()).isEqualTo("acme");
        assertThat(meta.version()).isEqualTo("1.0.0-beta.2+build.5");
        assertThat(meta.licenses()).containsExactly("MIT");
        assertThat(meta.links()).isEmpty();
        assertThat(meta.description()).isEqualTo("An atom description");
        assertThat(meta.requirements()).containsExactly(new Requirement("dep", "dep", "1.0.0", false, null));
        assertThat(meta.buildTools()).isEmpty();
        assertThat(meta.files()).isEmpty();
        assertThat(meta.elixir()).isNull();
        assertThat(meta.all()).containsKey("extra");
    }

    @Test
    void linksAsAMap() {
        HexMetadata meta = HexMetadata.parse(BASE + "{<<\"links\">>,#{<<\"Docs\">> => <<\"https://d.test\">>, <<\"N\">> => 1}}.\n");
        assertThat(meta.links()).containsEntry("Docs", "https://d.test").containsEntry("N", "1");
    }

    @Test
    void refusesInvalidMetadata() {
        assertThatThrownBy(() -> HexMetadata.parse("{<<\"version\">>,<<\"1.0.0\">>}.")).hasMessageContaining("has no name");
        assertThatThrownBy(() -> HexMetadata.parse("{<<\"name\">>,<<\"acme\">>}.")).hasMessageContaining("has no version");
        assertThatThrownBy(() -> HexMetadata.parse("{<<\"name\">>,<<\"\">>}.")).hasMessageContaining("has no name");
        assertThatThrownBy(() -> HexMetadata.parse("{<<\"name\">>,<<\"../etc\">>}.\n{<<\"version\">>,<<\"1.0.0\">>}."))
                .hasMessageContaining("invalid package name");
        assertThatThrownBy(() -> HexMetadata.parse("{<<\"name\">>,<<\"acme\">>}.\n{<<\"version\">>,<<\"v1\">>}."))
                .hasMessageContaining("invalid version");
        assertThatThrownBy(() -> HexMetadata.parse(BASE + "{<<\"licenses\">>, 5}.")).hasMessageContaining("licenses must be a list");
        assertThatThrownBy(() -> HexMetadata.parse(BASE + "{<<\"licenses\">>, [{a}]}.")).hasMessageContaining("list of binaries");
        assertThatThrownBy(() -> HexMetadata.parse(BASE + "{<<\"links\">>, 5}.")).hasMessageContaining("links must be a map");
        assertThatThrownBy(() -> HexMetadata.parse(BASE + "{<<\"requirements\">>, 5}.")).hasMessageContaining("list or a map");
        assertThatThrownBy(() -> HexMetadata.parse(BASE + "{<<\"requirements\">>, [5]}.")).hasMessageContaining("unsupported requirement");
        assertThatThrownBy(() -> HexMetadata.parse(BASE + "{<<\"requirements\">>, [{<<\"Bad\">>, []}]}."))
                .hasMessageContaining("invalid dependency name");
        assertThatThrownBy(() -> HexMetadata.parse(BASE + "{<<\"requirements\">>, [{<<\"dep\">>, [{<<\"app\">>,<<\"d\">>}]}]}."))
                .hasMessageContaining("has no version requirement");
        assertThatThrownBy(() -> HexMetadata.parse(BASE + "{<<\"requirements\">>, [{<<\"dep\">>, 5}]}."))
                .hasMessageContaining("must be a map or a property list");
        assertThatThrownBy(() -> HexMetadata.parse(BASE + "{<<\"requirements\">>, #{{a} => 2}}."))
                .hasMessageContaining("requirement names must be binaries");
        assertThatThrownBy(() -> HexMetadata.parse("not erlang")).isInstanceOf(HexMetadata.MetadataException.class)
                .hasMessageContaining("not valid Erlang term syntax");
    }

    @Test
    void textAcceptsBinariesAtomsAndNumbers() {
        assertThat(HexMetadata.text("x")).isEqualTo("x");
        assertThat(HexMetadata.text(new ErlangTerms.Atom("a"))).isEqualTo("a");
        assertThat(HexMetadata.text(5L)).isEqualTo("5");
        assertThat(HexMetadata.text(null)).isNull();
        assertThat(HexMetadata.text(java.util.List.of())).isNull();
    }
}
