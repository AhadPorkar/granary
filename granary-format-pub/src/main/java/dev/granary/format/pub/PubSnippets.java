package dev.granary.format.pub;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ready-to-paste configuration for {@code dart pub} and {@code flutter pub}. */
final class PubSnippets {

    private PubSnippets() {
    }

    static List<ConnectSnippet> of(ConnectContext ctx) {
        String url = ctx.url();
        boolean hosted = ctx.repository().isHosted();
        boolean needsToken = hosted || !ctx.anonymousRead();
        String insecureNote = ctx.https() || isLocal(ctx.host()) ? ""
                : "# dart pub only stores tokens for https:// URLs (http:// is accepted for localhost only)\n";
        List<ConnectSnippet> snippets = new ArrayList<>();

        snippets.add(ConnectSnippet.shell("pub-hosted-url", "Every package through Granary (PUB_HOSTED_URL)",
                "# bash / zsh\n"
                        + "export PUB_HOSTED_URL=" + url + "\n"
                        + "dart pub get\n"
                        + "\n"
                        + "# PowerShell\n"
                        + "$env:PUB_HOSTED_URL = \"" + url + "\"\n"
                        + "\n"
                        + "# optional: fail fast instead of retrying seven times when a package cannot be served\n"
                        + "# export PUB_MAX_HTTP_RETRIES=2\n"));

        snippets.add(ConnectSnippet.shell("pub-token", "Authentication (dart pub token add)",
                insecureNote
                        + "dart pub token add " + url + "\n"
                        + "# paste a Granary API token (" + ctx.token() + ") when asked, or pipe it in:\n"
                        + "echo \"" + ctx.token() + "\" | dart pub token add " + url + "\n"
                        + "# the token is sent as 'Authorization: Bearer' to every URL below " + url + "\n"
                        + (needsToken ? "" : "# only needed for publishing: downloads work anonymously on this server\n")));

        snippets.add(ConnectSnippet.file("pubspec-hosted", "One dependency from Granary (pubspec.yaml)", "yaml",
                "pubspec.yaml",
                "dependencies:\n"
                        + "  my_package:\n"
                        + "    hosted: " + url + "\n"
                        + "    version: ^1.0.0\n"));

        if (hosted) {
            snippets.add(ConnectSnippet.file("pubspec-publish-to", "Publish target (pubspec.yaml)", "yaml", "pubspec.yaml",
                    "name: my_package\n"
                            + "version: 1.0.0\n"
                            + "publish_to: " + url + "\n"));
            snippets.add(ConnectSnippet.shell("dart-pub-publish", "Publish (dart pub publish)",
                    insecureNote
                            + "echo \"" + ctx.token() + "\" | dart pub token add " + url + "\n"
                            + "dart pub publish            # add --force in scripts to skip the confirmation\n"
                            + "# publish_to in pubspec.yaml picks the repository; without it PUB_HOSTED_URL does\n"));
        }

        snippets.add(ConnectSnippet.shell("flutter-pub", "Flutter (flutter pub get)",
                "export PUB_HOSTED_URL=" + url + "\n"
                        + "flutter pub get\n"
                        + "\n"
                        + "# FLUTTER_STORAGE_BASE_URL is for Flutter SDK artifacts (engine, Dart SDK), not packages.\n"
                        + "# To cache those too, create a raw proxy of https://storage.googleapis.com/ and set:\n"
                        + "# export FLUTTER_STORAGE_BASE_URL=" + ctx.serverUrl() + "/repository/<raw-proxy-name>\n"));

        snippets.add(ConnectSnippet.shell("pub-ci", "CI (token from a secret)",
                "export PUB_HOSTED_URL=" + url + "\n"
                        + "# GRANARY_PUB_TOKEN holds a Granary API token, injected from your CI secret store\n"
                        + "dart pub token add " + url + " --env-var GRANARY_PUB_TOKEN\n"
                        + "dart pub get --enforce-lockfile   # resolves exactly the committed pubspec.lock\n"
                        + (hosted ? "dart pub publish --force\n" : "")));

        snippets.add(ConnectSnippet.file("dockerfile", "Docker build", "text", "Dockerfile",
                "FROM dart:stable\n"
                        + "ENV PUB_HOSTED_URL=" + url + "\n"
                        + "WORKDIR /app\n"
                        + "COPY pubspec.* ./\n"
                        + "RUN dart pub get\n"
                        + "COPY . .\n"
                        + "RUN dart compile exe bin/main.dart -o bin/server\n"));
        return snippets;
    }

    private static boolean isLocal(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.startsWith("localhost") || h.startsWith("127.") || h.startsWith("[::1]");
    }
}
