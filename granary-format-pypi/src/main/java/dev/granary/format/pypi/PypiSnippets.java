package dev.granary.format.pypi;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import java.util.ArrayList;
import java.util.List;

/** Ready-to-paste configuration for every Python client that can talk to a repository. */
final class PypiSnippets {

    private PypiSnippets() {
    }

    static List<ConnectSnippet> of(ConnectContext ctx) {
        String simple = ctx.url() + "/simple";
        String upload = ctx.urlWithSlash();
        String host = ctx.host();
        boolean http = !ctx.https();
        String trustedHostFlag = http ? " --trusted-host " + hostOnly(host) : "";
        String credentialsUrl = withCredentials(simple, ctx.username(), ctx.token());
        List<ConnectSnippet> snippets = new ArrayList<>();

        StringBuilder pipConf = new StringBuilder();
        pipConf.append("[global]\n");
        pipConf.append("index-url = ").append(ctx.anonymousRead() ? simple : credentialsUrl).append('\n');
        if (http) {
            pipConf.append("trusted-host = ").append(hostOnly(host)).append('\n');
        }
        if (ctx.anonymousRead()) {
            pipConf.append("# with credentials: index-url = ").append(credentialsUrl).append('\n');
        }
        snippets.add(ConnectSnippet.file("pip-conf", "pip (pip.conf)", "ini",
                "~/.config/pip/pip.conf (Linux), ~/Library/Application Support/pip/pip.conf (macOS), %APPDATA%\\pip\\pip.ini (Windows)",
                pipConf.toString()));
        snippets.add(ConnectSnippet.shell("pip-install", "pip (command line)",
                "pip install --index-url " + simple + trustedHostFlag + " <package>\n"
                        + "# or with credentials:\n"
                        + "pip install --index-url " + credentialsUrl + trustedHostFlag + " <package>\n"));

        snippets.add(ConnectSnippet.shell("uv-env", "uv (environment)",
                "export UV_DEFAULT_INDEX=" + simple + "\n"
                        + "# with credentials: export UV_DEFAULT_INDEX=" + credentialsUrl + "\n"
                        + "# uv before 0.4.23 reads UV_INDEX_URL instead\n"
                        + "uv pip install <package>\n"
                        + "uv add <package>\n"));
        snippets.add(ConnectSnippet.file("uv-pyproject", "uv (pyproject.toml)", "toml", "pyproject.toml",
                "[[tool.uv.index]]\n"
                        + "name = \"granary\"\n"
                        + "url = \"" + simple + "\"\n"
                        + "default = true\n"
                        + "\n"
                        + "# credentials, when anonymous downloads are off:\n"
                        + "#   export UV_INDEX_GRANARY_USERNAME=" + ctx.username() + "\n"
                        + "#   export UV_INDEX_GRANARY_PASSWORD=" + ctx.token() + "\n"));

        snippets.add(ConnectSnippet.shell("poetry", "Poetry",
                "poetry source add --priority=primary granary " + simple + "\n"
                        + "poetry config http-basic.granary " + ctx.username() + " " + ctx.token() + "\n"
                        + "poetry lock && poetry install\n"));

        snippets.add(ConnectSnippet.file("pipenv", "Pipenv (Pipfile)", "toml", "Pipfile",
                "[[source]]\n"
                        + "url = \"" + simple + "\"\n"
                        + "verify_ssl = " + (http ? "false" : "true") + "\n"
                        + "name = \"granary\"\n"));

        snippets.add(ConnectSnippet.shell("pdm", "PDM",
                "pdm config pypi.url " + simple + "\n"
                        + "pdm config pypi.username " + ctx.username() + "\n"
                        + "pdm config pypi.password " + ctx.token() + "\n"
                        + (http ? "pdm config pypi.verify_ssl false\n" : "")));

        snippets.add(ConnectSnippet.file("dockerfile", "Docker build", "text", "Dockerfile",
                "ARG PIP_INDEX_URL=" + simple + "\n"
                        + (http ? "ARG PIP_TRUSTED_HOST=" + hostOnly(host) + "\n" : "")
                        + "RUN pip install --no-cache-dir -r requirements.txt\n"));

        if (ctx.repository().isHosted()) {
            snippets.add(ConnectSnippet.file("twine-pypirc", "twine (.pypirc)", "ini", "~/.pypirc",
                    "[distutils]\n"
                            + "index-servers =\n"
                            + "    granary\n"
                            + "\n"
                            + "[granary]\n"
                            + "repository = " + upload + "\n"
                            + "username = __token__\n"
                            + "password = " + ctx.token() + "\n"));
            snippets.add(ConnectSnippet.shell("twine-upload", "twine (upload)",
                    "python -m build\n"
                            + "twine upload -r granary dist/*\n"
                            + "# or without .pypirc:\n"
                            + "twine upload --repository-url " + upload + " -u __token__ -p " + ctx.token() + " dist/*\n"));
            snippets.add(ConnectSnippet.shell("uv-publish", "uv publish",
                    "uv build\n"
                            + "uv publish --publish-url " + upload + " --check-url " + simple
                            + " --username __token__ --password " + ctx.token() + "\n"));
            snippets.add(ConnectSnippet.shell("hatch-publish", "Hatch",
                    "hatch build\n"
                            + "hatch publish -r " + upload + " -u __token__ -a " + ctx.token() + "\n"));
            snippets.add(ConnectSnippet.shell("flit-publish", "Flit",
                    "# uses the [granary] section of ~/.pypirc above\n"
                            + "flit publish --repository granary\n"));
            snippets.add(ConnectSnippet.shell("poetry-publish", "Poetry (publish)",
                    "poetry config repositories.granary " + upload + "\n"
                            + "poetry config http-basic.granary __token__ " + ctx.token() + "\n"
                            + "poetry publish --build -r granary\n"));
        }
        return snippets;
    }

    /** {@code http://user:token@host/…}, the form pip and uv accept for credentials inside the index URL. */
    static String withCredentials(String url, String username, String token) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        return url.substring(0, scheme + 3) + username + ":" + token + "@" + url.substring(scheme + 3);
    }

    /** {@code host:port} without any path, the value pip's {@code --trusted-host} wants. */
    static String hostOnly(String host) {
        int slash = host.indexOf('/');
        return slash < 0 ? host : host.substring(0, slash);
    }
}
