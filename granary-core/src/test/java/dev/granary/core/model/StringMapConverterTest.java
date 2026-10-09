package dev.granary.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StringMapConverterTest {

    private final StringMapConverter converter = new StringMapConverter();

    @Test
    void roundTripsAsSortedJson() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("zeta", "1");
        settings.put("alpha", "a \"quoted\" value");
        String column = converter.convertToDatabaseColumn(settings);
        assertThat(column).isEqualTo("{\"alpha\":\"a \\\"quoted\\\" value\",\"zeta\":\"1\"}");
        assertThat(converter.convertToEntityAttribute(column)).containsExactlyInAnyOrderEntriesOf(Map.of("alpha", "a \"quoted\" value", "zeta", "1"));
    }

    @Test
    void emptyAndNullCollapseToNullColumnAndEmptyMap() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(Map.of())).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute(" ")).isEmpty();
    }

    @Test
    void repositoryHelpersReadSettings() {
        Repository repo = new Repository();
        repo.setFormatSettings(Map.of("docker.v1Enabled", "true", "pypi.name", "  spaced  ", "blank", " "));
        assertThat(repo.setting("pypi.name")).contains("spaced");
        assertThat(repo.setting("blank")).isEmpty();
        assertThat(repo.setting("missing", "dflt")).isEqualTo("dflt");
        assertThat(repo.settingEnabled("docker.v1Enabled", false)).isTrue();
        assertThat(repo.settingEnabled("missing", true)).isTrue();
    }
}
