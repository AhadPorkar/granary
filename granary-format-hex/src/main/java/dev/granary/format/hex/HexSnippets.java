package dev.granary.format.hex;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import java.util.ArrayList;
import java.util.List;

/**
 * Ready-to-paste configuration for mix and rebar3. A proxy is a drop-in mirror of hex.pm (its documents keep
 * hex.pm's signature, so {@code HEX_MIRROR} / {@code HEX_CDN} is all it takes); hosted repositories and groups
 * are added as repositories of their own, trusted through Granary's public key.
 */
final class HexSnippets {

    private HexSnippets() {
    }

    /**
     * @param publicKeyPem Granary's signing key (what hosted repositories and groups are verified with)
     * @param fingerprint  its {@code SHA256:…} fingerprint, for {@code --fetch-public-key}
     * @param hostedMember for a group, the hosted member to publish to; otherwise {@code null}
     */
    static List<ConnectSnippet> of(ConnectContext ctx, String publicKeyPem, String fingerprint, String hostedMember) {
        Repository repo = ctx.repository();
        List<ConnectSnippet> snippets = new ArrayList<>();
        String url = ctx.url();
        String token = ctx.token();
        boolean anonymous = ctx.anonymousRead();
        String certNote = ctx.https()
                ? "# a server certificate from a private CA: export HEX_CACERTS_PATH=/path/to/ca-bundle.pem\n"
                : "";

        if (repo.isProxy()) {
            snippets.add(ConnectSnippet.shell("mix-hex-mirror", "mix (HEX_MIRROR)",
                    "# hex.pm mirror: documents keep hex.pm's signature, so nothing else changes\n"
                            + (anonymous ? "" : "# (anonymous downloads are off on this server: use hex.repo set hexpm below)\n")
                            + "export HEX_MIRROR=" + url + "\n"
                            + certNote
                            + "mix deps.get\n"));
            snippets.add(ConnectSnippet.shell("mix-repo-set", "mix (hex.repo set hexpm, persistent)",
                    "mix hex.repo set hexpm --url " + url
                            + (anonymous ? "" : " --auth-key " + token + " --no-oauth-exchange") + "\n"
                            + "mix deps.get\n"
                            + "# back to hex.pm: mix hex.repo set hexpm --url https://repo.hex.pm\n"));
            snippets.add(ConnectSnippet.shell("mix-local-hex", "mix local.hex / local.rebar through the mirror",
                    "HEX_MIRROR=" + url + " mix local.hex --force\n"
                            + "HEX_MIRROR=" + url + " mix local.rebar --force\n"));
            snippets.add(ConnectSnippet.shell("rebar3-hex-cdn", "rebar3 (HEX_CDN)",
                    "export HEX_CDN=" + url + "\n"
                            + certNote
                            + "rebar3 get-deps\n"));
            snippets.add(ConnectSnippet.file("rebar3-config-mirror", "rebar3 (global rebar.config)", "erlang",
                    "~/.config/rebar3/rebar.config",
                    "{hex, [{repos, [#{name => <<\"hexpm\">>,\n"
                            + "                  repo_url => <<\"" + url + "\">>"
                            + (anonymous ? "" : ",\n                  repo_key => <<\"" + token + "\">>")
                            + "}]}]}.\n"));
            snippets.add(ConnectSnippet.shell("mix-unsafe-registry", "Mirrors without hex.pm's signature (discouraged)",
                    "# Only for an upstream that re-signs documents with a key you cannot obtain: this turns off\n"
                            + "# registry signature checks entirely. Prefer adding the repository with its public key.\n"
                            + "export HEX_UNSAFE_REGISTRY=1\n"));
            return snippets;
        }

        String name = HexSettings.repositoryName(repo);
        String keyFile = "granary-" + name + ".pem";
        String authKey = anonymous ? "" : " --auth-key " + token;
        String curlAuth = anonymous ? "" : "-u " + ctx.username() + ":" + token + " ";

        snippets.add(ConnectSnippet.shell("mix-repo-add", "mix (hex.repo add)",
                "mix hex.repo add " + name + " " + url + " --fetch-public-key " + fingerprint + authKey + "\n"
                        + "# or with the key as a file:\n"
                        + "curl -fsSL " + curlAuth + url + "/public_key -o " + keyFile + "\n"
                        + "mix hex.repo add " + name + " " + url + " --public-key " + keyFile + authKey + "\n"
                        + certNote));
        snippets.add(ConnectSnippet.file("mix-deps", "mix (dependency)", "elixir", "mix.exs",
                "defp deps do\n"
                        + "  [\n"
                        + "    {:my_package, \"~> 0.1\", repo: \"" + name + "\"}\n"
                        + "  ]\n"
                        + "end\n"));
        if (repo.isHosted()) {
            snippets.add(ConnectSnippet.shell("mix-publish", "mix hex.publish",
                    "export HEX_API_URL=" + url + "/api\n"
                            + "export HEX_API_KEY=" + token + "\n"
                            + "mix hex.publish package --yes      # the release only\n"
                            + "mix hex.publish --yes              # release and documentation\n"
                            + "mix hex.publish --revert 0.1.0     # remove a release (within the revert window)\n"
                            + "mix hex.retire my_package 0.1.0 security --message \"use 0.1.1\"\n"));
            snippets.add(ConnectSnippet.file("mix-package", "mix (package metadata)", "elixir", "mix.exs",
                    "def project do\n"
                            + "  [\n"
                            + "    app: :my_package,\n"
                            + "    version: \"0.1.0\",\n"
                            + "    description: \"What my_package does\",\n"
                            + "    package: [licenses: [\"Apache-2.0\"], links: %{}],\n"
                            + "    deps: deps()\n"
                            + "  ]\n"
                            + "end\n"
                            + "# dependencies from this repository need repo: \"" + name + "\"; from hex.pm none\n"));
        } else if (hostedMember != null) {
            snippets.add(ConnectSnippet.shell("mix-publish", "mix hex.publish (to the hosted member)",
                    "# groups are read-only: publish to the hosted member '" + hostedMember + "'\n"
                            + "export HEX_API_URL=" + ctx.serverUrl() + "/repository/" + hostedMember + "/api\n"
                            + "export HEX_API_KEY=" + token + "\n"
                            + "mix hex.publish package --yes\n"));
        }

        StringBuilder rebar = new StringBuilder();
        rebar.append("{hex, [{repos, [#{name => <<\"").append(name).append("\">>,\n")
                .append("                  repo_url => <<\"").append(url).append("\">>,\n");
        if (!anonymous) {
            rebar.append("                  repo_key => <<\"").append(token).append("\">>,\n");
        }
        if (repo.isHosted()) {
            rebar.append("                  api_url => <<\"").append(url).append("/api\">>,\n");
        }
        rebar.append("                  repo_public_key => <<\"").append(publicKeyPem.strip().replace("\n", "\\n"))
                .append("\\n\">>}]}]}.\n")
                .append("\n{deps, [{my_package, \"0.1.0\"}]}.\n");
        snippets.add(ConnectSnippet.file("rebar3-config", "rebar3 (rebar.config)", "erlang", "rebar.config", rebar.toString()));
        if (repo.isHosted()) {
            // rebar3_hex has no API-key login of its own (rebar3 hex user auth is hex.pm's OAuth device flow), so the
            // key goes into the repository map; repositories other than hexpm get no defaults from rebar3, and
            // rebar3_hex fails on a map without api_organization and api_repository
            snippets.add(ConnectSnippet.file("rebar3-publish-config", "rebar3 publishing (global rebar.config)", "erlang",
                    "~/.config/rebar3/rebar.config",
                    "%% merged by name with the repository in the project's rebar.config; keeps the API key out of it\n"
                            + "{hex, [{repos, [#{name => <<\"" + name + "\">>,\n"
                            + "                  api_url => <<\"" + url + "/api\">>,\n"
                            + "                  api_key => <<\"" + token + "\">>,\n"
                            + "                  api_organization => undefined,\n"
                            + "                  api_repository => undefined}]}]}.\n"));
            snippets.add(ConnectSnippet.shell("rebar3-publish", "rebar3 hex publish",
                    "# the project's rebar.config: {plugins, [rebar3_hex]}. and, for documentation, {hex, [{doc, edoc}]}.\n"
                            + "rebar3 hex publish package --repo " + name + " --yes   # the release only\n"
                            + "rebar3 hex publish --repo " + name + " --yes           # release and documentation\n"
                            + "rebar3 hex publish --revert 0.1.0 --repo " + name + " --yes\n"
                            + "rebar3 hex retire my_package 0.1.0 security --message \"use 0.1.1\" --repo " + name + "\n"));
        }
        snippets.add(ConnectSnippet.file("public-key", "Repository public key", "text", keyFile, publicKeyPem));
        return snippets;
    }
}
