package dev.granary.format.conan;

import static dev.granary.format.conan.ConanTestSupport.group;
import static dev.granary.format.conan.ConanTestSupport.hosted;
import static dev.granary.format.conan.ConanTestSupport.proxy;
import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ConanSnippetsTest {

    private static final String SERVER = "https://granary.example.com";

    private static ConnectContext context(Repository repo, boolean anonymousRead) {
        return new ConnectContext(repo, SERVER + "/repository/" + repo.getName(), SERVER, "alice",
                ConnectContext.TOKEN_PLACEHOLDER, anonymousRead);
    }

    private static Map<String, ConnectSnippet> byId(List<ConnectSnippet> snippets) {
        return snippets.stream().collect(Collectors.toMap(ConnectSnippet::id, s -> s));
    }

    @Test
    void everyClientCommandIsCovered() {
        Map<String, ConnectSnippet> snippets = byId(ConanSnippets.build(context(group("conan-group"), true), "conan-hosted"));
        assertThat(snippets).containsOnlyKeys("remote-add", "login", "env", "install", "upload", "search", "tls", "conan1");
        String url = SERVER + "/repository/conan-group";

        assertThat(snippets.get("remote-add").code())
                .contains("conan remote add granary " + url + " --index 0")
                .contains("conan remote remove conancenter")
                .contains("conan remote disable conancenter");
        assertThat(snippets.get("login").code()).contains("conan remote login granary alice -p <api-token>")
                .contains("anonymous downloads are enabled");
        assertThat(snippets.get("env").code()).contains("CONAN_LOGIN_USERNAME_GRANARY=alice")
                .contains("CONAN_PASSWORD_GRANARY=<api-token>");
        assertThat(snippets.get("install").code()).contains("conan install . -r granary")
                .contains("conan install --requires=zlib/1.3.1 -r granary");
        assertThat(snippets.get("search").code()).contains("conan search \"*\" -r granary").contains("conan list");
        assertThat(snippets.get("tls").filename()).isEqualTo("global.conf");
        assertThat(snippets.get("tls").code()).contains("core.net.http:cacert_path").contains("-cc core.net.http:cacert_path=")
                .contains("--insecure");
        assertThat(snippets.get("conan1").code()).contains("conan remote add granary " + url)
                .contains("revisions_enabled=1").contains("False").contains("conan user -r granary -p <api-token> alice")
                .contains("conan install zlib/1.3.1@ -r granary")
                .contains("conan remote add granary-hosted " + SERVER + "/repository/conan-hosted")
                .contains("conan upload \"*\" -r granary-hosted --all --confirm");
        for (ConnectSnippet snippet : snippets.values()) {
            assertThat(snippet.title()).isNotBlank();
            assertThat(snippet.code()).isNotBlank().doesNotContain("null");
            assertThat(snippet.language()).isIn("shell", "ini");
        }
    }

    @Test
    void groupUploadsGoToTheHostedMember() {
        Map<String, ConnectSnippet> snippets = byId(ConanSnippets.build(context(group("conan-group"), false), "conan-hosted"));
        assertThat(snippets.get("upload").code())
                .contains("conan remote add granary-hosted " + SERVER + "/repository/conan-hosted")
                .contains("conan remote login granary-hosted alice")
                .contains("conan upload \"*\" -r granary-hosted --confirm");
        assertThat(snippets.get("login").code()).doesNotContain("anonymous downloads");
    }

    @Test
    void groupWithoutHostedMemberExplainsWhy() {
        Map<String, ConnectSnippet> snippets = byId(ConanSnippets.build(context(group("conan-group"), true), null));
        assertThat(snippets.get("upload").code()).contains("no hosted member").doesNotContain("conan upload");
        assertThat(snippets.get("conan1").code()).doesNotContain("conan upload");
    }

    @Test
    void hostedUploadsDirectlyAndKeepsConanCenter() {
        Map<String, ConnectSnippet> snippets = byId(ConanSnippets.build(context(hosted("conan-hosted"), true), "conan-hosted"));
        assertThat(snippets.get("upload").code()).contains("conan create .")
                .contains("conan upload \"*\" -r granary --confirm").contains("--only-recipe");
        assertThat(snippets.get("conan1").code()).contains("conan upload \"*\" -r granary --all --confirm")
                .doesNotContain("granary-hosted");
        assertThat(snippets.get("remote-add").code()).doesNotContain("conan remote remove conancenter");
    }

    @Test
    void proxyIsReadOnly() {
        Map<String, ConnectSnippet> snippets = byId(ConanSnippets.build(
                context(proxy("conancenter", ConanFormatHandler.CONANCENTER), true), null));
        assertThat(snippets.get("upload").code()).contains("read-only").doesNotContain("conan upload");
        assertThat(snippets.get("conan1").code()).doesNotContain("conan upload");
        assertThat(snippets.get("remote-add").code()).contains("conan remote remove conancenter");
    }

    @Test
    void environmentVariablesFollowTheRemoteName() {
        assertThat(ConanSnippets.ENV_SUFFIX).isEqualTo("GRANARY");
        assertThat(ConanSnippets.REMOTE).isEqualTo("granary");
    }
}
