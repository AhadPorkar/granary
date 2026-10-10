package dev.granary.format.rubygems;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RubygemsSnippetsTest {

    private static final String SERVER = "https://granary.example.com:8443";

    private static ConnectContext context(Repository repo, boolean anonymousRead) {
        return new ConnectContext(repo, SERVER + "/repository/" + repo.getName(), SERVER, "alice", "gry_token",
                anonymousRead);
    }

    private static Map<String, ConnectSnippet> snippets(ConnectContext ctx) {
        return RubygemsSnippets.of(ctx).stream()
                .collect(Collectors.toMap(ConnectSnippet::id, Function.identity(), (a, b) -> a, java.util.LinkedHashMap::new));
    }

    @Test
    void hostedRepositoriesGetReadAndPublishSnippets() {
        Repository hosted = RubygemsTestSupport.hosted("gems-hosted");
        Map<String, ConnectSnippet> snippets = snippets(context(hosted, false));
        assertThat(snippets.keySet()).containsExactly("gem-sources", "gem-install", "gemrc", "gemfile",
                "bundle-credentials", "bundle-mirror", "gem-credentials", "gem-push", "gem-signin", "gem-yank",
                "gemspec-push-host");

        String url = SERVER + "/repository/gems-hosted";
        String withCredentials = "https://alice:gry_token@granary.example.com:8443/repository/gems-hosted/";
        assertThat(snippets.get("gem-sources").code()).contains("gem sources --add " + withCredentials + " --remove https://rubygems.org/");
        assertThat(snippets.get("gem-install").code()).contains("gem install --clear-sources --source " + withCredentials + " <gem>");
        assertThat(snippets.get("gemrc").filename()).isEqualTo("~/.gemrc");
        assertThat(snippets.get("gemrc").code()).contains("- " + withCredentials);
        assertThat(snippets.get("gemfile").code()).startsWith("source \"" + url + "/\"");
        assertThat(snippets.get("bundle-credentials").code())
                .contains("bundle config set --global " + url + "/ alice:gry_token")
                .contains("export BUNDLE_GRANARY__EXAMPLE__COM=alice:gry_token");
        assertThat(snippets.get("bundle-mirror").code()).contains("bundle config set --global mirror.https://rubygems.org " + url + "/");
        assertThat(snippets.get("gem-credentials").code()).contains(":granary: gry_token").contains(url + ": gry_token");
        assertThat(snippets.get("gem-push").code()).contains("gem push --host " + url + " --key granary <gem>-<version>.gem")
                .contains("GEM_HOST_API_KEY=gry_token gem push --host " + url);
        assertThat(snippets.get("gem-signin").code()).contains("RUBYGEMS_HOST=" + url + " gem signin");
        assertThat(snippets.get("gem-yank").code()).contains("gem yank --host " + url + " --key granary <gem> --version <version>");
        assertThat(snippets.get("gemspec-push-host").code()).contains("spec.metadata[\"allowed_push_host\"] = \"" + url + "\"");
    }

    @Test
    void proxiesAndGroupsOnlyGetReadSnippets() {
        for (Repository repo : List.of(RubygemsTestSupport.proxy("gems-proxy"), RubygemsTestSupport.group("gems", "a"))) {
            assertThat(snippets(context(repo, false)).keySet()).containsExactly("gem-sources", "gem-install", "gemrc",
                    "gemfile", "bundle-credentials", "bundle-mirror");
        }
    }

    @Test
    void anonymousReadLeavesCredentialsOutOfSourceUrls() {
        Map<String, ConnectSnippet> snippets = snippets(context(RubygemsTestSupport.group("gems", "a"), true));
        String url = SERVER + "/repository/gems/";
        assertThat(snippets.get("gem-sources").code()).startsWith("gem sources --add " + url + " ")
                .contains("# with credentials: gem sources --add https://alice:gry_token@");
        assertThat(snippets.get("gem-install").code()).doesNotContain("gry_token");
        assertThat(snippets.get("gemrc").code()).doesNotContain("gry_token");
        assertThat(snippets.get("bundle-mirror").code()).doesNotContain("gry_token");
    }

    @Test
    void buildsCredentialUrlsAndBundlerKeys() {
        assertThat(RubygemsSnippets.withCredentials("http://host/x/", "u", "t")).isEqualTo("http://u:t@host/x/");
        assertThat(RubygemsSnippets.withCredentials("relative/x", "u", "t")).isEqualTo("relative/x");
        assertThat(RubygemsSnippets.bundlerEnvKey("my-host.example.com")).isEqualTo("MY___HOST__EXAMPLE__COM");
        assertThat(RubygemsSnippets.hostName("granary.example.com:8443")).isEqualTo("granary.example.com");
        assertThat(RubygemsSnippets.hostName("localhost")).isEqualTo("localhost");
        assertThat(RubygemsSnippets.hostName("[::1]:8080")).isEqualTo("[::1]");
        assertThat(RubygemsSnippets.hostName("host:80/path")).isEqualTo("host");
    }
}
