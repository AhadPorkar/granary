package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import java.util.List;
import org.junit.jupiter.api.Test;

class CargoSnippetsTest {

    private static ConnectContext context(Repository repo, String server, boolean anonymousRead) {
        return new ConnectContext(repo, server + "/repository/" + repo.getName(), server, ConnectContext.USERNAME_PLACEHOLDER,
                ConnectContext.TOKEN_PLACEHOLDER, anonymousRead);
    }

    private static ConnectSnippet byId(List<ConnectSnippet> snippets, String id) {
        return snippets.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no snippet " + id + " in " + snippets.stream().map(ConnectSnippet::id).toList()));
    }

    @Test
    void groupSnippetsCoverConsumingMirroringAndPublishingThroughTheHostedMember() {
        Repository group = CargoTestSupport.group("cargo-group", "cargo-hosted", "cargo-proxy");
        List<ConnectSnippet> snippets = CargoSnippets.of(context(group, "http://granary:8081", true),
                "http://granary:8081/repository/cargo-hosted");
        assertThat(snippets).extracting(ConnectSnippet::id).containsExactly("cargo-registry", "cargo-mirror", "cargo-login",
                "cargo-env", "cargo-dependency", "cargo-add", "cargo-publish", "cargo-publish-manifest");

        ConnectSnippet registry = byId(snippets, "cargo-registry");
        assertThat(registry.language()).isEqualTo("toml");
        assertThat(registry.filename()).contains(".cargo/config.toml");
        assertThat(registry.code()).contains("[registries.granary]\nindex = \"sparse+http://granary:8081/repository/cargo-group/index/\"")
                .contains("global-credential-providers = [\"cargo:token\"]")
                .contains("[net]\nretry = 3");

        assertThat(byId(snippets, "cargo-mirror").code())
                .contains("[source.crates-io]\nreplace-with = \"granary\"")
                .contains("[source.granary]\nregistry = \"sparse+http://granary:8081/repository/cargo-group/index/\"");

        assertThat(byId(snippets, "cargo-login").code()).contains("cargo login --registry granary")
                .contains("echo <api-token> | cargo login --registry granary")
                .contains("http://granary:8081/account");
        assertThat(byId(snippets, "cargo-env").code())
                .contains("CARGO_REGISTRIES_GRANARY_INDEX=sparse+http://granary:8081/repository/cargo-group/index/")
                .contains("CARGO_REGISTRIES_GRANARY_TOKEN=<api-token>")
                .doesNotContain("CARGO_HTTP_CAINFO");
        assertThat(byId(snippets, "cargo-add").code()).contains("cargo add <crate> --registry granary")
                .contains("cargo search <query> --registry granary");
        assertThat(byId(snippets, "cargo-dependency").code()).contains("registry = \"granary\"");

        String publish = byId(snippets, "cargo-publish").code();
        assertThat(publish).contains("[registries.granary-hosted] index = \"sparse+http://granary:8081/repository/cargo-hosted/index/\"")
                .contains("cargo publish --registry granary-hosted")
                .contains("cargo yank --version 0.1.0 --registry granary-hosted <crate>")
                .contains("cargo owner --list --registry granary-hosted <crate>");
        assertThat(byId(snippets, "cargo-publish-manifest").code()).contains("publish = [\"granary-hosted\"]");
    }

    @Test
    void theConfigTomlSnippetsCanBePastedIntoOneFile() {
        // TOML refuses a table defined twice, and cargo then refuses the whole configuration
        Repository group = CargoTestSupport.group("cargo-group", "cargo-hosted", "cargo-proxy");
        List<ConnectSnippet> snippets = CargoSnippets.of(context(group, "https://granary.example.com", false),
                "https://granary.example.com/repository/cargo-hosted");
        List<String> tables = snippets.stream()
                .filter(s -> s.filename() != null && s.filename().contains("config.toml"))
                .flatMap(s -> s.code().lines())
                .map(String::strip)
                .filter(line -> line.startsWith("["))
                .toList();
        assertThat(tables).contains("[registries.granary]", "[source.crates-io]", "[source.granary]", "[registry]", "[http]")
                .doesNotHaveDuplicates();
    }

    @Test
    void hostedSnippetsPublishToTheRepositoryItself() {
        Repository hosted = CargoTestSupport.hosted("cargo-hosted");
        List<ConnectSnippet> snippets = CargoSnippets.of(context(hosted, "http://granary:8081", true),
                "http://granary:8081/repository/cargo-hosted");
        assertThat(snippets).extracting(ConnectSnippet::id).doesNotContain("cargo-mirror");
        assertThat(byId(snippets, "cargo-publish").code()).startsWith("cargo publish --registry granary\n")
                .doesNotContain("granary-hosted");
        assertThat(byId(snippets, "cargo-publish-manifest").code()).contains("publish = [\"granary\"]");
    }

    @Test
    void proxySnippetsHaveNoPublishing() {
        Repository proxy = CargoTestSupport.proxy("cargo-proxy", "https://index.crates.io/");
        List<ConnectSnippet> snippets = CargoSnippets.of(context(proxy, "http://granary:8081", true), null);
        assertThat(snippets).extracting(ConnectSnippet::id).contains("cargo-mirror").doesNotContain("cargo-publish");
    }

    @Test
    void httpsAddsTheCertificateHints() {
        Repository group = CargoTestSupport.group("cargo-group", "cargo-hosted");
        List<ConnectSnippet> snippets = CargoSnippets.of(context(group, "https://granary.example.com", false), null);
        assertThat(byId(snippets, "cargo-tls").code()).contains("cainfo").contains("CARGO_HTTP_CAINFO");
        assertThat(byId(snippets, "cargo-env").code()).contains("CARGO_HTTP_CAINFO");
        assertThat(byId(snippets, "cargo-registry").code()).contains("downloads need a token");
    }
}
