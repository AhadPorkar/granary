package dev.granary.format.pub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PubspecTest {

    private static Pubspec parse(String yaml) throws Exception {
        return Pubspec.parse(yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void keepsTheWholePubspecAsJson() throws Exception {
        Pubspec pubspec = parse("""
                name: fancy_widgets
                version: 2.1.0-dev.3+7
                description: Widgets that are fancy.
                homepage: https://example.com
                repository: https://github.com/acme/fancy_widgets
                topics: [ui, widgets]
                environment:
                  sdk: '>=3.0.0 <4.0.0'
                  flutter: '>=3.10.0'
                dependencies:
                  flutter:
                    sdk: flutter
                  path: ^1.9.0
                  meta: any
                  internal:
                    hosted: https://granary.example.com/repository/pub-group
                    version: ^1.0.0
                dev_dependencies:
                  test: ^1.24.0
                flutter:
                  uses-material-design: true
                  assets:
                    - images/a.png
                false_secrets:
                  - /test/fixtures/key.pem
                """);
        assertThat(pubspec.name()).isEqualTo("fancy_widgets");
        assertThat(pubspec.version().text()).isEqualTo("2.1.0-dev.3+7");
        JsonNode json = pubspec.json();
        assertThat(json.path("environment").path("sdk").stringValue()).isEqualTo(">=3.0.0 <4.0.0");
        assertThat(json.path("dependencies").path("flutter").path("sdk").stringValue()).isEqualTo("flutter");
        assertThat(json.path("dependencies").path("internal").path("hosted").stringValue())
                .isEqualTo("https://granary.example.com/repository/pub-group");
        assertThat(json.path("topics").get(1).stringValue()).isEqualTo("widgets");
        assertThat(json.path("flutter").path("uses-material-design").booleanValue()).isTrue();
        assertThat(json.path("dev_dependencies").path("test").stringValue()).isEqualTo("^1.24.0");
        assertThat(json.path("version").stringValue()).isEqualTo("2.1.0-dev.3+7");
    }

    @Test
    void convertsYamlScalarsToJson() throws Exception {
        Pubspec pubspec = parse("""
                name: scalars
                version: 1.0.0
                count: 3
                big: 123456789012345678901234567890
                ratio: 0.5
                infinite: .inf
                nothing: ~
                released: 2024-01-02
                1.5: numeric key
                blob: !!binary aGVsbG8=
                set: !!set {a, b}
                """);
        JsonNode json = pubspec.json();
        assertThat(json.path("count").intValue()).isEqualTo(3);
        assertThat(json.path("big").bigIntegerValue().toString()).isEqualTo("123456789012345678901234567890");
        assertThat(json.path("ratio").doubleValue()).isEqualTo(0.5);
        assertThat(json.path("infinite").stringValue()).isEqualTo("Infinity");
        assertThat(json.get("nothing").isNull()).isTrue();
        assertThat(json.path("released").stringValue()).isEqualTo("2024-01-02T00:00:00Z");
        assertThat(json.path("1.5").stringValue()).isEqualTo("numeric key");
        assertThat(json.path("blob").stringValue()).isEqualTo("aGVsbG8=");
        assertThat(json.path("set").isArray()).isTrue();
    }

    @Test
    void acceptsUnicodeDescriptions() throws Exception {
        Pubspec pubspec = parse("name: intl_demo\nversion: 1.0.0\ndescription: Sizes \u2264 10 \u00b5m, 5 \u20ac, \u03c0 \u2248 3.14 \u2713 \ud83c\udfaf\n");
        assertThat(pubspec.json().path("description").stringValue()).isEqualTo("Sizes \u2264 10 \u00b5m, 5 \u20ac, \u03c0 \u2248 3.14 \u2713 \ud83c\udfaf");
    }

    @Test
    void requiresANameAndAVersion() {
        assertThatThrownBy(() -> parse("version: 1.0.0\n")).hasMessageContaining("no \"name\"");
        assertThatThrownBy(() -> parse("name: x\n")).hasMessageContaining("no \"version\"");
        assertThatThrownBy(() -> parse("name: x\nversion: ''\n")).hasMessageContaining("no \"version\"");
        assertThatThrownBy(() -> parse("name: ''\nversion: 1.0.0\n")).hasMessageContaining("no \"name\"");
        assertThatThrownBy(() -> parse("name: [a]\nversion: 1.0.0\n")).hasMessageContaining("no \"name\"");
    }

    @Test
    void refusesInvalidVersions() {
        assertThatThrownBy(() -> parse("name: x\nversion: 1.0\n")).hasMessageContaining("\"1.0\"")
                .hasMessageContaining("not a valid version");
        assertThatThrownBy(() -> parse("name: x\nversion: 1\n")).hasMessageContaining("not a valid version");
        assertThatThrownBy(() -> parse("name: x\nversion: v1.0.0\n")).hasMessageContaining("not a valid version");
    }

    @Test
    void refusesNamesPubDevWouldRefuse() {
        assertThatThrownBy(() -> parse("name: MyPackage\nversion: 1.0.0\n")).hasMessageContaining("lower-case");
        assertThatThrownBy(() -> parse("name: my-package\nversion: 1.0.0\n")).hasMessageContaining("lower-case");
        assertThatThrownBy(() -> parse("name: class\nversion: 1.0.0\n")).hasMessageContaining("reserved word");
    }

    @Test
    void refusesWhatIsNotAYamlMapping() {
        assertThatThrownBy(() -> parse("- a\n- b\n")).hasMessageContaining("must be a YAML mapping");
        assertThatThrownBy(() -> parse("")).hasMessageContaining("must be a YAML mapping");
        assertThatThrownBy(() -> parse("just a string")).hasMessageContaining("must be a YAML mapping");
    }

    @Test
    void refusesMalformedYaml() {
        assertThatThrownBy(() -> parse("name: x\nversion: 1.0.0\n  bad: indentation\n"))
                .isInstanceOf(Pubspec.InvalidPubspecException.class).hasMessageContaining("not valid YAML");
        assertThatThrownBy(() -> parse("name: x\nname: y\nversion: 1.0.0\n")).hasMessageContaining("not valid YAML");
        assertThatThrownBy(() -> parse("name: \"unterminated\nversion: 1.0.0\n")).hasMessageContaining("not valid YAML");
        assertThatThrownBy(() -> parse("name: !!python/object:os.system x\nversion: 1.0.0\n"))
                .hasMessageContaining("not valid YAML");
    }

    @Test
    void refusesAliasBombs() {
        StringBuilder yaml = new StringBuilder("name: x\nversion: 1.0.0\na0: &a0 [x, x, x, x, x, x, x, x, x, x]\n");
        for (int i = 1; i < 12; i++) {
            yaml.append("a").append(i).append(": &a").append(i).append(" [");
            for (int j = 0; j < 10; j++) {
                yaml.append(j == 0 ? "" : ", ").append("*a").append(i - 1);
            }
            yaml.append("]\n");
        }
        assertThatThrownBy(() -> parse(yaml.toString())).isInstanceOf(Pubspec.InvalidPubspecException.class);
    }

    @Test
    void refusesDeepNesting() {
        String yaml = "name: x\nversion: 1.0.0\ndeep: " + "[".repeat(200) + "]".repeat(200) + "\n";
        assertThatThrownBy(() -> parse(yaml)).isInstanceOf(Pubspec.InvalidPubspecException.class);
    }

    @Test
    void numericVersionsAreReadAsText() throws Exception {
        assertThatThrownBy(() -> parse("name: x\nversion: 1.5\n")).hasMessageContaining("\"1.5\"");
        assertThat(parse("name: x\nversion: \"1.0.0\"\n").version().text()).isEqualTo("1.0.0");
    }
}
