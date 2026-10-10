package dev.granary.format.helm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChartMetadataTest {

    private static Map<String, Object> of(String chartYaml) {
        return of(chartYaml, null);
    }

    private static Map<String, Object> of(String chartYaml, String requirements) {
        return ChartMetadata.of(new ChartArchive.Contents(yaml(chartYaml), requirements == null ? null : yaml(requirements)));
    }

    private static Map<String, Object> yaml(String text) {
        return HelmYaml.loadMap(text.getBytes(StandardCharsets.UTF_8), HelmYaml.SMALL_DOCUMENT_LIMIT);
    }

    @Test
    void keepsTheFieldsHelmKnows() {
        Map<String, Object> m = of("""
                apiVersion: v2
                name: demo
                version: 1.2.3
                kubeVersion: ">=1.22.0-0"
                description: Demo
                type: application
                keywords: [web, 1]
                home: https://example.com
                sources: [https://github.com/acme/demo]
                maintainers:
                  - name: Jane
                    email: jane@example.com
                icon: https://example.com/icon.png
                appVersion: 1.10
                deprecated: false
                annotations:
                  category: Web
                  artifacthub.io/license: 2
                dependencies:
                  - name: redis
                    version: 17.x.x
                    repository: oci://registry.example/charts
                    condition: redis.enabled
                    alias: cache
                    tags: [db]
                    import-values: [child]
                    enabled: true
                custom: dropped
                """);
        assertThat(m).containsEntry("name", "demo").containsEntry("version", "1.2.3").containsEntry("appVersion", "1.10")
                .containsEntry("deprecated", false).containsEntry("keywords", List.of("web", "1"))
                .doesNotContainKey("custom");
        assertThat(m.get("annotations")).isEqualTo(Map.of("category", "Web", "artifacthub.io/license", "2"));
        assertThat((List<?>) m.get("dependencies")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> dependency = (Map<String, Object>) ((List<?>) m.get("dependencies")).getFirst();
        assertThat(dependency).containsEntry("alias", "cache").containsEntry("enabled", true)
                .containsEntry("import-values", List.of("child")).containsEntry("tags", List.of("db"));
    }

    @Test
    void missingApiVersionMeansV1() {
        assertThat(of("name: demo\nversion: 1.0.0\n")).containsEntry("apiVersion", "v1");
    }

    @Test
    void v1ChartsTakeTheirDependenciesFromRequirements() {
        Map<String, Object> m = of("apiVersion: v1\nname: legacy\nversion: 0.1.0\n",
                "dependencies:\n- name: redis\n  version: 1.x\n  repository: https://charts.example\n");
        assertThat((List<?>) m.get("dependencies")).hasSize(1);
        Map<String, Object> v2 = of("apiVersion: v2\nname: modern\nversion: 0.1.0\n",
                "dependencies:\n- name: redis\n  version: 1.x\n");
        assertThat(v2).doesNotContainKey("dependencies");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "version: 1.0.0|chart.metadata.name is required",
            "name: ''\\nversion: 1.0.0|chart.metadata.name is required",
            "name: de/mo\\nversion: 1.0.0|is invalid",
            "name: ../demo\\nversion: 1.0.0|is invalid",
            "name: demo|chart.metadata.version is required",
            "name: demo\\nversion: latest|chart.metadata.version \"latest\" is invalid",
            "name: demo\\nversion: 1.0.0\\ntype: plugin|must be application or library",
            "name: demo\\nversion: 1.0.0\\ndescription: {a: b}|description must be a string",
            "name: [demo]\\nversion: 1.0.0|name must be a string",
            "name: demo\\nversion: 1.0.0\\nkeywords: web|keywords must be a list of strings",
            "name: demo\\nversion: 1.0.0\\nkeywords: [[a]]|keywords must be a list of strings",
            "name: demo\\nversion: 1.0.0\\ndeprecated: 'yes'|deprecated must be true or false",
            "name: demo\\nversion: 1.0.0\\nannotations: [a]|annotations must be a map of strings",
            "name: demo\\nversion: 1.0.0\\nannotations: {a: [b]}|annotations.a must be a string",
            "name: demo\\nversion: 1.0.0\\nmaintainers: jane|maintainers must be a list",
            "name: demo\\nversion: 1.0.0\\nmaintainers: [~]|maintainers must not contain empty or null nodes",
            "name: demo\\nversion: 1.0.0\\ndependencies: redis|dependencies must be a list",
            "name: demo\\nversion: 1.0.0\\ndependencies: [~]|dependencies must not contain empty or null nodes",
            "name: demo\\nversion: 1.0.0\\ndependencies: [{name: r, alias: 'a b'}]|disallowed characters in the alias",
            "name: demo\\nversion: 1.0.0\\ndependencies: [{name: r, enabled: 'yes'}]|enabled must be true or false",
            "name: demo\\nversion: 1.0.0\\ndependencies: [{name: [r]}]|dependency name must be a string",
    })
    void refusesWhatWouldBreakTheIndex(String chartYaml, String message) {
        assertThatThrownBy(() -> of(chartYaml.replace("\\n", "\n"))).isInstanceOf(ChartArchive.InvalidChartException.class)
                .hasMessageContaining(message);
    }

    @Test
    void libraryChartsAndEmptyTypeAreFine() {
        assertThat(of("name: lib\nversion: 1.0.0\ntype: library\n")).containsEntry("type", "library");
        assertThat(of("name: app\nversion: 1.0.0\ntype: ''\n")).containsEntry("type", "");
    }

    @Test
    void booleanLookingStringsAreKeptAsStrings() {
        assertThat(of("name: demo\nversion: 1.0.0\ndescription: true\n")).containsEntry("description", "true");
    }
}
