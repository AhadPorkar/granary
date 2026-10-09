package dev.granary.format.nuget;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NuGetSnippetsTest {

    private static final String URL = "https://granary.example.com/repository/nuget-group";
    private static final String INDEX = URL + "/index.json";
    private static final List<String> IDS = List.of("nuget-source", "nuget-push", "nuget-config", "nuget-exe-source",
            "nuget-exe-push", "paket", "visual-studio");
    private static final Set<String> WITH_USERNAME = Set.of("nuget-source", "nuget-config", "nuget-exe-source", "paket", "visual-studio");

    private static ConnectContext context(String username) {
        Repository repo = new Repository();
        repo.setName("nuget-group");
        repo.setFormat(NuGetFormatHandler.FORMAT);
        repo.setType(RepositoryType.GROUP);
        return new ConnectContext(repo, URL, "https://granary.example.com", username, ConnectContext.TOKEN_PLACEHOLDER, true);
    }

    private static List<ConnectSnippet> snippets() {
        return NuGetConnectSnippets.of(context(ConnectContext.USERNAME_PLACEHOLDER));
    }

    private static ConnectSnippet snippet(String id) {
        return snippets().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void idsAreStableAndUnique() {
        assertThat(snippets().stream().map(ConnectSnippet::id)).containsExactlyElementsOf(IDS);
    }

    @Test
    void everySnippetPointsAtTheServiceIndexAndCarriesTheToken() {
        for (ConnectSnippet s : snippets()) {
            assertThat(s.code()).as(s.id()).contains(INDEX).contains(ConnectContext.TOKEN_PLACEHOLDER);
            assertThat(s.title()).as(s.id()).isNotBlank();
            assertThat(s.language()).as(s.id()).isIn("shell", "ini", "xml", "json", "yaml", "toml", "text");
            if (WITH_USERNAME.contains(s.id())) {
                assertThat(s.code()).as(s.id()).contains(ConnectContext.USERNAME_PLACEHOLDER);
            } else {
                assertThat(s.code()).as(s.id()).doesNotContain(ConnectContext.USERNAME_PLACEHOLDER);
            }
        }
    }

    @Test
    void theCallersUsernameReplacesThePlaceholder() {
        List<ConnectSnippet> snippets = NuGetConnectSnippets.of(context("bob"));
        assertThat(snippets.get(0).code()).isEqualTo("dotnet nuget add source " + INDEX
                + " -n granary -u bob -p <api-token> --store-password-in-clear-text");
        for (ConnectSnippet s : snippets) {
            assertThat(s.code()).as(s.id()).doesNotContain(ConnectContext.USERNAME_PLACEHOLDER);
        }
    }

    @Test
    void dotnetCommands() {
        assertThat(snippet("nuget-push").code()).isEqualTo("dotnet nuget push ./MyPackage.1.0.0.nupkg -k <api-token> -s " + INDEX);
    }

    @Test
    void nugetConfigHasSourceCredentialsAndApiKey() {
        ConnectSnippet config = snippet("nuget-config");
        assertThat(config.language()).isEqualTo("xml");
        assertThat(config.filename()).isEqualTo("nuget.config");
        assertThat(config.code())
                .startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                .contains("<clear />")
                .contains("<add key=\"granary\" value=\"" + INDEX + "\" />")
                .contains("<packageSourceCredentials>")
                .contains("<add key=\"Username\" value=\"<username>\" />")
                .contains("<add key=\"ClearTextPassword\" value=\"<api-token>\" />")
                .contains("<apikeys>")
                .contains("<add key=\"" + INDEX + "\" value=\"<api-token>\" />");
    }

    @Test
    void nugetExePaketAndVisualStudio() {
        assertThat(snippet("nuget-exe-source").code())
                .isEqualTo("nuget sources add -Name granary -Source " + INDEX
                        + " -UserName <username> -Password <api-token> -StorePasswordInClearText");
        assertThat(snippet("nuget-exe-push").code())
                .contains("nuget setapikey <api-token> -Source " + INDEX)
                .contains("nuget push MyPackage.1.0.0.nupkg -Source " + INDEX + " -ApiKey <api-token>");
        ConnectSnippet paket = snippet("paket");
        assertThat(paket.filename()).isEqualTo("paket.dependencies");
        assertThat(paket.code()).isEqualTo("source " + INDEX + " username: \"<username>\" password: \"<api-token>\"");
        ConnectSnippet vs = snippet("visual-studio");
        assertThat(vs.language()).isEqualTo("text");
        assertThat(vs.filename()).isNull();
        assertThat(vs.code()).contains("Package Sources").contains("Source: " + INDEX);
    }

    @Test
    void theIndexIsDerivedFromTheRepositoryUrl() {
        Repository repo = new Repository();
        repo.setName("nuget");
        repo.setFormat(NuGetFormatHandler.FORMAT);
        repo.setType(RepositoryType.HOSTED);
        ConnectContext ctx = new ConnectContext(repo, "http://localhost:8081/repository/nuget", "http://localhost:8081",
                "bob", ConnectContext.TOKEN_PLACEHOLDER, false);
        assertThat(NuGetConnectSnippets.of(ctx).get(0).code()).contains("http://localhost:8081/repository/nuget/index.json");
    }
}
