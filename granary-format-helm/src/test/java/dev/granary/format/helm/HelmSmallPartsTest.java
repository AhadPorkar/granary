package dev.granary.format.helm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.GranaryException;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Settings, snippets and the bounded cache. */
class HelmSmallPartsTest {

    // --- settings --------------------------------------------------------------------------------

    @Test
    void settingsAreDeclared() {
        List<SettingDescriptor> settings = HelmSettings.descriptors();
        assertThat(settings).extracting(SettingDescriptor::key).containsExactly("helm.relativeUrls", "helm.maxChartSizeMb");
        assertThat(settings.get(0).type()).isEqualTo(SettingDescriptor.Type.BOOLEAN);
        assertThat(settings.get(0).defaultValue()).isEqualTo("false");
        assertThat(settings.get(1).type()).isEqualTo(SettingDescriptor.Type.INTEGER);
        assertThat(settings.get(1).defaultValue()).isEqualTo("20");
        assertThat(settings.get(1).appliesTo()).containsExactly(RepositoryType.HOSTED);
    }

    @Test
    void settingValues() {
        Repository repo = HelmTestSupport.hosted("h");
        assertThat(HelmSettings.relativeUrls(repo)).isFalse();
        assertThat(HelmSettings.maxChartBytes(repo)).isEqualTo(20L * 1024 * 1024);
        repo.setFormatSettings(Map.of("helm.relativeUrls", "true", "helm.maxChartSizeMb", "0"));
        assertThat(HelmSettings.relativeUrls(repo)).isTrue();
        assertThat(HelmSettings.maxChartBytes(repo)).isZero();
        repo.setFormatSettings(Map.of("helm.maxChartSizeMb", "abc"));
        assertThat(HelmSettings.maxChartBytes(repo)).isEqualTo(20L * 1024 * 1024);
        repo.setFormatSettings(Map.of("helm.maxChartSizeMb", "1"));
        assertThat(HelmSettings.maxChartBytes(repo)).isEqualTo(1024 * 1024);
    }

    @ParameterizedTest
    @ValueSource(strings = {"maybe", "2"})
    void badBooleanIsRefused(String value) {
        Repository repo = HelmTestSupport.hosted("h");
        repo.setFormatSettings(Map.of("helm.relativeUrls", value));
        assertThatThrownBy(() -> HelmSettings.validate(repo)).isInstanceOf(GranaryException.class)
                .extracting(e -> ((GranaryException) e).code()).isEqualTo("repository.helm.relativeUrls.invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "x", "10241", "1.5"})
    void badSizeIsRefused(String value) {
        Repository repo = HelmTestSupport.hosted("h");
        repo.setFormatSettings(Map.of("helm.maxChartSizeMb", value));
        assertThatThrownBy(() -> HelmSettings.validate(repo)).isInstanceOf(GranaryException.class)
                .extracting(e -> ((GranaryException) e).code()).isEqualTo("repository.helm.maxChartSizeMb.invalid");
    }

    @Test
    void goodSettingsPass() {
        Repository repo = HelmTestSupport.hosted("h");
        repo.setFormatSettings(Map.of("helm.relativeUrls", "Yes", "helm.maxChartSizeMb", "100"));
        HelmSettings.validate(repo);
        repo.setFormatSettings(Map.of());
        HelmSettings.validate(repo);
    }

    // --- snippets --------------------------------------------------------------------------------

    private static ConnectContext context(Repository repo) {
        return new ConnectContext(repo, "https://granary.example/repository/" + repo.getName(), "https://granary.example",
                "alice", ConnectContext.TOKEN_PLACEHOLDER, true);
    }

    @Test
    void hostedSnippetsCoverEveryClient() {
        List<ConnectSnippet> snippets = HelmSnippets.of(context(HelmTestSupport.hosted("helm-hosted")));
        assertThat(snippets).extracting(ConnectSnippet::id).containsExactly("helm-repo-add", "helm-install", "helm-dependency",
                "helm-repositories-yaml", "helm-push-curl", "helm-cm-push", "helm-push-put", "helmfile",
                "flux-helmrepository", "argocd-repository", "artifact-hub");
        String all = String.join("\n", snippets.stream().map(ConnectSnippet::code).toList());
        assertThat(all).contains("helm repo add granary https://granary.example/repository/helm-hosted --username alice --password <api-token>")
                .contains("curl -u alice:<api-token> --data-binary \"@<chart>-<version>.tgz\" https://granary.example/repository/helm-hosted/api/charts")
                .contains("helm cm-push <chart>-<version>.tgz granary")
                .contains("repository: \"https://granary.example/repository/helm-hosted\"")
                .contains("artifacthub-repo.yml");
        assertThat(snippets).filteredOn(s -> s.id().equals("helm-dependency")).first()
                .satisfies(s -> assertThat(s.filename()).isEqualTo("Chart.yaml"));
        assertThat(snippets).allSatisfy(s -> assertThat(s.title()).isNotBlank());
    }

    @Test
    void readOnlyRepositoriesOfferNoUploads() {
        for (Repository repo : List.of(HelmTestSupport.proxy("helm-proxy", "https://charts.example"),
                HelmTestSupport.group("helm-group", "helm-hosted"))) {
            List<ConnectSnippet> snippets = HelmSnippets.of(context(repo));
            assertThat(snippets).extracting(ConnectSnippet::id).contains("helm-repo-add", "flux-helmrepository")
                    .doesNotContain("helm-push-curl", "helm-cm-push", "helm-push-put");
            assertThat(String.join("\n", snippets.stream().map(ConnectSnippet::code).toList())).doesNotContain("--upload-file");
        }
    }

    // --- cache -----------------------------------------------------------------------------------

    @Test
    void cacheEvictsLeastRecentlyUsedByCountAndWeight() {
        BoundedCache<String, String> cache = new BoundedCache<>(2, 10, String::length);
        cache.put("a", "1234");
        cache.put("b", "1234");
        assertThat(cache.getIfPresent("a")).isEqualTo("1234"); // a is now the most recently used
        cache.put("c", "12");
        assertThat(cache.getIfPresent("b")).isNull();
        assertThat(cache.size()).isEqualTo(2);
        cache.put("d", "12345678");
        assertThat(cache.weight()).isLessThanOrEqualTo(10);
        assertThat(cache.getIfPresent("d")).isEqualTo("12345678");
        cache.put("huge", "x".repeat(11));
        assertThat(cache.getIfPresent("huge")).isNull();
        cache.put("d", "1");
        assertThat(cache.weight()).isLessThanOrEqualTo(10);
        cache.clear();
        assertThat(cache.size()).isZero();
        assertThat(cache.weight()).isZero();
    }

    @Test
    void cacheComputesOnce() {
        BoundedCache<String, String> cache = new BoundedCache<>(4, 100, String::length);
        AtomicInteger calls = new AtomicInteger();
        assertThat(cache.get("k", () -> "v" + calls.incrementAndGet())).isEqualTo("v1");
        assertThat(cache.get("k", () -> "v" + calls.incrementAndGet())).isEqualTo("v1");
        assertThat(calls).hasValue(1);
    }
}
