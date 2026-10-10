package dev.granary.format.rubygems;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.format.rubygems.marshal.MarshalReader;
import dev.granary.format.rubygems.marshal.MarshalWriter;
import dev.granary.format.rubygems.marshal.Ruby;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RubygemsRequirementTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "'>= 1.0, < 3'|'>= 1.0, < 3'|'< 3&>= 1.0'",
            "'~> 2.1'|'~> 2.1'|'~> 2.1'",
            "'1.0'|'= 1.0'|'= 1.0'",
            "'>=1.0'|'>= 1.0'|'>= 1.0'",
            "'!= 1.2, >= 1'|'!= 1.2, >= 1'|'!= 1.2&>= 1'",
            "''|'>= 0'|'>= 0'",
            "'< 4.0&>= 2.8'|'< 4.0, >= 2.8'|'< 4.0&>= 2.8'",
            "'>= 3.0, < 3.4.dev'|'>= 3.0, < 3.4.dev'|'< 3.4.dev&>= 3.0'",
    })
    void parsesAndPrints(String input, String toS, String compact) {
        GemRequirement r = GemRequirement.parse(input);
        assertThat(r.toString()).isEqualTo(toS);
        assertThat(r.toCompactIndex()).isEqualTo(compact);
    }

    @ParameterizedTest
    @ValueSource(strings = {"=> 1.0", ">= ", ">= abc", "~> 1.0, >= x", "<<1"})
    void refusesIllformedRequirements(String input) {
        assertThatThrownBy(() -> GemRequirement.parse(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anyMatchesEverything() {
        assertThat(GemRequirement.any().isAny()).isTrue();
        assertThat(GemRequirement.parse(">= 0").isAny()).isTrue();
        assertThat(GemRequirement.parse(">= 0.0").isAny()).isTrue();
        assertThat(GemRequirement.parse("> 0").isAny()).isFalse();
        assertThat(new GemRequirement(List.of()).isAny()).isTrue();
        assertThatThrownBy(() -> GemRequirement.constraint("=~", "1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void marshalsExactlyLikeRuby() {
        // Marshal.dump(Gem::Requirement.new(">= 1.0", "< 2")) printed by Ruby 3.3
        String ruby = "0408553a1547656d3a3a526571756972656d656e745b065b075b074922073e3d063a064554553a1147"
                + "656d3a3a56657273696f6e5b06492208312e30063b06545b074922063c063b0654553b075b0649220632063b0654";
        byte[] ours = MarshalWriter.dump(GemRequirement.parse(">= 1.0, < 2").toMarshal());
        assertThat(HexFormat.of().formatHex(ours)).isEqualTo(ruby);
        assertThat(GemRequirement.fromMarshal(MarshalReader.load(HexFormat.of().parseHex(ruby))))
                .isEqualTo(GemRequirement.parse(">= 1.0, < 2"));
    }

    @Test
    void unexpectedMarshalledValuesMeanAnyVersion() {
        assertThat(GemRequirement.fromMarshal(null)).isEqualTo(GemRequirement.any());
        assertThat(GemRequirement.fromMarshal("x")).isEqualTo(GemRequirement.any());
        assertThat(GemRequirement.fromMarshal(new Ruby.UserMarshal("Gem::Requirement", List.of()))).isEqualTo(GemRequirement.any());
        assertThat(GemRequirement.fromMarshal(new Ruby.UserMarshal("Gem::Requirement",
                List.of(List.of(List.of("=~", Ruby.gemVersion("1"))))))).isEqualTo(GemRequirement.any());
    }

    @Test
    void dependencyMarshalsAndReadsBack() {
        GemDependency dep = new GemDependency("rake", GemDependency.RUNTIME, GemRequirement.parse(">= 12.0"));
        Ruby.RObject o = dep.toMarshal();
        assertThat(o.className()).isEqualTo("Gem::Dependency");
        assertThat(o.ivar("@type")).isEqualTo(Ruby.symbol("runtime"));
        Object read = MarshalReader.load(MarshalWriter.dump(o));
        assertThat(GemDependency.fromMarshal(read)).isEqualTo(dep);
        assertThat(GemDependency.fromMarshal("rake")).isNull();
        Map<String, Object> noName = new LinkedHashMap<>();
        noName.put("@type", Ruby.symbol("development"));
        assertThat(GemDependency.fromMarshal(new Ruby.RObject("Gem::Dependency", noName))).isNull();
    }
}
