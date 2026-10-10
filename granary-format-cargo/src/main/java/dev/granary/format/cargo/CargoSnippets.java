package dev.granary.format.cargo;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import java.util.ArrayList;
import java.util.List;

/** Ready-to-paste cargo configuration for one repository. */
final class CargoSnippets {

    static final String REGISTRY = "granary";

    private CargoSnippets() {
    }

    /**
     * @param publishUrl URL of the hosted repository publishes go to: the repository itself when it is hosted, the
     *                   first hosted member of a group, {@code null} when there is none
     */
    static List<ConnectSnippet> of(ConnectContext ctx, String publishUrl) {
        String index = "sparse+" + ctx.url() + "/index/";
        boolean hosted = ctx.repository().isHosted();
        String auth = ctx.anonymousRead() ? "" : "\n# downloads need a token on this server\n";
        List<ConnectSnippet> snippets = new ArrayList<>();

        snippets.add(ConnectSnippet.file("cargo-registry", "Cargo registry (.cargo/config.toml)", "toml",
                ".cargo/config.toml (in the project) or ~/.cargo/config.toml",
                "[registries." + REGISTRY + "]\n"
                        + "index = \"" + index + "\"\n"
                        + auth
                        + "\n"
                        + "[registry]\n"
                        + "global-credential-providers = [\"cargo:token\"]\n"
                        + "\n"
                        + "[net]\n"
                        + "retry = 3\n"));

        if (!hosted) {
            snippets.add(ConnectSnippet.file("cargo-mirror", "Replace crates.io (mirror every dependency)", "toml",
                    ".cargo/config.toml (in the project) or ~/.cargo/config.toml",
                    "[source.crates-io]\n"
                            + "replace-with = \"" + REGISTRY + "\"\n"
                            + "\n"
                            + "[source." + REGISTRY + "]\n"
                            + "registry = \"" + index + "\"\n"
                            + "\n"
                            + "# keep the [registries." + REGISTRY + "] table of the registry snippet in the same file, so\n"
                            + "# `registry = \"" + REGISTRY + "\"` dependencies and cargo login --registry " + REGISTRY
                            + " work too\n"));
        }

        snippets.add(ConnectSnippet.shell("cargo-login", "Log in (stores the API token)",
                "cargo login --registry " + REGISTRY + "\n"
                        + "# paste your Granary API token when asked, or non-interactively:\n"
                        + "echo " + ctx.token() + " | cargo login --registry " + REGISTRY + "\n"
                        + "# a token is created on your Granary account page: " + ctx.serverUrl() + "/account\n"));

        snippets.add(ConnectSnippet.shell("cargo-env", "Environment variables (CI)",
                "export CARGO_REGISTRIES_" + REGISTRY.toUpperCase() + "_INDEX=" + index + "\n"
                        + "export CARGO_REGISTRIES_" + REGISTRY.toUpperCase() + "_TOKEN=" + ctx.token() + "\n"
                        + "export CARGO_REGISTRY_GLOBAL_CREDENTIAL_PROVIDERS=cargo:token\n"
                        + (ctx.https() ? "# self-signed certificate: export CARGO_HTTP_CAINFO=/path/to/ca.pem\n" : "")));

        snippets.add(ConnectSnippet.file("cargo-dependency", "Use a crate (Cargo.toml)", "toml", "Cargo.toml",
                "[dependencies]\n"
                        + "my-crate = { version = \"0.1\", registry = \"" + REGISTRY + "\" }\n"));

        snippets.add(ConnectSnippet.shell("cargo-add", "Add a crate (command line)",
                "cargo add <crate> --registry " + REGISTRY + "\n"
                        + "cargo search <query> --registry " + REGISTRY + "\n"
                        + "cargo fetch\n"));

        if (publishUrl != null) {
            String publishIndex = "sparse+" + publishUrl + "/index/";
            String registry = hosted ? REGISTRY : REGISTRY + "-hosted";
            StringBuilder publish = new StringBuilder();
            if (!hosted) {
                publish.append("# a group is read-only; publish to its hosted member\n")
                        .append("# ~/.cargo/config.toml: [registries.").append(registry).append("] index = \"")
                        .append(publishIndex).append("\"\n")
                        .append("cargo login --registry ").append(registry).append('\n');
            }
            publish.append("cargo publish --registry ").append(registry).append('\n')
                    .append("cargo yank --version 0.1.0 --registry ").append(registry).append(" <crate>\n")
                    .append("cargo yank --undo --version 0.1.0 --registry ").append(registry).append(" <crate>\n")
                    .append("cargo owner --list --registry ").append(registry).append(" <crate>\n");
            snippets.add(ConnectSnippet.shell("cargo-publish", "Publish a crate", publish.toString()));
            snippets.add(ConnectSnippet.file("cargo-publish-manifest", "Restrict publishing to this registry (Cargo.toml)",
                    "toml", "Cargo.toml",
                    "[package]\n"
                            + "publish = [\"" + registry + "\"]\n"));
        }

        if (ctx.https()) {
            snippets.add(ConnectSnippet.file("cargo-tls", "Custom certificate authority", "toml",
                    "~/.cargo/config.toml",
                    "[http]\n"
                            + "cainfo = \"/path/to/ca.pem\"   # or export CARGO_HTTP_CAINFO=/path/to/ca.pem\n"));
        }
        return snippets;
    }
}
