package dev.granary.format.composer;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Ready-to-paste Composer configuration for one repository. */
final class ComposerSnippets {

    private ComposerSnippets() {
    }

    static List<ConnectSnippet> of(ConnectContext ctx) {
        String url = ctx.url();
        String host = authHost(url);
        boolean http = !url.startsWith("https://");
        String user = ctx.username();
        String token = ctx.token();
        List<ConnectSnippet> snippets = new ArrayList<>();

        snippets.add(ConnectSnippet.shell("composer-global", "Composer (all projects)",
                "composer config -g repositories.granary composer " + url + "\n"
                        + "# use Granary instead of packagist.org, not in addition to it\n"
                        + "composer config -g repo.packagist false\n"
                        + (http ? "# the URL is plain http, which Composer refuses by default (see the secure-http snippet)\n"
                                + "composer config -g secure-http false\n" : "")
                        + "composer require <vendor>/<package>\n"));

        snippets.add(ConnectSnippet.shell("composer-project", "Composer (this project)",
                "composer config repositories.granary composer " + url + "\n"
                        + "composer config repo.packagist false\n"
                        + (http ? "composer config secure-http false\n" : "")));

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("    \"repositories\": [\n");
        json.append("        {\"type\": \"composer\", \"url\": \"").append(url).append("\"},\n");
        json.append("        {\"packagist.org\": false}\n");
        json.append("    ]");
        if (http) {
            json.append(",\n    \"config\": {\n        \"secure-http\": false\n    }");
        }
        json.append("\n}\n");
        snippets.add(ConnectSnippet.file("composer-json", "Composer (composer.json)", "json", "composer.json", json.toString()));

        snippets.add(ConnectSnippet.shell("composer-auth", "Credentials (auth.json via the CLI)",
                (ctx.anonymousRead()
                        ? "# downloads work anonymously on this server; credentials are needed to publish\n"
                        : "# this server requires credentials for downloads too\n")
                        + "composer config -g http-basic." + host + " " + user + " " + token + "\n"
                        + "# or send the API token as a bearer token instead:\n"
                        + "composer config -g bearer." + host + " " + token + "\n"));

        snippets.add(ConnectSnippet.file("auth-json", "Credentials (auth.json)", "json",
                "auth.json next to composer.json, or ~/.config/composer/auth.json (COMPOSER_HOME)",
                "{\n"
                        + "    \"http-basic\": {\n"
                        + "        \"" + host + "\": {\n"
                        + "            \"username\": \"" + user + "\",\n"
                        + "            \"password\": \"" + token + "\"\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n"));

        snippets.add(ConnectSnippet.shell("composer-auth-env", "Credentials in CI (COMPOSER_AUTH)",
                "export COMPOSER_AUTH='{\"http-basic\":{\"" + host + "\":{\"username\":\"" + user
                        + "\",\"password\":\"" + token + "\"}}}'\n"
                        + "composer install --no-interaction --prefer-dist\n"));

        if (http) {
            snippets.add(ConnectSnippet.shell("secure-http", "Plain http (secure-http)",
                    "# Warning: Composer only talks to https repositories unless secure-http is off.\n"
                            + "# Turning it off sends credentials and downloads in clear text; prefer an https\n"
                            + "# URL for Granary (a TLS-terminating reverse proxy) wherever you can.\n"
                            + "composer config secure-http false      # this project\n"
                            + "composer config -g secure-http false   # every project\n"));
        }

        if (ctx.repository().isHosted()) {
            snippets.add(ConnectSnippet.shell("publish-curl", "Publish a version (curl)",
                    "# an archive with composer.json at its root, e.g. built by Composer from the project directory:\n"
                            + "composer archive --format=zip --dir=build --file=package-1.0.0\n"
                            + "# or from a git tag: git archive --format=zip --output=build/package-1.0.0.zip v1.0.0\n"
                            + "curl -u " + user + ":" + token + " --upload-file build/package-1.0.0.zip \\\n"
                            + "     " + url + "/packages/<vendor>/<package>/1.0.0\n"
                            + "# or as a form upload; the version comes from ?version= or composer.json\n"
                            + "curl -u " + user + ":" + token + " -F package=@build/package-1.0.0.zip \\\n"
                            + "     \"" + url + "/packages?version=1.0.0\"\n"
                            + "# remove a version\n"
                            + "curl -u " + user + ":" + token + " -X DELETE " + url + "/packages/<vendor>/<package>/1.0.0\n"));
        }

        snippets.add(new ConnectSnippet("satis-comparison", "Coming from Satis or Private Packagist", "text", null,
                "Satis: a hosted Granary repository replaces a Satis build. There is nothing to rebuild after a\n"
                        + "release; uploading the archive updates packages.json and the p2 metadata at once, and the\n"
                        + "archives are served from Granary (Satis' archive/dist mirroring) for every version.\n"
                        + "Private Packagist: the proxy repository mirrors packagist.org metadata and every dist archive\n"
                        + "on first use and keeps serving both when the internet is down; the group repository is the\n"
                        + "single URL for private and public packages, with private versions taking precedence.\n"
                        + "Both speak the Composer 2 protocol (metadata-url, minified p2 files), so composer.json only\n"
                        + "needs a different repository URL.\n"));
        return snippets;
    }

    /** The key Composer looks credentials up by: the host, plus the port when it is not the scheme's default. */
    static String authHost(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null) {
                return url;
            }
            int port = uri.getPort();
            boolean defaultPort = port < 0 || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                    || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
            return uri.getHost() + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
