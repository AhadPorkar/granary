package dev.granary.format.hex;

import static dev.granary.format.hex.HexTestSupport.group;
import static dev.granary.format.hex.HexTestSupport.hosted;
import static dev.granary.format.hex.HexTestSupport.proxy;
import static dev.granary.format.hex.HexTestSupport.withSetting;
import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class HexSnippetsTest {

    private static final String PEM = "-----BEGIN PUBLIC KEY-----\nABC\nDEF\n-----END PUBLIC KEY-----\n";
    private static final String FINGERPRINT = "SHA256:abcdef";

    private static ConnectContext ctx(Repository repo, String server, boolean anonymous) {
        return new ConnectContext(repo, server + "/repository/" + repo.getName(), server, "alice", "<api-token>", anonymous);
    }

    private static Map<String, ConnectSnippet> byId(List<ConnectSnippet> snippets) {
        return snippets.stream().collect(Collectors.toMap(ConnectSnippet::id, Function.identity()));
    }

    @Test
    void aProxyIsAHexMirror() {
        Map<String, ConnectSnippet> s = byId(HexSnippets.of(ctx(proxy("hexpm", "https://repo.hex.pm/"), "http://g.test", true),
                PEM, FINGERPRINT, null));
        assertThat(s).containsOnlyKeys("mix-hex-mirror", "mix-repo-set", "mix-local-hex", "rebar3-hex-cdn",
                "rebar3-config-mirror", "mix-unsafe-registry");
        assertThat(s.get("mix-hex-mirror").code()).contains("export HEX_MIRROR=http://g.test/repository/hexpm\n")
                .doesNotContain("HEX_CACERTS_PATH").doesNotContain("anonymous downloads are off");
        assertThat(s.get("mix-repo-set").code()).contains("mix hex.repo set hexpm --url http://g.test/repository/hexpm\n");
        assertThat(s.get("rebar3-hex-cdn").code()).contains("export HEX_CDN=http://g.test/repository/hexpm");
        assertThat(s.get("rebar3-config-mirror").code()).contains("repo_url => <<\"http://g.test/repository/hexpm\">>")
                .doesNotContain("repo_key");
        assertThat(s.get("rebar3-config-mirror").filename()).isEqualTo("~/.config/rebar3/rebar.config");
    }

    @Test
    void aProxyBehindAuthenticationNeedsTheKey() {
        Map<String, ConnectSnippet> s = byId(HexSnippets.of(ctx(proxy("hexpm", "https://repo.hex.pm/"), "https://g.test", false),
                PEM, FINGERPRINT, null));
        assertThat(s.get("mix-hex-mirror").code()).contains("anonymous downloads are off").contains("HEX_CACERTS_PATH");
        assertThat(s.get("mix-repo-set").code()).contains("--auth-key <api-token> --no-oauth-exchange");
        assertThat(s.get("rebar3-config-mirror").code()).contains("repo_key => <<\"<api-token>\">>");
    }

    @Test
    void aHostedRepositoryIsAddedWithGranarysKey() {
        Repository repo = withSetting(hosted("hex-hosted"), HexSettings.REPOSITORY_NAME, "acme");
        Map<String, ConnectSnippet> s = byId(HexSnippets.of(ctx(repo, "http://g.test", false), PEM, FINGERPRINT, null));
        assertThat(s).containsOnlyKeys("mix-repo-add", "mix-deps", "mix-publish", "mix-package", "rebar3-config",
                "rebar3-publish-config", "rebar3-publish", "public-key");
        assertThat(s.get("mix-repo-add").code())
                .contains("mix hex.repo add acme http://g.test/repository/hex-hosted --fetch-public-key SHA256:abcdef "
                        + "--auth-key <api-token>\n")
                .contains("curl -fsSL -u alice:<api-token> http://g.test/repository/hex-hosted/public_key -o granary-acme.pem")
                .contains("--public-key granary-acme.pem --auth-key <api-token>");
        assertThat(s.get("mix-deps").code()).contains("repo: \"acme\"");
        assertThat(s.get("mix-publish").code()).contains("export HEX_API_URL=http://g.test/repository/hex-hosted/api")
                .contains("export HEX_API_KEY=<api-token>").contains("mix hex.publish --revert 0.1.0")
                .contains("mix hex.retire my_package 0.1.0 security");
        String rebar = s.get("rebar3-config").code();
        assertThat(rebar).contains("name => <<\"acme\">>").contains("repo_key => <<\"<api-token>\">>")
                .contains("api_url => <<\"http://g.test/repository/hex-hosted/api\">>")
                .contains("repo_public_key => <<\"-----BEGIN PUBLIC KEY-----\\nABC\\nDEF\\n-----END PUBLIC KEY-----\\n\">>");
        // rebar3_hex 7.3 fails on a repository map without api_organization and api_repository, and has no
        // API-key login command, so the key goes into the (global) repository map
        ConnectSnippet publishConfig = s.get("rebar3-publish-config");
        assertThat(publishConfig.filename()).isEqualTo("~/.config/rebar3/rebar.config");
        assertThat(publishConfig.code()).contains("name => <<\"acme\">>")
                .contains("api_url => <<\"http://g.test/repository/hex-hosted/api\">>")
                .contains("api_key => <<\"<api-token>\">>")
                .contains("api_organization => undefined").contains("api_repository => undefined");
        assertThat(s.get("rebar3-publish").code()).contains("{plugins, [rebar3_hex]}")
                .contains("rebar3 hex publish package --repo acme --yes")
                .contains("rebar3 hex publish --revert 0.1.0 --repo acme --yes")
                .contains("rebar3 hex retire my_package 0.1.0 security --message \"use 0.1.1\" --repo acme")
                .doesNotContain("hex auth");
        assertThat(s.get("public-key").code()).isEqualTo(PEM);
        assertThat(s.get("public-key").filename()).isEqualTo("granary-acme.pem");
    }

    @Test
    void anonymousReadLeavesCredentialsOutOfReads() {
        Map<String, ConnectSnippet> s = byId(HexSnippets.of(ctx(hosted("hex-hosted"), "http://g.test", true), PEM, FINGERPRINT,
                null));
        assertThat(s.get("mix-repo-add").code()).doesNotContain("--auth-key").doesNotContain("-u alice");
        assertThat(s.get("rebar3-config").code()).doesNotContain("repo_key");
        assertThat(s.get("mix-publish").code()).contains("HEX_API_KEY"); // publishing always needs a key
    }

    @Test
    void aGroupIsReadOnlyAndPointsPublishingAtItsHostedMember() {
        Map<String, ConnectSnippet> s = byId(HexSnippets.of(ctx(group("hex-group"), "http://g.test", false), PEM, FINGERPRINT,
                "hex-hosted"));
        assertThat(s).containsOnlyKeys("mix-repo-add", "mix-deps", "mix-publish", "rebar3-config", "public-key");
        assertThat(s.get("mix-repo-add").code()).contains("mix hex.repo add hex-group http://g.test/repository/hex-group");
        assertThat(s.get("mix-publish").code()).contains("HEX_API_URL=http://g.test/repository/hex-hosted/api");
        assertThat(s.get("rebar3-config").code()).doesNotContain("api_url");

        Map<String, ConnectSnippet> lonely = byId(HexSnippets.of(ctx(group("hex-group"), "http://g.test", false), PEM,
                FINGERPRINT, null));
        assertThat(lonely).doesNotContainKey("mix-publish");
    }
}
