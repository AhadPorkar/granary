package dev.granary.format.helm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.helm.HelmPath.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HelmPathTest {

    @ParameterizedTest
    @CsvSource({
            "'', ROOT",
            "/, ROOT",
            "index.yaml, INDEX",
            "/index.yaml, INDEX",
            "index.yaml/x, UNKNOWN",
            "index.yml, UNKNOWN",
            "charts/demo-1.0.0.tgz, CHART",
            "charts/demo-1.0.0.tgz.prov, PROVENANCE",
            "charts/demo-1.0.0.tar.gz, UNKNOWN",
            "charts/readme.txt, UNKNOWN",
            "charts/, UNKNOWN",
            "charts, UNKNOWN",
            "charts/demo/1.0.0, UNKNOWN",
            "charts/demo/1.0.0/demo-1.0.0.tgz, CHART_ALIAS",
            "charts/demo/1.0.0/demo-1.0.0.tgz.prov, PROVENANCE_ALIAS",
            "charts/demo/1.0.0/notes.txt, UNKNOWN",
            "charts/a/b/c/d.tgz, UNKNOWN",
            "demo-1.0.0.tgz, ROOT_FILE",
            "demo-1.0.0.tgz.prov, ROOT_FILE",
            "README.md, UNKNOWN",
            "artifacthub-repo.yml, ARTIFACTHUB",
            "health, HEALTH",
            "health/x, UNKNOWN",
            "api/charts, API_CHARTS",
            "api/charts/, API_CHARTS",
            "api/charts/demo, API_CHART",
            "api/charts/demo/, API_CHART",
            "api/charts/demo/1.0.0, API_CHART_VERSION",
            "api/charts/demo/latest, API_CHART_VERSION",
            "api/charts/demo/1.0.0/templates, UNKNOWN",
            "api/prov, API_PROV",
            "api/prov/x, UNKNOWN",
            "api, UNKNOWN",
            "api/v1/charts, UNKNOWN",
            "entries/demo/1.0.0.json, UNKNOWN",
    })
    void classifies(String path, Kind kind) {
        assertThat(HelmPath.parse(path).kind()).as(path).isEqualTo(kind);
    }

    @ParameterizedTest
    @ValueSource(strings = {"charts/../index.yaml", "charts/..", "charts/.hidden.tgz", "charts/a b.tgz", "charts/a\\b.tgz",
            "charts//a.tgz", "charts/ünï.tgz", "charts/a%2Fb.tgz", "charts/a\u0000.tgz", "charts/a:b.tgz",
            "charts/../demo/1.0.0/x.tgz", "charts/demo/../x.tgz", "charts/de mo/1.0.0/x.tgz", "charts/demo/1.0.0/../x.tgz",
            "api/charts/../x", "api/charts/de mo", "api/charts/demo/1 0", "../demo-1.0.0.tgz", ".tgz", "a;b.tgz",
            "api/charts/demo/..", "api/charts/%2e%2e"})
    void oddInputIsInvalidNeverAPathToStorage(String path) {
        assertThat(HelmPath.parse(path).kind()).as(path).isIn(Kind.INVALID, Kind.UNKNOWN);
    }

    @Test
    void chartPathsCarryTheirParts() {
        HelmPath chart = HelmPath.parse("charts/demo-1.0.0.tgz");
        assertThat(chart.filename()).isEqualTo("demo-1.0.0.tgz");
        assertThat(chart.isProvenancePath()).isFalse();
        assertThat(chart.isMetadata()).isFalse();

        HelmPath alias = HelmPath.parse("charts/demo/1.0.0+build.1/demo-1.0.0+build.1.tgz.prov");
        assertThat(alias.kind()).isEqualTo(Kind.PROVENANCE_ALIAS);
        assertThat(alias.name()).isEqualTo("demo");
        assertThat(alias.version()).isEqualTo("1.0.0+build.1");
        assertThat(alias.isProvenancePath()).isTrue();

        HelmPath api = HelmPath.parse("api/charts/my_chart.v2/v1.2.3-rc.1");
        assertThat(api.name()).isEqualTo("my_chart.v2");
        assertThat(api.version()).isEqualTo("v1.2.3-rc.1");

        assertThat(HelmPath.parse("demo-1.0.0.tgz.prov").isProvenancePath()).isTrue();
        assertThat(HelmPath.parse("index.yaml").isMetadata()).isTrue();
        assertThat(HelmPath.parse("artifacthub-repo.yml").isMetadata()).isTrue();
        assertThat(HelmPath.parse(null).kind()).isEqualTo(Kind.ROOT);
    }

    @Test
    void layoutHelpers() {
        assertThat(HelmPath.archiveName("demo", "1.0.0")).isEqualTo("demo-1.0.0.tgz");
        assertThat(HelmPath.archivePath("demo", "1.0.0")).isEqualTo("charts/demo-1.0.0.tgz");
        assertThat(HelmPath.aliasPath("demo", "1.0.0", "x.tgz")).isEqualTo("charts/demo/1.0.0/x.tgz");
        assertThat(HelmPath.isArchive(".tgz")).isFalse();
        assertThat(HelmPath.isProvenance(".tgz.prov")).isFalse();
    }

    @Test
    void chartNames() {
        assertThat(HelmPath.isValidName("cert-manager")).isTrue();
        assertThat(HelmPath.isValidName("kube_state.metrics")).isTrue();
        assertThat(HelmPath.isValidName("Demo")).isTrue();
        assertThat(HelmPath.isValidName("-demo")).isFalse();
        assertThat(HelmPath.isValidName(".demo")).isFalse();
        assertThat(HelmPath.isValidName("de/mo")).isFalse();
        assertThat(HelmPath.isValidName("")).isFalse();
        assertThat(HelmPath.isValidName(null)).isFalse();
        assertThat(HelmPath.isValidName("a".repeat(250))).isTrue();
        assertThat(HelmPath.isValidName("a".repeat(251))).isFalse();
    }
}
