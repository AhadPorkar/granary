package dev.granary.format.cocoapods;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import java.util.ArrayList;
import java.util.List;

/** Ready-to-paste configuration for {@code pod} (CocoaPods 1.x) and for publishing with {@code curl}. */
final class CocoaPodsSnippets {

    private CocoaPodsSnippets() {
    }

    static List<ConnectSnippet> of(ConnectContext ctx) {
        // CDN sources are addressed with a trailing slash; pod appends the index file names to it verbatim
        String source = ctx.urlWithSlash();
        String repoName = "granary";
        List<ConnectSnippet> snippets = new ArrayList<>();

        snippets.add(ConnectSnippet.file("podfile", "Podfile", "ruby", "Podfile",
                "source '" + source + "'\n"
                        + (ctx.repository().isGroup() ? ""
                        : "# add source 'https://cdn.cocoapods.org/' below when this repository does not proxy trunk\n")
                        + "\n"
                        + "platform :ios, '13.0'\n"
                        + "\n"
                        + "target 'MyApp' do\n"
                        + "  use_frameworks!\n"
                        + "  pod 'AFNetworking', '~> 4.0'\n"
                        + "end\n"));

        snippets.add(ConnectSnippet.shell("pod-repo-add-cdn", "pod repo add-cdn",
                "pod repo add-cdn " + repoName + " " + source + "\n"
                        + "pod repo list\n"));

        snippets.add(ConnectSnippet.shell("pod-install", "pod install / update",
                "pod repo update " + repoName + "\n"
                        + "pod install\n"
                        + "# refresh the index first: pod install --repo-update\n"
                        + "pod update AFNetworking\n"));

        snippets.add(ConnectSnippet.shell("pod-search", "pod search / spec",
                "pod search AFNetworking --simple\n"
                        + "pod spec cat AFNetworking\n"
                        + "pod spec which AFNetworking\n"));

        String netrcNote = ctx.anonymousRead()
                ? "# only needed when anonymous downloads are switched off\n"
                : "# anonymous downloads are off: pod needs these credentials\n";
        snippets.add(ConnectSnippet.file("netrc", "Credentials (.netrc)", "text", "~/.netrc (chmod 600)",
                netrcNote
                        + "# pod's CDN source and its curl downloads read credentials from here\n"
                        + "machine " + hostName(ctx) + "\n"
                        + "  login " + ctx.username() + "\n"
                        + "  password " + ctx.token() + "\n"));

        snippets.add(ConnectSnippet.shell("pod-env", "Environment",
                "# CocoaPods with the cocoapods-stats plugin reports installs to stats.cocoapods.org;\n"
                        + "# switch that off where only this server is reachable\n"
                        + "export COCOAPODS_DISABLE_STATS=true\n"
                        + "# fewer parallel podspec downloads (default 200) for a small server\n"
                        + "export COCOAPODS_CDN_MAX_CONCURRENCY=50\n"));

        if (ctx.repository().isHosted()) {
            String podspecUrl = ctx.url() + "/Specs/MyPod/1.0.0/MyPod.podspec.json";
            snippets.add(ConnectSnippet.shell("pod-publish", "Publish a podspec",
                    "pod spec lint MyPod.podspec --allow-warnings   # optional, needs Xcode for iOS pods\n"
                            + "pod ipc spec MyPod.podspec > MyPod.podspec.json\n"
                            + "curl -u " + ctx.username() + ":" + ctx.token() + " -X PUT \\\n"
                            + "  -H 'Content-Type: application/json' --data-binary @MyPod.podspec.json \\\n"
                            + "  " + podspecUrl + "\n"));
            snippets.add(ConnectSnippet.shell("pod-publish-archive", "Publish with the source archive",
                    "# the archive is stored here and source.http in the podspec points at it\n"
                            + "curl -u " + ctx.username() + ":" + ctx.token() + " \\\n"
                            + "  -F podspec=@MyPod.podspec.json -F source=@MyPod-1.0.0.zip \\\n"
                            + "  " + ctx.url() + "/api/pods\n"));
            snippets.add(ConnectSnippet.shell("pod-delete", "Delete a version",
                    "curl -u " + ctx.username() + ":" + ctx.token() + " -X DELETE " + ctx.url() + "/Specs/MyPod/1.0.0\n"));
            snippets.add(ConnectSnippet.file("pod-trunk", "Compared with pod trunk", "text", null,
                    "pod trunk push MyPod.podspec    -> pod ipc spec + curl PUT (above)\n"
                            + "pod trunk deprecate MyPod       -> push the podspec again with \"deprecated\": true\n"
                            + "                                  (needs allow redeploy on the repository)\n"
                            + "pod trunk delete MyPod 1.0.0    -> curl -X DELETE " + ctx.url() + "/Specs/MyPod/1.0.0\n"
                            + "pod repo push                   -> not for CDN sources; it pushes to a git spec repo\n"));
        }
        return snippets;
    }

    /** The host name without port, the key {@code .netrc} entries are looked up by. */
    static String hostName(ConnectContext ctx) {
        String host = ctx.host();
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 0 ? host.substring(0, end + 1) : host;
        }
        int colon = host.lastIndexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }
}
