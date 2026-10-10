package dev.granary.format.raw;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hackage and Stackage as verbatim proxies. The package index ({@code 01-index.tar.gz} for secure
 * repositories, {@code 00-index.tar.gz} for {@code secure: False}), the TUF metadata and the Stackage
 * snapshot files are re-validated; package tarballs and cabal file revisions are immutable.
 */
@org.springframework.stereotype.Component
public class HackageFormatHandler extends RawFormatHandler {

    public static final String FORMAT = "hackage";

    static final List<String> DEFAULT_METADATA = List.of(
            "01-index.tar.gz", "01-index.tar", "01-index.tar.idx", "00-index.tar.gz", "00-index.tar",
            "root.json", "timestamp.json", "snapshot.json", "mirrors.json", "packages/index.tar.gz",
            "package/*/preferred", "package/*/preferred-versions", "preferred-versions", "*/cabal.config",
            "snapshots.json", "snapshots/**", "lts*", "nightly*", "lts-*/**", "nightly-*/**", "lts/**", "nightly/**");

    /**
     * Key ids of the root role in Hackage's {@code root.json} (version 8, {@code signed.roles.root.keyids}). cabal
     * needs them only for a secure repository with a name of its own, to accept the first {@code root.json}; under
     * the name {@code hackage.haskell.org} it uses the keys it ships with, and stack always does.
     */
    static final List<String> HACKAGE_ROOT_KEYS = List.of(
            "fe331502606802feac15e514d9b9ea83fee8b6ffef71335479a2e68d84adc6b0",
            "1ea9ba32c526d1cc91ab5e5bd364ec5e9e8cb67179a471872f6e26f0ae773d42",
            "0a5c7ea47cd1b15f01f5f51a33adda7e655bc0f0b0615baa8e271f4c3351e21d",
            "51f0161b906011b52c6613376b1ae937670da69322113a246a09f807c62f6921",
            "c7de58fc6a224b92b5b513f26fbb8b370f2d97c7cfe0075a951314a55734be93",
            "d26e46f3b631aae1433b89379a6c68bd417eb5d1c408f0643dcc07757fece522");

    private static final Pattern PACKAGE_ID = Pattern.compile("^(.+)-(\\d+(?:\\.\\d+)*)$");
    private static final Pattern PACKAGE_PATH = Pattern.compile("^package/([^/]+?)(?:\\.tar\\.gz|/.*)$");

    public HackageFormatHandler(StorageService storage, ProxyService proxy, GroupResolver groups, GranaryProperties properties) {
        super(storage, proxy, groups, properties);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Hackage / Stackage (Haskell)";
    }

    @Override
    protected List<String> defaultMetadataPatterns() {
        return DEFAULT_METADATA;
    }

    @Override
    protected StorageService.ComponentCoordinates coordinates(String path) {
        Matcher m = PACKAGE_PATH.matcher(path);
        if (m.matches()) {
            Matcher id = PACKAGE_ID.matcher(m.group(1));
            if (id.matches()) {
                return new StorageService.ComponentCoordinates("", id.group(1), id.group(2));
            }
        }
        return RawComponents.generic(path);
    }

    /** {@code name-1.2.3} split into name and version, when the segment has that shape. */
    static String[] packageId(String segment) {
        Matcher id = PACKAGE_ID.matcher(segment);
        return id.matches() ? new String[] {id.group(1), id.group(2)} : null;
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("hackage-proxy", FORMAT, "https://hackage.haskell.org/", "Proxy of Hackage (cabal)"),
                RepositoryTemplate.proxy("stackage-proxy", FORMAT, "https://www.stackage.org/", "Proxy of Stackage (snapshots, cabal.config)"),
                RepositoryTemplate.proxy("stackage-snapshots", FORMAT,
                        "https://raw.githubusercontent.com/commercialhaskell/stackage-snapshots/master/",
                        "Proxy of the Stackage snapshot definitions stack downloads (lts/ and nightly/)"));
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext ctx) {
        String url = ctx.url();
        String server = ctx.serverUrl();
        String name = ctx.repository().getName();
        String authUrl = ctx.anonymousRead() ? url : url.replaceFirst("^(https?://)", "$1" + ctx.username() + ":" + ctx.token() + "@");
        String authServer = ctx.anonymousRead() ? server : server.replaceFirst("^(https?://)", "$1" + ctx.username() + ":" + ctx.token() + "@");
        // cabal's secure (TUF) downloads never send credentials, not even the ones in the URL
        String secureNote = ctx.anonymousRead() ? ""
                : "-- cabal sends no credentials for secure repositories: they need anonymous read on Granary\n";
        StringBuilder keys = new StringBuilder();
        for (String key : HACKAGE_ROOT_KEYS) {
            keys.append("    ").append(key).append('\n');
        }
        return List.of(
                ConnectSnippet.file("cabal-secure", "cabal: ~/.cabal/config, Hackage through Granary with signature checks", "text", "~/.cabal/config",
                        "-- change the url of the existing hackage.haskell.org entry: the TUF metadata passes through\n"
                                + "-- unchanged, so cabal keeps verifying it with the Hackage root keys it ships with\n"
                                + secureNote
                                + "repository hackage.haskell.org\n"
                                + "  url: " + url + "/"),
                ConnectSnippet.file("cabal-secure-named", "cabal: ~/.cabal/config, secure mirror under its own name", "text", "~/.cabal/config",
                        "-- a secure repository with its own name needs Hackage's root keys to accept the first root.json\n"
                                + "-- (the keyids of signed.roles.root in " + url + "/root.json)\n"
                                + secureNote
                                + "repository " + name + "\n"
                                + "  url: " + url + "/\n"
                                + "  secure: True\n"
                                + "  root-keys:\n"
                                + keys
                                + "  key-threshold: 3"),
                ConnectSnippet.file("cabal-insecure", "cabal: ~/.cabal/config, plain mirror without signature checks", "text", "~/.cabal/config",
                        "-- 'secure: False' reads 00-index.tar.gz and checks no signatures\n"
                                + "repository " + name + "\n"
                                + "  url: " + authUrl + "/\n"
                                + "  secure: False"),
                ConnectSnippet.shell("cabal-update", "cabal: refresh the index and build",
                        "cabal update\n"
                                + "cabal build"),
                ConnectSnippet.file("stack-package-index", "stack: ~/.stack/config.yaml package index", "yaml", "~/.stack/config.yaml",
                        "# stack keeps verifying the index with the Hackage root keys it ships with\n"
                                + "package-index:\n"
                                + "  download-prefix: " + authUrl + "/"),
                ConnectSnippet.file("stack-snapshots", "stack: snapshot definitions through Granary", "yaml", "stack.yaml",
                        "# a raw proxy of https://raw.githubusercontent.com/commercialhaskell/stackage-snapshots/master/\n"
                                + "# (the 'stackage-snapshots' template) serves the lts/ and nightly/ snapshot files\n"
                                + "snapshot-location-base: " + authServer + "/repository/stackage-snapshots/\n"
                                + "resolver: lts-22.43"),
                ConnectSnippet.shell("stackage-cabal-config", "cabal: pin versions with a Stackage cabal.config",
                        "# through the 'stackage-proxy' template\n"
                                + "curl -fsSL " + authServer + "/repository/stackage-proxy/lts-22.43/cabal.config -o cabal.project.freeze"));
    }
}
