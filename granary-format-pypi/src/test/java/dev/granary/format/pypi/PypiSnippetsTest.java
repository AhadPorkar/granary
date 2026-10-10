package dev.granary.format.pypi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.util.List;
import org.junit.jupiter.api.Test;

class PypiSnippetsTest {

    private static ConnectContext context(RepositoryType type, String server, boolean anonymousRead) {
        Repository repo = PypiTestSupport.repository("pypi-" + type.name().toLowerCase(), type);
        return new ConnectContext(repo, server + "/repository/" + repo.getName(), server, "alice", "<api-token>", anonymousRead);
    }

    @Test
    void everyClientIsCoveredForAHostedRepository() {
        List<ConnectSnippet> snippets = PypiSnippets.of(context(RepositoryType.HOSTED, "https://granary.example.com", true));
        assertThat(snippets).extracting(ConnectSnippet::id).containsExactly(
                "pip-conf", "pip-install", "uv-env", "uv-pyproject", "poetry", "pipenv", "pdm", "dockerfile",
                "twine-pypirc", "twine-upload", "uv-publish", "hatch-publish", "flit-publish", "poetry-publish");
        assertThat(snippets).extracting(ConnectSnippet::id).doesNotHaveDuplicates();
        for (ConnectSnippet s : snippets) {
            assertThat(s.title()).isNotBlank();
            assertThat(s.code()).isNotBlank();
            assertThat(s.language()).isIn("shell", "ini", "toml", "text");
        }
        String all = String.join("\n", snippets.stream().map(ConnectSnippet::code).toList());
        assertThat(all).contains("index-url = https://granary.example.com/repository/pypi-hosted/simple");
        assertThat(all).contains("pip install --index-url https://granary.example.com/repository/pypi-hosted/simple");
        assertThat(all).contains("UV_DEFAULT_INDEX=https://granary.example.com/repository/pypi-hosted/simple");
        assertThat(all).contains("uv publish --publish-url https://granary.example.com/repository/pypi-hosted/"
                + " --check-url https://granary.example.com/repository/pypi-hosted/simple");
        assertThat(all).contains("[[tool.uv.index]]");
        assertThat(all).contains("poetry source add --priority=primary granary https://granary.example.com/repository/pypi-hosted/simple");
        assertThat(all).contains("poetry config http-basic.granary alice <api-token>");
        assertThat(all).contains("[[source]]");
        assertThat(all).contains("pdm config pypi.url https://granary.example.com/repository/pypi-hosted/simple");
        assertThat(all).contains("repository = https://granary.example.com/repository/pypi-hosted/");
        assertThat(all).contains("username = __token__");
        assertThat(all).contains("twine upload -r granary dist/*");
        assertThat(all).contains("uv publish --publish-url https://granary.example.com/repository/pypi-hosted/");
        assertThat(all).contains("hatch publish -r https://granary.example.com/repository/pypi-hosted/");
        assertThat(all).contains("flit publish --repository granary");
        assertThat(all).contains("ARG PIP_INDEX_URL=https://granary.example.com/repository/pypi-hosted/simple");
        assertThat(all).doesNotContain("trusted-host").doesNotContain("verify_ssl = false");
        assertThat(all).contains("verify_ssl = true");
        assertThat(snippets.stream().filter(s -> s.id().equals("twine-pypirc")).findFirst().orElseThrow().filename()).isEqualTo("~/.pypirc");
    }

    @Test
    void readOnlyRepositoriesGetNoUploadSnippets() {
        for (RepositoryType type : List.of(RepositoryType.PROXY, RepositoryType.GROUP)) {
            List<ConnectSnippet> snippets = PypiSnippets.of(context(type, "https://granary.example.com", true));
            assertThat(snippets).extracting(ConnectSnippet::id)
                    .contains("pip-conf", "uv-env", "poetry", "pipenv", "pdm")
                    .doesNotContain("twine-pypirc", "twine-upload", "uv-publish", "hatch-publish", "flit-publish");
        }
    }

    @Test
    void plainHttpAddsTrustedHostHints() {
        List<ConnectSnippet> snippets = PypiSnippets.of(context(RepositoryType.GROUP, "http://granary.local:8081", false));
        String all = String.join("\n", snippets.stream().map(ConnectSnippet::code).toList());
        assertThat(all).contains("trusted-host = granary.local:8081");
        assertThat(all).contains("--trusted-host granary.local:8081");
        // uv talks plain http without any flag; its insecure-host option only skips certificate checks
        assertThat(all).doesNotContain("UV_INSECURE_HOST");
        assertThat(all).contains("verify_ssl = false");
        assertThat(all).contains("ARG PIP_TRUSTED_HOST=granary.local:8081");
        assertThat(all).contains("pdm config pypi.verify_ssl false");
        // anonymous downloads are off, so the pip.conf carries credentials in the URL
        assertThat(all).contains("index-url = http://alice:<api-token>@granary.local:8081/repository/pypi-group/simple");
    }

    @Test
    void helpers() {
        assertThat(PypiSnippets.withCredentials("https://h/x", "u", "t")).isEqualTo("https://u:t@h/x");
        assertThat(PypiSnippets.withCredentials("nourl", "u", "t")).isEqualTo("nourl");
        assertThat(PypiSnippets.hostOnly("granary.local:8081/ctx")).isEqualTo("granary.local:8081");
        assertThat(PypiSnippets.hostOnly("granary.local")).isEqualTo("granary.local");
    }
}
