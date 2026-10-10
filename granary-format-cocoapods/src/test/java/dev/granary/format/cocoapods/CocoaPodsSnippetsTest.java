package dev.granary.format.cocoapods;

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

class CocoaPodsSnippetsTest {

    private static ConnectContext context(RepositoryType type, String serverUrl, boolean anonymousRead) {
        Repository repo = CocoaPodsTestSupport.repository("cocoapods-" + type.name().toLowerCase(), type);
        return new ConnectContext(repo, serverUrl + "/repository/" + repo.getName(), serverUrl,
                ConnectContext.USERNAME_PLACEHOLDER, ConnectContext.TOKEN_PLACEHOLDER, anonymousRead);
    }

    private static Map<String, ConnectSnippet> byId(ConnectContext ctx) {
        return CocoaPodsSnippets.of(ctx).stream().collect(Collectors.toMap(ConnectSnippet::id, Function.identity()));
    }

    @Test
    void groupsAndProxiesGetTheClientSnippetsOnly() {
        for (RepositoryType type : List.of(RepositoryType.GROUP, RepositoryType.PROXY)) {
            List<ConnectSnippet> snippets = CocoaPodsSnippets.of(context(type, "https://granary.example.com", true));
            assertThat(snippets).extracting(ConnectSnippet::id)
                    .containsExactly("podfile", "pod-repo-add-cdn", "pod-install", "pod-search", "netrc", "pod-env");
        }
    }

    @Test
    void hostedRepositoriesAlsoGetThePublishingSnippets() {
        List<ConnectSnippet> snippets = CocoaPodsSnippets.of(context(RepositoryType.HOSTED, "https://granary.example.com", true));
        assertThat(snippets).extracting(ConnectSnippet::id).containsExactly("podfile", "pod-repo-add-cdn", "pod-install",
                "pod-search", "netrc", "pod-env", "pod-publish", "pod-publish-archive", "pod-delete", "pod-trunk");
        assertThat(snippets).extracting(ConnectSnippet::id).doesNotHaveDuplicates();
        assertThat(snippets).allSatisfy(s -> {
            assertThat(s.title()).isNotBlank();
            assertThat(s.code()).endsWith("\n");
            assertThat(s.code()).matches("\\A\\p{ASCII}*\\z");
        });
    }

    @Test
    void sourcesCarryATrailingSlash() {
        Map<String, ConnectSnippet> snippets = byId(context(RepositoryType.GROUP, "https://granary.example.com", true));
        ConnectSnippet podfile = snippets.get("podfile");
        assertThat(podfile.filename()).isEqualTo("Podfile");
        assertThat(podfile.language()).isEqualTo("ruby");
        assertThat(podfile.code()).startsWith("source 'https://granary.example.com/repository/cocoapods-group/'\n")
                .contains("pod 'AFNetworking'").doesNotContain("cdn.cocoapods.org");
        assertThat(snippets.get("pod-repo-add-cdn").code())
                .contains("pod repo add-cdn granary https://granary.example.com/repository/cocoapods-group/\n");
        assertThat(snippets.get("pod-install").code()).contains("pod repo update granary").contains("pod install");
        assertThat(snippets.get("pod-search").code()).contains("pod search AFNetworking --simple");
        assertThat(snippets.get("pod-env").code()).contains("COCOAPODS_DISABLE_STATS=true");
    }

    @Test
    void aHostedPodfileMentionsTrunk() {
        Map<String, ConnectSnippet> snippets = byId(context(RepositoryType.HOSTED, "https://granary.example.com", true));
        assertThat(snippets.get("podfile").code()).contains("source 'https://cdn.cocoapods.org/'");
    }

    @Test
    void publishingUsesTheRepositoryUrlAndPlaceholders() {
        Map<String, ConnectSnippet> snippets = byId(context(RepositoryType.HOSTED, "http://localhost:8080", true));
        String url = "http://localhost:8080/repository/cocoapods-hosted";
        assertThat(snippets.get("pod-publish").code())
                .contains("pod ipc spec MyPod.podspec > MyPod.podspec.json")
                .contains("curl -u <username>:<api-token> -X PUT")
                .contains(url + "/Specs/MyPod/1.0.0/MyPod.podspec.json");
        assertThat(snippets.get("pod-publish-archive").code())
                .contains("-F podspec=@MyPod.podspec.json -F source=@MyPod-1.0.0.zip").contains(url + "/api/pods");
        assertThat(snippets.get("pod-delete").code()).contains("-X DELETE " + url + "/Specs/MyPod/1.0.0");
        assertThat(snippets.get("pod-trunk").code()).contains("pod trunk push").contains(url + "/Specs/MyPod/1.0.0");
    }

    @Test
    void netrcEntriesAreKeyedByHostWithoutPort() {
        ConnectSnippet netrc = byId(context(RepositoryType.GROUP, "http://localhost:8080", false)).get("netrc");
        assertThat(netrc.filename()).isEqualTo("~/.netrc (chmod 600)");
        assertThat(netrc.code()).contains("machine localhost\n").contains("  login <username>\n")
                .contains("  password <api-token>\n").contains("anonymous downloads are off");
        assertThat(byId(context(RepositoryType.GROUP, "https://granary.example.com", true)).get("netrc").code())
                .contains("machine granary.example.com\n").contains("only needed when anonymous downloads are switched off");
    }

    @Test
    void hostNamesDropPortsAndPathsButKeepIpv6Brackets() {
        assertThat(CocoaPodsSnippets.hostName(context(RepositoryType.GROUP, "https://granary.example.com", true)))
                .isEqualTo("granary.example.com");
        assertThat(CocoaPodsSnippets.hostName(context(RepositoryType.GROUP, "http://10.0.0.5:8081", true))).isEqualTo("10.0.0.5");
        assertThat(CocoaPodsSnippets.hostName(context(RepositoryType.GROUP, "https://example.com/granary", true)))
                .isEqualTo("example.com");
        assertThat(CocoaPodsSnippets.hostName(context(RepositoryType.GROUP, "http://[::1]:8080", true))).isEqualTo("[::1]");
        assertThat(CocoaPodsSnippets.hostName(context(RepositoryType.GROUP, "http://[fe80::1]", true))).isEqualTo("[fe80::1]");
    }
}
