package dev.granary.format.helm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UpstreamUrlsTest {

    @ParameterizedTest
    @CsvSource({
            "https://charts.example.com, charts/a-1.0.0.tgz, https://charts.example.com/charts/a-1.0.0.tgz",
            "https://charts.example.com/, a-1.0.0.tgz, https://charts.example.com/a-1.0.0.tgz",
            "https://example.com/bitnami, a-1.0.0.tgz, https://example.com/bitnami/a-1.0.0.tgz",
            "https://example.com/bitnami/, ../other/a.tgz, https://example.com/other/a.tgz",
            "https://example.com/repo, /abs/a.tgz, https://example.com/abs/a.tgz",
            "https://example.com/repo, https://github.com/x/releases/download/a-1.0.0/a-1.0.0.tgz, https://github.com/x/releases/download/a-1.0.0/a-1.0.0.tgz",
            "https://example.com/repo?token=abc, charts/a.tgz, https://example.com/repo/charts/a.tgz?token=abc",
            "https://example.com/repo, charts/a.tgz?x=1, https://example.com/repo/charts/a.tgz",
            "https://example.com/repo, a%20b.tgz, https://example.com/repo/a%20b.tgz",
            "http://localhost:8080/r, a.tgz, http://localhost:8080/r/a.tgz",
    })
    void resolvesLikeHelm(String repositoryUrl, String ref, String expected) {
        assertThat(UpstreamUrls.resolve(repositoryUrl, ref)).contains(expected);
    }

    @Test
    void refusesWhatIsNotAnHttpUrl() {
        assertThat(UpstreamUrls.resolve("https://example.com", "file:///etc/passwd")).isEmpty();
        assertThat(UpstreamUrls.resolve("https://example.com", "oci://registry/charts/a")).isEmpty();
        assertThat(UpstreamUrls.resolve("https://example.com", "http://[broken")).isEmpty();
        assertThat(UpstreamUrls.resolve("https://example.com", "a b.tgz")).isEmpty();
    }

    @Test
    void repositoryUrlDropsAPastedIndexAndTrailingSlashes() {
        assertThat(UpstreamUrls.repositoryUrl("https://charts.example.com/index.yaml")).isEqualTo("https://charts.example.com");
        assertThat(UpstreamUrls.repositoryUrl(" https://charts.example.com/stable// ")).isEqualTo("https://charts.example.com/stable");
        assertThat(UpstreamUrls.repositoryUrl(null)).isEmpty();
        // a query string (a signed storage URL) belongs to the repository URL and is kept
        assertThat(UpstreamUrls.repositoryUrl("https://acct.blob.example.net/charts/?sv=1&sig=a%2Fb"))
                .isEqualTo("https://acct.blob.example.net/charts?sv=1&sig=a%2Fb");
        assertThat(UpstreamUrls.repositoryUrl("https://h/charts/index.yaml?token=t#top")).isEqualTo("https://h/charts?token=t");
    }

    @Test
    void documentUrlsAreBuiltLikeHelmBuildsTheIndexUrl() {
        assertThat(UpstreamUrls.documentUrl("https://charts.example.com/stable/", "index.yaml"))
                .contains(java.net.URI.create("https://charts.example.com/stable/index.yaml"));
        assertThat(UpstreamUrls.documentUrl("https://charts.example.com", "index.yaml"))
                .contains(java.net.URI.create("https://charts.example.com/index.yaml"));
        assertThat(UpstreamUrls.documentUrl("https://charts.example.com/stable/index.yaml", "artifacthub-repo.yml"))
                .contains(java.net.URI.create("https://charts.example.com/stable/artifacthub-repo.yml"));
        // helm keeps the repository URL's query on the index URL: https://host/charts?sig=… → …/charts/index.yaml?sig=…
        assertThat(UpstreamUrls.documentUrl("https://acct.blob.example.net/charts?sv=1&sig=a%2Fb", "index.yaml"))
                .contains(java.net.URI.create("https://acct.blob.example.net/charts/index.yaml?sv=1&sig=a%2Fb"));
        assertThat(UpstreamUrls.documentUrl("", "index.yaml")).isEmpty();
        assertThat(UpstreamUrls.documentUrl(null, "index.yaml")).isEmpty();
        assertThat(UpstreamUrls.documentUrl("http://[broken", "index.yaml")).isEmpty();
    }

    @Test
    void lastSegment() {
        assertThat(UpstreamUrls.lastSegment("https://h/a/b%2Bc.tgz?x=1")).isEqualTo("b+c.tgz");
        assertThat(UpstreamUrls.lastSegment("https://h/")).isEmpty();
        assertThat(UpstreamUrls.lastSegment("https://h")).isEmpty();
        assertThat(UpstreamUrls.lastSegment("::")).isEmpty();
    }

    @Test
    void mapsEveryEntryToAPathInTheRepository() {
        HelmIndex upstream = HelmIndex.parse(("""
                apiVersion: v1
                entries:
                  hello-world:
                  - name: hello-world
                    version: 0.1.0
                    urls: [https://github.com/helm/examples/releases/download/hello-world-0.1.0/hello-world-0.1.0.tgz]
                  relative:
                  - {name: relative, version: 1.0.0, urls: [charts/relative-1.0.0.tgz, https://mirror.example/relative-1.0.0.tgz]}
                  same-file:
                  - {name: same-file, version: 1.0.0, urls: [https://a.example/x/chart.tgz]}
                  - {name: same-file, version: 2.0.0, urls: [https://a.example/y/chart.tgz]}
                  no-extension:
                  - {name: no-extension, version: 1.0.0, urls: ['https://a.example/download?id=7']}
                  no-urls:
                  - {name: no-urls, version: 1.0.0}
                  bad-scheme:
                  - {name: bad-scheme, version: 1.0.0, urls: ['ftp://a.example/a.tgz']}
                """).getBytes(StandardCharsets.UTF_8));
        UpstreamUrls.Mapping mapping = UpstreamUrls.map(upstream, "https://charts.example.com/stable");
        HelmIndex local = mapping.local();

        assertThat(local.find("hello-world", "0.1.0").orElseThrow().urls()).containsExactly("charts/hello-world-0.1.0.tgz");
        assertThat(mapping.upstreamUrl("charts/hello-world-0.1.0.tgz"))
                .contains("https://github.com/helm/examples/releases/download/hello-world-0.1.0/hello-world-0.1.0.tgz");
        assertThat(mapping.upstreamUrl("charts/hello-world-0.1.0.tgz.prov"))
                .contains("https://github.com/helm/examples/releases/download/hello-world-0.1.0/hello-world-0.1.0.tgz.prov");

        // only the first URL is used, resolved against the repository URL
        assertThat(local.find("relative", "1.0.0").orElseThrow().urls()).containsExactly("charts/relative-1.0.0.tgz");
        assertThat(mapping.upstreamUrl("charts/relative-1.0.0.tgz")).contains("https://charts.example.com/stable/charts/relative-1.0.0.tgz");

        // two different upstream files with the same name are told apart by chart and version
        assertThat(local.find("same-file", "1.0.0").orElseThrow().urls()).containsExactly("charts/same-file/1.0.0/chart.tgz");
        assertThat(local.find("same-file", "2.0.0").orElseThrow().urls()).containsExactly("charts/same-file/2.0.0/chart.tgz");
        assertThat(mapping.upstreamUrl("charts/same-file/2.0.0/chart.tgz")).contains("https://a.example/y/chart.tgz");
        assertThat(mapping.upstreamUrl("charts/chart.tgz")).isEmpty();

        // a URL without a usable file name gets the conventional one
        assertThat(local.find("no-extension", "1.0.0").orElseThrow().urls())
                .containsExactly("charts/no-extension/1.0.0/no-extension-1.0.0.tgz");
        assertThat(mapping.upstreamUrl("charts/no-extension/1.0.0/no-extension-1.0.0.tgz")).contains("https://a.example/download?id=7");

        assertThat(local.versions("no-urls")).isEmpty();
        assertThat(local.versions("bad-scheme")).isEmpty();

        assertThat(mapping.archivePath("same-file", "1.0.0")).contains("charts/same-file/1.0.0/chart.tgz");
        assertThat(mapping.archivePath("same-file", "3.0.0")).isEmpty();
        assertThat(mapping.target("charts/hello-world-0.1.0.tgz.prov")).hasValueSatisfying(t -> {
            assertThat(t.name()).isEqualTo("hello-world");
            assertThat(t.version()).isEqualTo("0.1.0");
        });
        assertThat(mapping.target("charts/unknown.tgz")).isEmpty();
    }

    @Test
    void entriesThatCannotBeAddressedSafelyAreDropped() {
        HelmIndex upstream = HelmIndex.parse(("""
                apiVersion: v1
                entries:
                  'bad name':
                  - {version: 1.0.0, urls: ['https://a.example/x/ch art']}
                  ok:
                  - {name: ok, version: 'bad/version', urls: ['https://a.example/x/']}
                  dup:
                  - {name: dup, version: 1.0.0, urls: ['https://a.example/1/dup.tgz']}
                  - {name: dup, version: 1.0.0+b, urls: ['https://a.example/2/dup.tgz']}
                """).getBytes(StandardCharsets.UTF_8));
        UpstreamUrls.Mapping mapping = UpstreamUrls.map(upstream, "https://a.example");
        assertThat(mapping.local().entries().keySet()).containsExactly("dup");
        assertThat(mapping.local().versions("dup")).hasSize(2);
    }
}
