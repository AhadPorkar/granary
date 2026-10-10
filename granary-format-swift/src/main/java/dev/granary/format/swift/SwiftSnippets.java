package dev.granary.format.swift;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ready-to-paste configuration for SwiftPM ({@code swift package-registry}, Swift 5.7 and later; Xcode reads the
 * same configuration files). SwiftPM keys credentials by host and port, sends them only when the registries
 * configuration has an {@code authentication} entry for that host, and logs in and publishes over HTTPS only.
 */
final class SwiftSnippets {

    private SwiftSnippets() {
    }

    static List<ConnectSnippet> build(ConnectContext ctx) {
        List<ConnectSnippet> snippets = new ArrayList<>();
        String url = ctx.url();
        String insecure = ctx.https() ? "" : " --allow-insecure-http";
        String host = authenticationKey(ctx.serverUrl());
        boolean hosted = ctx.repository().isHosted();
        String httpsNote = ctx.https() ? "" : """
                # SwiftPM logs in and publishes over HTTPS only. Put a TLS reverse proxy (Caddy, nginx, Traefik)
                # in front of Granary and set its base URL to the https:// address for those two commands.
                """;

        snippets.add(ConnectSnippet.shell("swift-registry-set", "Use this registry for every package (swift package-registry set)", """
                # Resolve registry dependencies (.package(id: "scope.name", ...)) through this repository.
                # --global writes ~/.swiftpm/configuration/registries.json; without it the setting is per project.
                swift package-registry set %s --global%s
                """.formatted(url, insecure)));

        snippets.add(ConnectSnippet.shell("swift-registry-set-scope", "Use this registry for one scope", """
                swift package-registry set %s --scope acme%s
                """.formatted(url, insecure)));

        snippets.add(ConnectSnippet.shell("swift-registry-login", "Log in with an API token (swift package-registry login)", httpsNote + """
                # Stores the token (in ~/.netrc on Linux, the keychain on macOS) and an "authentication" entry for
                # %s in ~/.swiftpm/configuration/registries.json; SwiftPM then sends it as a Bearer token.
                swift package-registry login %s --token %s --no-confirm
                """.formatted(host, url, ctx.token())));

        snippets.add(ConnectSnippet.shell("swift-registry-login-basic", "Log in with username and token as password", httpsNote + """
                swift package-registry login %s --username %s --password %s --no-confirm
                """.formatted(url, ctx.username(), ctx.token())));

        snippets.add(ConnectSnippet.file("registries-json", "Registry configuration (registries.json)", "json",
                ".swiftpm/configuration/registries.json", """
                {
                  "authentication": {
                    "%s": {
                      "type": "token"
                    }
                  },
                  "registries": {
                    "[default]": {
                      "supportsAvailability": false,
                      "url": "%s"
                    }
                  },
                  "version": 1
                }
                """.formatted(host, url)));

        snippets.add(ConnectSnippet.file("netrc", "Token for the registry host (~/.netrc)", "text", "~/.netrc", """
                machine %s
                  login token
                  password %s
                """.formatted(netrcMachine(ctx.serverUrl()), ctx.token())));

        snippets.add(ConnectSnippet.file("package-swift", "Depend on a registry package (Package.swift)", "swift", "Package.swift", """
                // swift-tools-version:5.9
                import PackageDescription

                let package = Package(
                    name: "App",
                    dependencies: [
                        .package(id: "acme.demo", from: "1.0.0"),
                    ],
                    targets: [
                        .executableTarget(name: "App", dependencies: [
                            .product(name: "Demo", package: "acme.demo"),
                        ]),
                    ]
                )
                """));

        snippets.add(ConnectSnippet.shell("swift-package-resolve", "Resolve and build", """
                swift package resolve
                swift build
                """));

        snippets.add(ConnectSnippet.shell("swift-replace-scm", "Take source-control dependencies from the registry", """
                # Looks every .package(url: ...) up with GET identifiers?url=... and uses the registry release
                # when the URL is listed in a published package's repositoryURLs metadata.
                swift package --replace-scm-with-registry resolve
                swift build --replace-scm-with-registry
                """));

        String publishUrl = hosted ? url : "<hosted repository URL>";
        snippets.add(ConnectSnippet.file("package-metadata-json", "Release metadata (package-metadata.json)", "json",
                "package-metadata.json", """
                {
                  "description": "A demo package",
                  "author": { "name": "Jane Doe" },
                  "licenseURL": "https://example.com/acme/demo/LICENSE",
                  "repositoryURLs": ["https://github.com/acme/demo", "git@github.com:acme/demo.git"]
                }
                """));

        snippets.add(ConnectSnippet.shell("swift-registry-publish", "Publish a release (swift package-registry publish)",
                (hosted ? "" : "# Groups and proxies are read-only: publish to a hosted repository.\n") + httpsNote + """
                # Run in the package directory; package-metadata.json is picked up when present.
                swift package-registry publish acme.demo 1.0.0 --url %s
                """.formatted(publishUrl)));

        snippets.add(ConnectSnippet.shell("curl-publish", "Publish a release with curl", """
                # The archive must hold the package in one top-level directory, as archive-source creates it.
                swift package archive-source --output demo-1.0.0.zip
                curl -u %s:%s -X PUT -H "Accept: application/vnd.swift.registry.v1+json" \\
                  -F "source-archive=@demo-1.0.0.zip;type=application/zip" \\
                  -F "metadata=@package-metadata.json;type=application/json" \\
                  %s/acme/demo/1.0.0
                """.formatted(ctx.username(), ctx.token(), publishUrl)));
        return snippets;
    }

    /**
     * The key of the {@code authentication} entry in {@code registries.json}: the registry's host, with the port when
     * the URL names one ({@code localhost:8113}), never a path (Granary may run below one, {@code https://host/granary}).
     */
    static String authenticationKey(String serverUrl) {
        URI uri = parse(serverUrl);
        if (uri == null || uri.getHost() == null) {
            return stripPath(serverUrl.replaceFirst("^https?://", ""));
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return uri.getPort() == -1 ? host : host + ":" + uri.getPort();
    }

    /** The {@code machine} of a {@code ~/.netrc} entry: the host alone, without port or path. */
    static String netrcMachine(String serverUrl) {
        URI uri = parse(serverUrl);
        if (uri == null || uri.getHost() == null) {
            return stripPath(serverUrl.replaceFirst("^https?://", "")).replaceFirst(":\\d+$", "");
        }
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static URI parse(String url) {
        try {
            return url == null ? null : URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stripPath(String hostAndPath) {
        int slash = hostAndPath.indexOf('/');
        return slash >= 0 ? hostAndPath.substring(0, slash) : hostAndPath;
    }
}
