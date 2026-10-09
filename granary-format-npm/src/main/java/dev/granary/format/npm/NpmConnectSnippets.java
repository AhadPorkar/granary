package dev.granary.format.npm;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import java.util.List;

/**
 * Ready-to-paste configuration for the clients of the npm registry protocol: npm, pnpm, Yarn 1,
 * Yarn 2+ and Bun. npm, pnpm and Yarn 1 share {@code .npmrc}; Yarn 2+ and Bun have their own files.
 */
final class NpmConnectSnippets {

    private NpmConnectSnippets() {
    }

    static List<ConnectSnippet> of(ConnectContext ctx) {
        String registry = ctx.urlWithSlash();
        String authKey = ctx.urlWithoutScheme() + "/:_authToken";
        String token = ctx.token();
        return List.of(
                ConnectSnippet.shell("npm-registry", "npm: set the registry for this project or globally",
                        "npm config set registry " + registry),
                ConnectSnippet.file("npm-npmrc", "npm: registry and authentication token", "ini", ".npmrc",
                        "registry=" + registry + "\n" + authKey + "=" + token),
                ConnectSnippet.file("npm-scoped", "npm: use this registry for one scope only", "ini", ".npmrc",
                        "@acme:registry=" + registry + "\n" + authKey + "=" + token),
                ConnectSnippet.shell("npm-login", "npm: or log in interactively",
                        "npm login --auth-type=legacy --registry " + registry),
                ConnectSnippet.shell("npm-publish", "npm: publish a package",
                        "npm publish --registry " + registry),
                ConnectSnippet.shell("pnpm", "pnpm: registry and token",
                        "pnpm config set registry " + registry + "\n"
                                + "pnpm config set " + authKey + " " + token + "\n"
                                + "# pnpm reads the same .npmrc keys as npm"),
                ConnectSnippet.file("yarn-classic", "Yarn 1 (classic): registry", "text", ".yarnrc",
                        "registry \"" + registry + "\"\n"
                                + "# Yarn 1 reads the token from .npmrc: " + authKey + "=" + token),
                ConnectSnippet.file("yarn-berry", "Yarn 2+ (berry): registry, token and scopes", "yaml", ".yarnrc.yml",
                        "npmRegistryServer: \"" + registry + "\"\n"
                                + "npmAlwaysAuth: true\n"
                                + "npmAuthToken: \"" + token + "\"\n"
                                + "npmScopes:\n"
                                + "  acme:\n"
                                + "    npmRegistryServer: \"" + registry + "\"\n"
                                + "    npmPublishRegistry: \"" + registry + "\"\n"
                                + "    npmAuthToken: \"" + token + "\""),
                ConnectSnippet.file("bun", "Bun: registry and token", "toml", "bunfig.toml",
                        "[install]\n"
                                + "registry = { url = \"" + registry + "\", token = \"" + token + "\" }\n"
                                + "\n"
                                + "[install.scopes]\n"
                                + "acme = { url = \"" + registry + "\", token = \"" + token + "\" }"));
    }
}
