package dev.granary.format.conan;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import java.util.ArrayList;
import java.util.List;

/** The ready-to-paste client configuration shown on a Conan repository's "Connect" page. */
public final class ConanSnippets {

    /** The remote name used throughout; Conan derives the credential variables from it. */
    public static final String REMOTE = "granary";
    static final String ENV_SUFFIX = REMOTE.toUpperCase(java.util.Locale.ROOT).replace('-', '_');

    private ConanSnippets() {
    }

    /**
     * @param hostedMember for a group, the hosted member uploads go to ({@code null} when it has none); for a hosted
     *                     repository its own name; {@code null} for a proxy
     */
    public static List<ConnectSnippet> build(ConnectContext context, String hostedMember) {
        Repository repo = context.repository();
        String url = context.url();
        List<ConnectSnippet> snippets = new ArrayList<>();

        StringBuilder add = new StringBuilder()
                .append("conan remote add ").append(REMOTE).append(' ').append(url).append(" --index 0\n");
        if (repo.isHosted()) {
            add.append("# keep conancenter for public packages, or point Conan at a group that proxies it\n");
        } else {
            add.append("# ConanCenter is reached through Granary, so the public remote is no longer needed:\n")
                    .append("conan remote remove conancenter\n")
                    .append("# or keep it configured but unused:\n")
                    .append("conan remote disable conancenter\n");
        }
        snippets.add(ConnectSnippet.shell("remote-add", "conan remote add", add.toString()));

        snippets.add(ConnectSnippet.shell("login", "conan remote login",
                "conan remote login " + REMOTE + " " + context.username() + " -p " + context.token() + "\n"
                        + "# the password is a Granary API token (or your account password); omit -p to be asked for it\n"
                        + (context.anonymousRead() ? "# anonymous downloads are enabled, so logging in is only needed for uploads\n" : "")));

        snippets.add(ConnectSnippet.shell("env", "Credentials from the environment (CI)",
                "export CONAN_LOGIN_USERNAME_" + ENV_SUFFIX + "=" + context.username() + "\n"
                        + "export CONAN_PASSWORD_" + ENV_SUFFIX + "=" + context.token() + "\n"
                        + "# Conan reads these when the remote '" + REMOTE + "' asks for credentials\n"));

        snippets.add(ConnectSnippet.shell("install", "conan install",
                "conan install . -r " + REMOTE + " --build=missing\n"
                        + "conan install --requires=zlib/1.3.1 -r " + REMOTE + " --build=missing\n"));

        snippets.add(ConnectSnippet.shell("upload", "conan upload", upload(context, hostedMember)));

        snippets.add(ConnectSnippet.shell("search", "conan search and conan list",
                "conan search \"*\" -r " + REMOTE + "\n"
                        + "conan list \"zlib/*\" -r " + REMOTE + "\n"
                        + "conan list \"zlib/1.3.1:*\" -r " + REMOTE + "   # binaries of one version\n"));

        snippets.add(ConnectSnippet.file("tls", "HTTPS with a private certificate", "ini", "global.conf",
                "# ~/.conan2/global.conf: trust the CA that signed the Granary certificate\n"
                        + "core.net.http:cacert_path = /etc/ssl/certs/granary-ca.pem\n"
                        + "# or for a single command: -cc core.net.http:cacert_path=/etc/ssl/certs/granary-ca.pem\n"
                        + "# or, for a quick test only: conan remote add " + REMOTE + " " + url + " --insecure\n"));

        snippets.add(ConnectSnippet.shell("conan1", "Conan 1.x", conan1(context, hostedMember)));
        return snippets;
    }

    private static String conan1(ConnectContext context, String hostedMember) {
        Repository repo = context.repository();
        StringBuilder s = new StringBuilder()
                .append("# Conan 1.x speaks the same v2 API once revisions are enabled\n")
                .append("conan config set general.revisions_enabled=1\n")
                .append("conan remote add ").append(REMOTE).append(' ').append(context.url())
                .append("   # append False to skip certificate verification\n")
                .append("conan user -r ").append(REMOTE).append(" -p ").append(context.token()).append(' ')
                .append(context.username()).append('\n')
                .append("conan install zlib/1.3.1@ -r ").append(REMOTE).append(" --build=missing\n");
        if (repo.isHosted()) {
            s.append("conan upload \"*\" -r ").append(REMOTE).append(" --all --confirm\n");
        } else if (repo.isGroup() && hostedMember != null) {
            String hosted = REMOTE + "-hosted";
            s.append("# uploads go to the hosted member '").append(hostedMember).append("':\n")
                    .append("conan remote add ").append(hosted).append(' ').append(context.serverUrl())
                    .append("/repository/").append(hostedMember).append('\n')
                    .append("conan user -r ").append(hosted).append(" -p ").append(context.token()).append(' ')
                    .append(context.username()).append('\n')
                    .append("conan upload \"*\" -r ").append(hosted).append(" --all --confirm\n");
        }
        return s.toString();
    }

    private static String upload(ConnectContext context, String hostedMember) {
        Repository repo = context.repository();
        if (repo.isHosted()) {
            return "conan create .\n"
                    + "conan upload \"*\" -r " + REMOTE + " --confirm\n"
                    + "conan upload \"acme/1.0\" -r " + REMOTE + " --confirm --only-recipe   # recipe without binaries\n";
        }
        if (repo.isGroup() && hostedMember != null) {
            String hostedUrl = context.serverUrl() + "/repository/" + hostedMember;
            return "# groups are read-only; uploads go to the hosted member '" + hostedMember + "':\n"
                    + "conan remote add " + REMOTE + "-hosted " + hostedUrl + "\n"
                    + "conan remote login " + REMOTE + "-hosted " + context.username() + " -p " + context.token() + "\n"
                    + "conan upload \"*\" -r " + REMOTE + "-hosted --confirm\n";
        }
        if (repo.isGroup()) {
            return "# groups are read-only and this one has no hosted member; add one to upload packages\n";
        }
        return "# proxies are read-only; upload to a hosted repository instead\n";
    }
}
