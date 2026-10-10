package dev.granary.format.rubygems;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import java.util.ArrayList;
import java.util.List;

/** Ready-to-paste configuration for {@code gem} and Bundler. */
final class RubygemsSnippets {

    private RubygemsSnippets() {
    }

    static List<ConnectSnippet> of(ConnectContext ctx) {
        String source = ctx.urlWithSlash();
        String withCredentials = withCredentials(source, ctx.username(), ctx.token());
        String credentials = ctx.username() + ":" + ctx.token();
        boolean anonymous = ctx.anonymousRead();
        List<ConnectSnippet> snippets = new ArrayList<>();

        snippets.add(ConnectSnippet.shell("gem-sources", "gem (sources)",
                "gem sources --add " + (anonymous ? source : withCredentials) + " --remove https://rubygems.org/\n"
                        + "gem sources --list\n"
                        + (anonymous ? "# with credentials: gem sources --add " + withCredentials + "\n" : "")));
        snippets.add(ConnectSnippet.shell("gem-install", "gem install",
                "gem install --clear-sources --source " + (anonymous ? source : withCredentials) + " <gem>\n"
                        + "gem fetch --clear-sources --source " + (anonymous ? source : withCredentials) + " <gem>\n"
                        + "gem search --remote --clear-sources --source " + (anonymous ? source : withCredentials) + " <pattern>\n"));
        snippets.add(ConnectSnippet.file("gemrc", "gem (~/.gemrc)", "yaml", "~/.gemrc",
                "---\n"
                        + ":sources:\n"
                        + "- " + (anonymous ? source : withCredentials) + "\n"));

        snippets.add(ConnectSnippet.file("gemfile", "Bundler (Gemfile)", "ruby", "Gemfile",
                "source \"" + source + "\"\n"
                        + "\n"
                        + "gem \"rake\"\n"));
        snippets.add(ConnectSnippet.shell("bundle-credentials", "Bundler (credentials)",
                "bundle config set --global " + source + " " + credentials + "\n"
                        + "# or by host name, also as an environment variable:\n"
                        + "#   export BUNDLE_" + bundlerEnvKey(hostName(ctx.host())) + "=" + credentials + "\n"
                        + "bundle install\n"));
        snippets.add(ConnectSnippet.shell("bundle-mirror", "Bundler (mirror of rubygems.org)",
                "# keeps source \"https://rubygems.org\" in every Gemfile and fetches through Granary instead\n"
                        + "bundle config set --global mirror.https://rubygems.org " + source + "\n"
                        + (anonymous ? "" : "bundle config set --global " + source + " " + credentials + "\n")
                        + "bundle install\n"));

        if (ctx.repository().isHosted()) {
            snippets.add(ConnectSnippet.file("gem-credentials", "gem push (~/.gem/credentials)", "yaml", "~/.gem/credentials",
                    "---\n"
                            + ":granary: " + ctx.token() + "\n"
                            + ctx.url() + ": " + ctx.token() + "\n"
                            + "# then: chmod 0600 ~/.gem/credentials\n"));
            snippets.add(ConnectSnippet.shell("gem-push", "gem push",
                    "gem build *.gemspec\n"
                            + "gem push --host " + ctx.url() + " --key granary <gem>-<version>.gem\n"
                            + "# or without ~/.gem/credentials:\n"
                            + "GEM_HOST_API_KEY=" + ctx.token() + " gem push --host " + ctx.url() + " <gem>-<version>.gem\n"));
            snippets.add(ConnectSnippet.shell("gem-signin", "gem signin",
                    "# exchanges your Granary username and password for an API token and stores it in the gem\n"
                            + "# credentials file (~/.gem/credentials, or ~/.local/share/gem/credentials).\n"
                            + "# Set RUBYGEMS_HOST rather than passing --host: with --host alone RubyGems first sends\n"
                            + "# the credentials to rubygems.org to look up its multi-factor settings.\n"
                            + "RUBYGEMS_HOST=" + ctx.url() + " gem signin\n"
                            + "gem push --host " + ctx.url() + " <gem>-<version>.gem\n"));
            snippets.add(ConnectSnippet.shell("gem-yank", "gem yank",
                    "gem yank --host " + ctx.url() + " --key granary <gem> --version <version>\n"
                            + "# a platform-specific version: add --platform x86_64-linux\n"));
            snippets.add(ConnectSnippet.file("gemspec-push-host", "gemspec (allowed_push_host)", "ruby", "<gem>.gemspec",
                    "Gem::Specification.new do |spec|\n"
                            + "  # ...\n"
                            + "  spec.metadata[\"allowed_push_host\"] = \"" + ctx.url() + "\"\n"
                            + "end\n"));
        }
        return snippets;
    }

    /** {@code http://user:token@host/…}, the form {@code gem} accepts for credentials inside a source URL. */
    static String withCredentials(String url, String username, String token) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        return url.substring(0, scheme + 3) + username + ":" + token + "@" + url.substring(scheme + 3);
    }

    /**
     * The environment variable suffix Bundler reads a host's credentials from: the host name upper-cased with
     * {@code .} as {@code __} and {@code -} as {@code ___} (see {@code Bundler::Settings.key_for}).
     */
    static String bundlerEnvKey(String host) {
        return host.replace(".", "__").replace("-", "___").toUpperCase(java.util.Locale.ROOT);
    }

    /** The host name without port or path; Bundler looks credentials up by {@code URI#host}. */
    static String hostName(String hostAndPort) {
        String h = hostAndPort;
        int slash = h.indexOf('/');
        if (slash >= 0) {
            h = h.substring(0, slash);
        }
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            return close > 0 ? h.substring(0, close + 1) : h;
        }
        int colon = h.lastIndexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }
}
