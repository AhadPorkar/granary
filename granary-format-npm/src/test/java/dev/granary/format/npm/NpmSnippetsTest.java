package dev.granary.format.npm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.util.List;
import org.junit.jupiter.api.Test;

class NpmSnippetsTest {

    private static final String URL = "https://granary.example.com/repository/npm-group";
    private static final String REGISTRY = URL + "/";
    private static final String AUTH_KEY = "//granary.example.com/repository/npm-group/:_authToken";
    private static final List<String> IDS = List.of("npm-registry", "npm-npmrc", "npm-scoped", "npm-login", "npm-publish",
            "pnpm", "yarn-classic", "yarn-berry", "bun");

    private static ConnectContext context(String username) {
        Repository repo = new Repository();
        repo.setName("npm-group");
        repo.setFormat(NpmFormatHandler.FORMAT);
        repo.setType(RepositoryType.GROUP);
        return new ConnectContext(repo, URL, "https://granary.example.com", username, ConnectContext.TOKEN_PLACEHOLDER, true);
    }

    private static List<ConnectSnippet> snippets() {
        return NpmConnectSnippets.of(context(ConnectContext.USERNAME_PLACEHOLDER));
    }

    private static ConnectSnippet snippet(String id) {
        return snippets().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void idsAreStableAndUnique() {
        assertThat(snippets().stream().map(ConnectSnippet::id)).containsExactlyElementsOf(IDS);
        assertThat(new NpmFormatHandler(null, null, null, null, null, null, null).connectSnippets(context("alice"))
                .stream().map(ConnectSnippet::id)).containsExactlyElementsOf(IDS);
    }

    @Test
    void everySnippetNamesTheRegistryWithATrailingSlash() {
        for (ConnectSnippet s : snippets()) {
            assertThat(s.code()).as(s.id()).contains(REGISTRY);
            assertThat(s.title()).as(s.id()).isNotBlank();
            assertThat(s.language()).as(s.id()).isIn("shell", "ini", "xml", "json", "yaml", "toml", "text");
        }
    }

    @Test
    void tokensAreKeyedByTheRegistryWithoutScheme() {
        for (String id : List.of("npm-npmrc", "npm-scoped", "pnpm", "yarn-classic")) {
            assertThat(snippet(id).code()).as(id).contains(AUTH_KEY).contains(ConnectContext.TOKEN_PLACEHOLDER);
        }
        for (String id : List.of("yarn-berry", "bun")) {
            assertThat(snippet(id).code()).as(id).contains(ConnectContext.TOKEN_PLACEHOLDER);
        }
        for (String id : List.of("npm-registry", "npm-login", "npm-publish")) {
            assertThat(snippet(id).code()).as(id).doesNotContain(ConnectContext.TOKEN_PLACEHOLDER);
        }
        // npm never needs a username: the token alone authenticates
        for (ConnectSnippet s : snippets()) {
            assertThat(s.code()).as(s.id()).doesNotContain(ConnectContext.USERNAME_PLACEHOLDER);
        }
    }

    @Test
    void npmCommandsAndNpmrc() {
        assertThat(snippet("npm-registry").code()).isEqualTo("npm config set registry " + REGISTRY);
        ConnectSnippet npmrc = snippet("npm-npmrc");
        assertThat(npmrc.language()).isEqualTo("ini");
        assertThat(npmrc.filename()).isEqualTo(".npmrc");
        assertThat(npmrc.code()).isEqualTo("registry=" + REGISTRY + "\n" + AUTH_KEY + "=<api-token>");
        assertThat(snippet("npm-scoped").code()).startsWith("@acme:registry=" + REGISTRY);
        assertThat(snippet("npm-login").code()).isEqualTo("npm login --auth-type=legacy --registry " + REGISTRY);
        assertThat(snippet("npm-publish").code()).isEqualTo("npm publish --registry " + REGISTRY);
    }

    @Test
    void pnpmYarnAndBun() {
        assertThat(snippet("pnpm").code())
                .contains("pnpm config set registry " + REGISTRY)
                .contains("pnpm config set " + AUTH_KEY + " <api-token>");
        ConnectSnippet classic = snippet("yarn-classic");
        assertThat(classic.filename()).isEqualTo(".yarnrc");
        assertThat(classic.code()).startsWith("registry \"" + REGISTRY + "\"");
        ConnectSnippet berry = snippet("yarn-berry");
        assertThat(berry.language()).isEqualTo("yaml");
        assertThat(berry.filename()).isEqualTo(".yarnrc.yml");
        assertThat(berry.code())
                .contains("npmRegistryServer: \"" + REGISTRY + "\"")
                .contains("npmAuthToken: \"<api-token>\"")
                .contains("npmScopes:")
                .contains("npmPublishRegistry: \"" + REGISTRY + "\"");
        ConnectSnippet bun = snippet("bun");
        assertThat(bun.language()).isEqualTo("toml");
        assertThat(bun.filename()).isEqualTo("bunfig.toml");
        assertThat(bun.code()).contains("[install]").contains("registry = { url = \"" + REGISTRY + "\", token = \"<api-token>\" }")
                .contains("[install.scopes]");
    }

    @Test
    void plainHttpRegistriesKeepTheirPort() {
        Repository repo = new Repository();
        repo.setName("npm");
        repo.setFormat(NpmFormatHandler.FORMAT);
        repo.setType(RepositoryType.HOSTED);
        ConnectContext ctx = new ConnectContext(repo, "http://localhost:8081/repository/npm", "http://localhost:8081",
                "bob", ConnectContext.TOKEN_PLACEHOLDER, false);
        ConnectSnippet npmrc = NpmConnectSnippets.of(ctx).get(1);
        assertThat(npmrc.code()).isEqualTo("registry=http://localhost:8081/repository/npm/\n//localhost:8081/repository/npm/:_authToken=<api-token>");
    }
}
