package dev.granary.format.helm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HelmIndexTest {

    static final String UPSTREAM = """
            apiVersion: v1
            entries:
              hello-world:
              - apiVersion: v2
                appVersion: 1.16.0
                created: "2021-03-02T18:36:45.469691322Z"
                description: A Helm chart for Kubernetes
                digest: a6fe72c563eb8c9573daa5628cd4b9fc9f64fd8cadc22a20172064279e8d3b1a
                name: hello-world
                type: application
                urls:
                - https://github.com/helm/examples/releases/download/hello-world-0.1.0/hello-world-0.1.0.tgz
                version: 0.1.0
              nginx:
              - name: nginx
                version: 1.0.0
                urls: [charts/nginx-1.0.0.tgz]
              - name: nginx
                version: 1.10.0
                urls: [charts/nginx-1.10.0.tgz]
              - name: nginx
                version: 1.2.0-rc.1
                urls: [charts/nginx-1.2.0-rc.1.tgz]
                someFutureField: {nested: true}
            generated: "2021-03-02T18:36:45.469704022Z"
            serverInfo: {}
            """;

    static HelmIndex parse(String yaml) {
        return HelmIndex.parse(yaml.getBytes(StandardCharsets.UTF_8));
    }

    static ChartEntry entry(String name, String version, String url) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", name);
        fields.put("version", version);
        fields.put("urls", List.of(url));
        return ChartEntry.of(fields);
    }

    @Test
    void parsesAnUpstreamIndexAndSortsNewestFirst() {
        HelmIndex index = parse(UPSTREAM);
        assertThat(index.entries().keySet()).containsExactly("hello-world", "nginx");
        assertThat(index.versions("nginx")).extracting(ChartEntry::version).containsExactly("1.10.0", "1.2.0-rc.1", "1.0.0");
        ChartEntry hello = index.find("hello-world", "0.1.0").orElseThrow();
        assertThat(hello.digest()).isEqualTo("a6fe72c563eb8c9573daa5628cd4b9fc9f64fd8cadc22a20172064279e8d3b1a");
        assertThat(hello.created()).isEqualTo("2021-03-02T18:36:45.469691322Z");
        assertThat(hello.urls()).containsExactly("https://github.com/helm/examples/releases/download/hello-world-0.1.0/hello-world-0.1.0.tgz");
        assertThat(index.find("nginx", "1.2.0-rc.1").orElseThrow().get("someFutureField")).isEqualTo(Map.of("nested", true));
        assertThat(index.size()).isEqualTo(4);
        assertThat(index.find("nginx", "9.9.9")).isEmpty();
        assertThat(index.versions("nope")).isEmpty();
    }

    @Test
    void latestIsTheNewestReleaseUnlessThereIsOnlyPrereleases() {
        HelmIndex index = HelmIndex.of(List.of(entry("a", "1.0.0", "x"), entry("a", "2.0.0-rc.1", "y"),
                entry("b", "0.1.0-alpha", "z"), entry("b", "0.2.0-beta", "w")));
        assertThat(index.latest("a")).map(ChartEntry::version).contains("1.0.0");
        assertThat(index.latest("b")).map(ChartEntry::version).contains("0.2.0-beta");
        assertThat(index.latest("c")).isEmpty();
    }

    @Test
    void entryNameIsTheKeyItIsListedUnder() {
        HelmIndex index = parse("""
                apiVersion: v1
                entries:
                  real-name:
                  - {name: other-name, version: 1.0.0, urls: [a.tgz]}
                  - {version: 2.0.0, urls: [b.tgz]}
                """);
        assertThat(index.versions("real-name")).extracting(ChartEntry::name).containsOnly("real-name");
    }

    @Test
    void invalidEntriesAreSkippedNotFatal() {
        HelmIndex index = parse("""
                apiVersion: v1
                entries:
                  ok:
                  - {name: ok, version: 1.0.0, urls: [ok-1.0.0.tgz]}
                  - ~
                  - just a string
                  - {name: ok, urls: [no-version.tgz]}
                  - {name: ok, version: '', urls: [empty.tgz]}
                  - {name: ok, version: 1.0.0, urls: [duplicate.tgz]}
                  notalist: {name: x}
                  '': [{name: blank, version: 1.0.0}]
                """);
        assertThat(index.entries().keySet()).containsExactly("ok");
        assertThat(index.versions("ok")).hasSize(1);
        assertThat(index.versions("ok").getFirst().urls()).containsExactly("ok-1.0.0.tgz");
    }

    @Test
    void emptyAndMissingEntries() {
        assertThat(parse("apiVersion: v1\nentries: {}\n").size()).isZero();
        assertThat(parse("apiVersion: v1\n").size()).isZero();
        assertThat(parse("").size()).isZero();
        assertThatThrownBy(() -> parse("apiVersion: v1\nentries: [a]\n")).isInstanceOf(HelmYaml.InvalidYamlException.class);
        assertThatThrownBy(() -> parse("<html><body>Not found</body></html>")).isInstanceOf(HelmYaml.InvalidYamlException.class);
    }

    @Test
    void writesYamlHelmCanRead() {
        HelmIndex index = parse(UPSTREAM);
        String yaml = new String(index.toYaml("2026-01-01T00:00:00Z", Map.of("contextPath", "/repository/helm-hosted")),
                StandardCharsets.UTF_8);
        assertThat(yaml).startsWith("apiVersion: v1\nentries:\n  hello-world:\n  - apiVersion: v2\n    appVersion: 1.16.0\n");
        assertThat(yaml).contains("    version: 0.1.0\n").contains("generated: '2026-01-01T00:00:00Z'\n")
                .contains("serverInfo:\n  contextPath: /repository/helm-hosted\n")
                .contains("created: '2021-03-02T18:36:45.469691322Z'");
        // keys of every entry are sorted the way helm repo index writes them
        int api = yaml.indexOf("    apiVersion: v2");
        int digest = yaml.indexOf("    digest: a6fe");
        int urls = yaml.indexOf("    urls:");
        assertThat(api).isLessThan(digest);
        assertThat(digest).isLessThan(urls);
        HelmIndex back = HelmIndex.parse(yaml.getBytes(StandardCharsets.UTF_8));
        assertThat(back.size()).isEqualTo(index.size());
        assertThat(back.find("nginx", "1.10.0")).isPresent();
        assertThat(back.find("hello-world", "0.1.0").orElseThrow().created()).isEqualTo("2021-03-02T18:36:45.469691322Z");
    }

    @Test
    void emptyIndexIsValidYaml() {
        String yaml = new String(HelmIndex.empty().toYaml("2026-01-01T00:00:00Z", null), StandardCharsets.UTF_8);
        assertThat(yaml).isEqualTo("apiVersion: v1\nentries: {}\ngenerated: '2026-01-01T00:00:00Z'\n");
    }

    @Test
    void mergeUnionsMembersAndTheFirstMemberWinsAVersion() {
        HelmIndex hosted = HelmIndex.of(List.of(entry("demo", "1.0.0", "charts/demo-1.0.0.tgz").with("digest", "hosted")));
        HelmIndex proxy = HelmIndex.of(List.of(
                entry("demo", "1.0.0", "charts/demo-1.0.0.tgz").with("digest", "proxy"),
                entry("demo", "2.0.0", "charts/demo-2.0.0.tgz"),
                entry("nginx", "1.0.0", "charts/nginx-1.0.0.tgz")));
        HelmIndex merged = HelmIndex.merge(List.of(hosted, proxy));
        assertThat(merged.versions("demo")).extracting(ChartEntry::version).containsExactly("2.0.0", "1.0.0");
        assertThat(merged.find("demo", "1.0.0").orElseThrow().digest()).isEqualTo("hosted");
        assertThat(merged.find("nginx", "1.0.0")).isPresent();
        assertThat(HelmIndex.merge(List.of(proxy, hosted)).find("demo", "1.0.0").orElseThrow().digest()).isEqualTo("proxy");
        assertThat(HelmIndex.merge(List.of()).size()).isZero();
    }

    @Test
    void mergeReaddressesAPathAlreadyTakenByAnotherChart() {
        HelmIndex first = HelmIndex.of(List.of(entry("demo", "1.0.0", "charts/demo-1.0.0.tgz")));
        HelmIndex second = HelmIndex.of(List.of(entry("other", "3.0.0", "charts/demo-1.0.0.tgz"),
                entry("weird name", "1.0.0", "charts/demo-1.0.0.tgz")));
        HelmIndex merged = HelmIndex.merge(List.of(first, second));
        assertThat(merged.find("demo", "1.0.0").orElseThrow().urls()).containsExactly("charts/demo-1.0.0.tgz");
        assertThat(merged.find("other", "3.0.0").orElseThrow().urls()).containsExactly("charts/other/3.0.0/demo-1.0.0.tgz");
        assertThat(merged.versions("weird name")).isEmpty(); // cannot be addressed safely, so it is left out
        assertThat(merged.entries()).doesNotContainKey("weird name");
    }

    @Test
    void mapTransformsAndDrops() {
        HelmIndex index = parse(UPSTREAM).map(e -> e.name().equals("nginx") ? null : e.withUrls(List.of("x")));
        assertThat(index.entries().keySet()).containsExactly("hello-world");
        assertThat(index.all().getFirst().urls()).containsExactly("x");
    }

    @Test
    void toMapIsTheChartMuseumShape() {
        Map<String, Object> map = parse(UPSTREAM).toMap();
        assertThat(map.keySet()).containsExactly("hello-world", "nginx");
        assertThat((List<?>) map.get("nginx")).hasSize(3);
    }

    @Test
    void entryAccessors() {
        ChartEntry e = entry("demo", "1.0.0", "a.tgz").with("urls", List.of(" b.tgz ", "", 5, "c.tgz"));
        assertThat(e.urls()).containsExactly("b.tgz", "c.tgz");
        assertThat(e.with("urls", "not a list").urls()).isEmpty();
        assertThat(e.with("digest", null).digest()).isNull();
        assertThat(e.with("deprecated", true).string("deprecated")).isEqualTo("true");
        assertThat(e).isEqualTo(entry("demo", "1.0.0", "a.tgz").with("urls", List.of(" b.tgz ", "", 5, "c.tgz")));
        assertThat(e.hashCode()).isEqualTo(e.with("x", null).hashCode());
        assertThat(e.toString()).isEqualTo("demo 1.0.0");
        assertThat(e.fields()).containsKey("name");
    }
}
