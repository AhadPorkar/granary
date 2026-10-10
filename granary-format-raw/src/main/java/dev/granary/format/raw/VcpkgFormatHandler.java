package dev.granary.format.raw;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * vcpkg's two caches as a hosted repository: the asset cache ({@code x-azurl}, which does {@code GET} and
 * {@code PUT} of {@code <url>/<sha512>} with Azure blob headers) and the binary cache ({@code http} provider:
 * {@code GET}/{@code HEAD}/{@code PUT} of {@code <url>/{name}/{version}/{sha}} zip archives). The Azure
 * headers are accepted and ignored; re-uploading identical bytes succeeds because vcpkg does not check first. A
 * stored upload answers {@code 201} without a body, like Azure blob storage: vcpkg copies response bodies of its
 * uploads into its console output.
 */
@org.springframework.stereotype.Component
public class VcpkgFormatHandler extends RawFormatHandler {

    public static final String FORMAT = "vcpkg";

    private static final Pattern BINARY = Pattern.compile("^binaries/([^/]+)/([^/]+)/([^/]+)$");

    public VcpkgFormatHandler(StorageService storage, ProxyService proxy, GroupResolver groups, GranaryProperties properties) {
        super(storage, proxy, groups, properties);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "vcpkg (C/C++)";
    }

    @Override
    public Set<RepositoryType> supportedTypes() {
        return EnumSet.of(RepositoryType.HOSTED);
    }

    @Override
    protected List<String> defaultMetadataPatterns() {
        return List.of();
    }

    @Override
    protected boolean allowsIdenticalOverwrite() {
        return true;
    }

    @Override
    protected StorageService.ComponentCoordinates coordinates(String path) {
        Matcher binary = BINARY.matcher(path);
        if (binary.matches()) {
            return new StorageService.ComponentCoordinates("", binary.group(1), binary.group(2));
        }
        return RawComponents.generic(path);
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(RepositoryTemplate.hosted("vcpkg-cache", FORMAT,
                "vcpkg asset cache (assets/) and binary cache (binaries/)"));
    }

    @Override
    protected ResponseEntity<?> uploaded(String path, StoredContent content) {
        return ResponseEntity.status(HttpStatus.CREATED).eTag("\"" + content.asset().getBlobSha256() + "\"").build();
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext ctx) {
        String url = ctx.url();
        String binaries = "clear;http," + url + "/binaries/{name}/{version}/{sha},readwrite";
        // uploads always need credentials; reads need them too when anonymous read is off
        String authUrl = url.replaceFirst("^(https?://)", "$1" + ctx.username() + ":" + ctx.token() + "@");
        String header = "Authorization: Bearer " + ctx.token();
        String readUrl = ctx.anonymousRead() ? url : authUrl;
        String readHeader = ctx.anonymousRead() ? "" : "," + header;
        String writeAssets = "x-azurl," + authUrl + "/assets/,,readwrite";
        String writeBinaries = binaries + "," + header;
        String readAssets = "x-azurl," + readUrl + "/assets/,,read";
        String readBinaries = "clear;http," + url + "/binaries/{name}/{version}/{sha},read" + readHeader;
        String note = "# uploads need credentials. The binary cache sends the token as a header, which vcpkg keeps out of its\n"
                + "# output; the asset cache only takes them in the URL (percent-encode special characters), and vcpkg\n"
                + "# prints asset cache URLs, so keep such build logs private\n";
        String readNote = ctx.anonymousRead()
                ? "# read-only, without credentials (anonymous read is on):\n"
                : "# read-only (anonymous read is off, so reads need the credentials too):\n";
        return List.of(
                ConnectSnippet.shell("bash", "bash / zsh: environment variables",
                        note
                                + "export X_VCPKG_ASSET_SOURCES=\"" + writeAssets + "\"\n"
                                + "export VCPKG_BINARY_SOURCES=\"" + writeBinaries + "\"\n"
                                + readNote
                                + "export X_VCPKG_ASSET_SOURCES=\"" + readAssets + "\"\n"
                                + "export VCPKG_BINARY_SOURCES=\"" + readBinaries + "\""),
                ConnectSnippet.file("powershell", "PowerShell: environment variables", "shell", null,
                        note
                                + "$env:X_VCPKG_ASSET_SOURCES = \"" + writeAssets + "\"\n"
                                + "$env:VCPKG_BINARY_SOURCES = \"" + writeBinaries + "\"\n"
                                + readNote
                                + "$env:X_VCPKG_ASSET_SOURCES = \"" + readAssets + "\"\n"
                                + "$env:VCPKG_BINARY_SOURCES = \"" + readBinaries + "\""),
                ConnectSnippet.file("cmd", "cmd.exe: environment variables", "shell", null,
                        note.replace("# ", "rem ")
                                + "set \"X_VCPKG_ASSET_SOURCES=" + writeAssets + "\"\n"
                                + "set \"VCPKG_BINARY_SOURCES=" + writeBinaries + "\"\n"
                                + readNote.replace("# ", "rem ")
                                + "set \"X_VCPKG_ASSET_SOURCES=" + readAssets + "\"\n"
                                + "set \"VCPKG_BINARY_SOURCES=" + readBinaries + "\""),
                ConnectSnippet.shell("cli", "vcpkg command-line flags instead of environment variables",
                        "vcpkg install --x-asset-sources=\"" + writeAssets + "\" --binarysource=\"" + writeBinaries + "\""),
                ConnectSnippet.file("manifest", "vcpkg.json: registries stay in git", "json", "vcpkg.json",
                        "{\n"
                                + "  \"name\": \"my-app\",\n"
                                + "  \"version\": \"1.0.0\",\n"
                                + "  \"dependencies\": [\"fmt\", \"zlib\"]\n"
                                + "}\n"
                                + "// the default registry (ports) is a git checkout of microsoft/vcpkg; only the downloaded\n"
                                + "// sources (asset cache) and the built packages (binary cache) go through this repository"),
                ConnectSnippet.shell("curl", "curl: what vcpkg does on the wire",
                        "curl -fsS -u " + ctx.username() + ":" + ctx.token() + " -X PUT -H 'x-ms-blob-type: BlockBlob' "
                                + "--data-binary @zlib-1.3.1.tar.gz " + url + "/assets/<sha512-of-the-file>\n"
                                + "curl -fsSI " + url + "/binaries/zlib/1.3.1/<abi-hash>\n"
                                + "curl -fsS -o zlib.zip " + url + "/binaries/zlib/1.3.1/<abi-hash>"));
    }
}
