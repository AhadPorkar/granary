package dev.granary.format.composer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ComposerSnippetsTest {

    @Test
    void httpsGroupGetsConfigurationAndCredentialsButNoPublishing() {
        Map<String, ConnectSnippet> snippets = snippets(RepositoryType.GROUP, "https://granary.example.com/repository/composer-group",
                "https://granary.example.com", true);

        assertThat(snippets).containsKeys("composer-global", "composer-project", "composer-json", "composer-auth", "auth-json",
                "composer-auth-env", "satis-comparison");
        assertThat(snippets).doesNotContainKeys("secure-http", "publish-curl");
        assertThat(snippets.get("composer-global").code())
                .contains("composer config -g repositories.granary composer https://granary.example.com/repository/composer-group")
                .contains("composer config -g repo.packagist false")
                .doesNotContain("secure-http");
        assertThat(snippets.get("composer-json").filename()).isEqualTo("composer.json");
        assertThat(snippets.get("composer-json").code())
                .contains("\"type\": \"composer\"")
                .contains("{\"packagist.org\": false}")
                .doesNotContain("secure-http");
        assertThat(snippets.get("composer-auth").code())
                .contains("composer config -g http-basic.granary.example.com <username> <api-token>")
                .contains("bearer.granary.example.com");
        assertThat(snippets.get("auth-json").code()).contains("\"granary.example.com\"").contains("\"password\": \"<api-token>\"");
        assertThat(snippets.get("composer-auth-env").code()).contains("COMPOSER_AUTH").contains("\"granary.example.com\"");
    }

    @Test
    void plainHttpNeedsSecureHttpOffWithAWarning() {
        Map<String, ConnectSnippet> snippets = snippets(RepositoryType.HOSTED, "http://localhost:8080/repository/composer-hosted",
                "http://localhost:8080", false);

        assertThat(snippets.get("secure-http").code()).contains("Warning").contains("composer config secure-http false");
        assertThat(snippets.get("composer-global").code()).contains("composer config -g secure-http false");
        assertThat(snippets.get("composer-json").code()).contains("\"secure-http\": false");
        // Composer keys credentials by host and port
        assertThat(snippets.get("composer-auth").code()).contains("http-basic.localhost:8080 ");
        assertThat(snippets.get("composer-auth").code()).contains("requires credentials for downloads");
    }

    @Test
    void hostedRepositoriesExplainPublishing() {
        ConnectSnippet publish = snippets(RepositoryType.HOSTED, "https://g.example/repository/composer-hosted", "https://g.example", true)
                .get("publish-curl");
        assertThat(publish.code())
                .contains("--upload-file")
                .contains("https://g.example/repository/composer-hosted/packages/<vendor>/<package>/1.0.0")
                .contains("-F package=@")
                .contains("-X DELETE");
    }

    @Test
    void authHostIsHostAndNonDefaultPort() {
        assertThat(ComposerSnippets.authHost("https://g.example/repository/x")).isEqualTo("g.example");
        assertThat(ComposerSnippets.authHost("https://g.example:443/repository/x")).isEqualTo("g.example");
        assertThat(ComposerSnippets.authHost("http://g.example:80/x")).isEqualTo("g.example");
        assertThat(ComposerSnippets.authHost("https://g.example:8443/x")).isEqualTo("g.example:8443");
    }

    private static Map<String, ConnectSnippet> snippets(RepositoryType type, String url, String server, boolean anonymousRead) {
        Repository repo = ComposerTestSupport.repository(url.substring(url.lastIndexOf('/') + 1), type);
        List<ConnectSnippet> list = ComposerSnippets.of(new ConnectContext(repo, url, server,
                ConnectContext.USERNAME_PLACEHOLDER, ConnectContext.TOKEN_PLACEHOLDER, anonymousRead));
        return list.stream().collect(Collectors.toMap(ConnectSnippet::id, Function.identity()));
    }
}
