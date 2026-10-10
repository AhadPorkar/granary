package dev.granary.format.terraform;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerraformSnippetsTest {

    private static ConnectContext context(Repository repo, String server) {
        return new ConnectContext(repo, server + "/repository/" + repo.getName(), server, "alice", "gry_secret", true);
    }

    @Test
    void everyClientIsCoveredForAHostedRepository() {
        Repository hosted = TerraformTestSupport.hosted("terraform-hosted");
        List<ConnectSnippet> snippets = TerraformSnippets.build(context(hosted, "https://granary.example.com"));
        assertThat(snippets).extracting(ConnectSnippet::id).containsExactly(
                "required-providers", "module-source", "terraformrc-network-mirror", "terraformrc-credentials",
                "tf-cli-config-file", "tf-token-env", "tf-registry-discovery-retry", "tofurc", "tofu-init",
                "publish-provider-curl", "publish-module-curl", "providers-mirror-seed");
        assertThat(snippets).extracting(ConnectSnippet::id).doesNotHaveDuplicates();
        String all = String.join("\n", snippets.stream().map(ConnectSnippet::code).toList());
        assertThat(all).contains("source  = \"granary.example.com/hashicorp/aws\"")
                .contains("url = \"https://granary.example.com/repository/terraform-hosted/mirror/\"")
                .contains("credentials \"granary.example.com\"")
                .contains("token = \"gry_secret\"")
                .contains("TF_CLI_CONFIG_FILE").contains("TOFU_CLI_CONFIG_FILE")
                .contains("export TF_TOKEN_granary_example_com=gry_secret")
                .contains("TF_REGISTRY_DISCOVERY_RETRY=3").contains("TF_REGISTRY_CLIENT_TIMEOUT=30")
                .contains("tofu init")
                .contains("curl -fsS -u alice:gry_secret -T")
                .contains("/v1/providers/$NS/$TYPE/$VERSION/$os/$arch/$z")
                .contains("/v1/providers/$NS/$TYPE/$VERSION/SHA256SUMS\"")
                .contains("/v1/providers/$NS/$TYPE/$VERSION/SHA256SUMS.sig\"")
                .contains("/v1/providers/$NS/$TYPE/gpg-key")
                .contains("/v1/modules/acme/demo/local/1.0.0.tgz")
                .contains("terraform providers mirror")
                .doesNotContain("Put a TLS reverse proxy"); // already https
        assertThat(snippets.stream().filter(s -> s.filename() != null).map(ConnectSnippet::filename))
                .contains("main.tf", "~/.terraformrc", "~/.tofurc");
        assertThat(snippets.stream().map(ConnectSnippet::language)).containsOnly("hcl", "shell");
        assertThat(snippets.stream().map(ConnectSnippet::title)).allSatisfy(t -> assertThat(t).isNotBlank());
    }

    @Test
    void seedingFromAPublicRegistryUploadsEverythingTheCliNeeds() {
        Repository hosted = TerraformTestSupport.hosted("terraform-hosted");
        ConnectSnippet seed = TerraformSnippets.build(context(hosted, "https://granary.example.com")).stream()
                .filter(s -> s.id().equals("providers-mirror-seed")).findFirst().orElseThrow();
        // an archive without SHA256SUMS, its signature and the public key is listed but refused by terraform init
        assertThat(seed.code())
                .contains("terraform providers mirror")
                .contains("/v1/providers/hashicorp/null/$v/$os/$arch/$z\"")
                .contains("/v1/providers/hashicorp/null/$v/SHA256SUMS\"")
                .contains("/v1/providers/hashicorp/null/$v/SHA256SUMS.sig\"")
                .contains("/v1/providers/hashicorp/null/gpg-key\"")
                .contains("${z%.zip}")
                .doesNotContain("%%");
    }

    @Test
    void proxiesAndGroupsGetNoPublishSnippets() {
        Repository proxy = TerraformTestSupport.proxy("terraform-registry", "https://registry.terraform.io/");
        List<ConnectSnippet> snippets = TerraformSnippets.build(context(proxy, "https://granary.example.com"));
        assertThat(snippets).extracting(ConnectSnippet::id).doesNotContain("publish-provider-curl", "publish-module-curl", "providers-mirror-seed");
        Repository group = TerraformTestSupport.group("terraform-group", "a", "b");
        assertThat(TerraformSnippets.build(context(group, "https://granary.example.com"))).extracting(ConnectSnippet::id)
                .doesNotContain("publish-provider-curl");
    }

    @Test
    void plainHttpServersGetTheHttpsWarningAndPortsCannotUseTokenVariables() {
        Repository hosted = TerraformTestSupport.hosted("terraform-hosted");
        List<ConnectSnippet> snippets = TerraformSnippets.build(context(hosted, "http://localhost:8081"));
        String providers = snippets.get(0).code();
        assertThat(providers).contains("Put a TLS reverse proxy").contains("GRANARY_BASE_URL=https://");
        ConnectSnippet env = snippets.stream().filter(s -> s.id().equals("tf-token-env")).findFirst().orElseThrow();
        assertThat(env.code()).contains("cannot carry a port (localhost:8081)").doesNotContain("export TF_TOKEN_");
    }

    @Test
    void tokenEnvironmentVariableEncoding() {
        assertThat(TerraformSnippets.envHost("app.terraform.io")).isEqualTo("app_terraform_io");
        assertThat(TerraformSnippets.envHost("my-granary.example.com")).isEqualTo("my__granary_example_com");
        assertThat(TerraformSnippets.tokenEnv("granary.example.com", "<api-token>")).contains("export TF_TOKEN_granary_example_com=<api-token>");
    }

    @Test
    void placeholdersAreUsedForAnonymousCallers() {
        Repository hosted = TerraformTestSupport.hosted("terraform-hosted");
        ConnectContext ctx = new ConnectContext(hosted, "https://h/repository/terraform-hosted", "https://h",
                ConnectContext.USERNAME_PLACEHOLDER, ConnectContext.TOKEN_PLACEHOLDER, false);
        String all = String.join("\n", TerraformSnippets.build(ctx).stream().map(ConnectSnippet::code).toList());
        assertThat(all).contains("-u <username>:<api-token>").contains("token = \"<api-token>\"").doesNotContain("gry_");
    }
}
