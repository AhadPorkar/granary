package dev.granary.format.terraform;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import java.util.ArrayList;
import java.util.List;

/**
 * Ready-to-paste configuration for the Terraform and OpenTofu CLIs. Both clients share the registry
 * protocols and the CLI configuration syntax; OpenTofu reads {@code ~/.tofurc} (falling back to
 * {@code ~/.terraformrc}) and honours the same {@code TF_TOKEN_<host>} variables.
 */
final class TerraformSnippets {

    static final String HCL = "hcl";

    private TerraformSnippets() {
    }

    static List<ConnectSnippet> build(ConnectContext ctx) {
        List<ConnectSnippet> snippets = new ArrayList<>();
        String host = ctx.host();
        String repo = ctx.repository().getName();
        String httpsNote = ctx.https() ? "" : """
                # Terraform and OpenTofu only talk to registries over HTTPS. Put a TLS reverse proxy
                # (Caddy, nginx, Traefik) in front of Granary and set GRANARY_BASE_URL=https://<host>.
                """;
        boolean hosted = ctx.repository().isHosted();

        snippets.add(ConnectSnippet.file("required-providers", "Providers from this registry (main.tf)", HCL, "main.tf",
                httpsNote + """
                # <host>/.well-known/terraform.json must point at this repository (setting terraform.rootRepository,
                # which the terraform-group template sets); the namespace/type part is what the upstream registry uses.
                terraform {
                  required_providers {
                    aws = {
                      source  = "%s/hashicorp/aws"
                      version = "~> 5.0"
                    }
                  }
                }
                """.formatted(host)));

        snippets.add(ConnectSnippet.file("module-source", "Modules from this registry (main.tf)", HCL, "main.tf", """
                module "vpc" {
                  source  = "%s/<namespace>/<name>/<system>"   # e.g. "%s/acme/vpc/aws"
                  version = "~> 1.0"
                }
                """.formatted(host, host)));

        snippets.add(ConnectSnippet.file("terraformrc-network-mirror", "Provider network mirror (~/.terraformrc)", HCL,
                "~/.terraformrc", httpsNote + """
                # Installs every provider through Granary without changing provider addresses; the mirror URL
                # must be HTTPS and end with a slash. "direct" is not listed, so nothing bypasses Granary.
                provider_installation {
                  network_mirror {
                    url = "%s/mirror/"
                  }
                }
                """.formatted(ctx.url())));

        snippets.add(ConnectSnippet.file("terraformrc-credentials", "Registry credentials (~/.terraformrc)", HCL,
                "~/.terraformrc", """
                # Needed when anonymous reads are disabled; the token is a Granary API token.
                credentials "%s" {
                  token = "%s"
                }
                """.formatted(host, ctx.token())));

        snippets.add(ConnectSnippet.shell("tf-cli-config-file", "Use a dedicated CLI configuration file", """
                # Point both CLIs at a file holding the provider_installation and credentials blocks above.
                export TF_CLI_CONFIG_FILE="$HOME/granary.tfrc"
                export TOFU_CLI_CONFIG_FILE="$HOME/granary.tfrc"
                """));

        snippets.add(ConnectSnippet.shell("tf-token-env", "Registry token from the environment", tokenEnv(host, ctx.token())));

        snippets.add(ConnectSnippet.shell("tf-registry-discovery-retry", "Registry discovery retries and timeout", """
                # How often terraform/tofu retry the registry discovery request and how long they wait for the registry.
                export TF_REGISTRY_DISCOVERY_RETRY=3
                export TF_REGISTRY_CLIENT_TIMEOUT=30
                """));

        snippets.add(ConnectSnippet.file("tofurc", "OpenTofu CLI configuration (~/.tofurc)", HCL, "~/.tofurc", httpsNote + """
                # OpenTofu reads ~/.tofurc (or TOFU_CLI_CONFIG_FILE) and falls back to ~/.terraformrc; the syntax is the same.
                provider_installation {
                  network_mirror {
                    url = "%s/mirror/"
                  }
                }

                credentials "%s" {
                  token = "%s"
                }
                """.formatted(ctx.url(), host, ctx.token())));

        snippets.add(ConnectSnippet.shell("tofu-init", "OpenTofu", """
                # tofu uses the same provider and module addresses as terraform
                tofu init
                tofu providers lock -platform=linux_amd64
                """));

        if (hosted) {
            snippets.add(ConnectSnippet.shell("publish-provider-curl", "Publish a provider release (curl)", """
                    # One zip per platform, then the SHA256SUMS file, its detached GPG signature and the public key.
                    # The file names must match the lines of SHA256SUMS; the key signs SHA256SUMS.
                    NS=acme; TYPE=demo; VERSION=1.0.0
                    for z in terraform-provider-${TYPE}_${VERSION}_*_*.zip; do
                      IFS=_ read -r _ _ os arch <<< "${z%%.zip}"
                      curl -fsS -u %s:%s -T "$z" "%s/v1/providers/$NS/$TYPE/$VERSION/$os/$arch/$z"
                    done
                    curl -fsS -u %s:%s -T "terraform-provider-${TYPE}_${VERSION}_SHA256SUMS" "%s/v1/providers/$NS/$TYPE/$VERSION/SHA256SUMS"
                    curl -fsS -u %s:%s -T "terraform-provider-${TYPE}_${VERSION}_SHA256SUMS.sig" "%s/v1/providers/$NS/$TYPE/$VERSION/SHA256SUMS.sig"
                    jq -n --arg id "$(gpg --list-keys --with-colons "$GPG_KEY_ID" | awk -F: '/^pub/ {print $5}')" \\
                          --arg armor "$(gpg --armor --export "$GPG_KEY_ID")" '{key_id: $id, ascii_armor: $armor}' \\
                      | curl -fsS -u %s:%s -X PUT -H 'Content-Type: application/json' --data-binary @- "%s/v1/providers/$NS/$TYPE/gpg-key"
                    """.formatted(ctx.username(), ctx.token(), ctx.url(), ctx.username(), ctx.token(), ctx.url(),
                    ctx.username(), ctx.token(), ctx.url(), ctx.username(), ctx.token(), ctx.url())));

            snippets.add(ConnectSnippet.shell("publish-module-curl", "Publish a module version (curl)", """
                    # The archive holds the module's files at its top level (main.tf, variables.tf, …).
                    tar czf demo-1.0.0.tgz -C ./modules/demo .
                    curl -fsS -u %s:%s -T demo-1.0.0.tgz "%s/v1/modules/acme/demo/local/1.0.0.tgz"
                    # then: module "demo" { source = "%s/acme/demo/local"  version = "1.0.0" }
                    """.formatted(ctx.username(), ctx.token(), ctx.url(), host)));

            snippets.add(ConnectSnippet.shell("providers-mirror-seed", "Seed from a public registry (terraform providers mirror)", """
                    # Download the providers a configuration needs, sign the checksums with your own key and upload them.
                    terraform providers mirror -platform=linux_amd64 -platform=darwin_arm64 ./mirror
                    cd ./mirror/registry.terraform.io/hashicorp/null
                    sha256sum terraform-provider-null_*.zip > SHA256SUMS
                    gpg --detach-sign --local-user "$GPG_KEY_ID" SHA256SUMS      # writes SHA256SUMS.sig
                    for z in terraform-provider-null_*.zip; do
                      IFS=_ read -r _ v os arch <<< "${z%%.zip}"
                      curl -fsS -u %s:%s -T "$z" "%s/v1/providers/hashicorp/null/$v/$os/$arch/$z"
                    done
                    curl -fsS -u %s:%s -T SHA256SUMS     "%s/v1/providers/hashicorp/null/$v/SHA256SUMS"
                    curl -fsS -u %s:%s -T SHA256SUMS.sig "%s/v1/providers/hashicorp/null/$v/SHA256SUMS.sig"
                    # the public half of the key that signed SHA256SUMS; the CLIs verify the signature with it
                    jq -n --arg id "$(gpg --list-keys --with-colons "$GPG_KEY_ID" | awk -F: '/^pub/ {print $5}')" \\
                          --arg armor "$(gpg --armor --export "$GPG_KEY_ID")" '{key_id: $id, ascii_armor: $armor}' \\
                      | curl -fsS -u %s:%s -X PUT -H 'Content-Type: application/json' --data-binary @- "%s/v1/providers/hashicorp/null/gpg-key"
                    """.formatted(ctx.username(), ctx.token(), ctx.url(), ctx.username(), ctx.token(), ctx.url(),
                    ctx.username(), ctx.token(), ctx.url(), ctx.username(), ctx.token(), ctx.url())));
        }
        return snippets;
    }

    /** {@code TF_TOKEN_<host>}: dots become {@code _}, hyphens {@code __}; a port cannot be expressed this way. */
    static String tokenEnv(String host, String token) {
        if (host.contains(":")) {
            return """
                    # TF_TOKEN_<host> variables cannot carry a port (%s); use the credentials block in the CLI configuration instead.
                    """.formatted(host);
        }
        return """
                # Same effect as the credentials block, for CI; terraform and tofu both read it.
                export TF_TOKEN_%s=%s
                """.formatted(envHost(host), token);
    }

    static String envHost(String host) {
        return host.replace("-", "__").replace(".", "_");
    }
}
