package dev.granary.format.raw;

import static dev.granary.format.raw.RawTestSupport.body;
import static dev.granary.format.raw.RawTestSupport.bodyBytes;
import static dev.granary.format.raw.RawTestSupport.context;
import static dev.granary.format.raw.RawTestSupport.hosted;
import static dev.granary.format.raw.RawTestSupport.properties;
import static dev.granary.format.raw.RawTestSupport.proxy;
import static dev.granary.format.raw.RawTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class CranFormatHandlerTest {

    @TempDir
    Path dir;

    final FakeStorage storage = new FakeStorage();
    final ProxyService proxy = mock(ProxyService.class);
    final Repository hosted = hosted("cran-hosted", "cran");
    CranFormatHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CranFormatHandler(storage.mock, proxy, mock(GroupResolver.class), properties(dir));
    }

    static byte[] tarball(String version) {
        return TarFixtures.tarGz("praise/DESCRIPTION", CranPackagesTest.DESCRIPTION.replace("Version: 1.0.0", "Version: " + version),
                "praise/R/praise.R", "praise <- function() \"nice\"\n");
    }

    static String md5(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }

    @Test
    void uploadingASourcePackageRegeneratesPackages() throws Exception {
        byte[] tgz = tarball("1.0.0");
        ResponseEntity<?> put = handler.handle(request(hosted, "PUT", "src/contrib/praise_1.0.0.tar.gz", tgz, "application/gzip"));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.coordinates(hosted, "src/contrib/praise_1.0.0.tar.gz")).isEqualTo(new StorageService.ComponentCoordinates("", "praise", "1.0.0"));

        String packages = storage.text(hosted, CranFormatHandler.PACKAGES);
        assertThat(packages).startsWith("Package: praise\n")
                .contains("Version: 1.0.0\n")
                .contains("Depends: R (>= 3.0.0)\n")
                .contains("Imports: utils,\n        stats\n")
                .contains("Suggests: testthat\n")
                .contains("License: MIT + file LICENSE\n")
                .contains("MD5sum: " + md5(tgz) + "\n")
                .contains("NeedsCompilation: no\n")
                .doesNotContain("Title:");
        assertThat(storage.asset(hosted, CranFormatHandler.PACKAGES).isMetadata()).isTrue();

        ResponseEntity<?> gz = handler.handle(request(hosted, "GET", CranFormatHandler.PACKAGES_GZ));
        assertThat(gz.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bodyBytes(gz)))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(packages);
        }
        assertThat(body(handler.handle(request(hosted, "GET", CranFormatHandler.PACKAGES)))).isEqualTo(packages);
    }

    @Test
    void onlyTheNewestVersionIsListedOlderOnesStayReachableThroughTheArchive() throws Exception {
        byte[] old = tarball("1.0.0");
        handler.handle(request(hosted, "PUT", "src/contrib/praise_1.0.0.tar.gz", old, "application/gzip"));
        handler.handle(request(hosted, "PUT", "src/contrib/praise_1.1.0.tar.gz", tarball("1.1.0"), "application/gzip"));
        String packages = storage.text(hosted, CranFormatHandler.PACKAGES);
        assertThat(packages).containsOnlyOnce("Package: praise").contains("Version: 1.1.0\n").doesNotContain("1.0.0");

        ResponseEntity<?> archived = handler.handle(request(hosted, "GET", "src/contrib/Archive/praise/praise_1.0.0.tar.gz"));
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyBytes(archived)).isEqualTo(old);
        assertThat(handler.handle(request(hosted, "GET", "src/contrib/Archive/praise/praise_0.9.0.tar.gz")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(handler.handle(request(hosted, "DELETE", "src/contrib/praise_1.1.0.tar.gz")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storage.text(hosted, CranFormatHandler.PACKAGES)).contains("Version: 1.0.0\n").doesNotContain("1.1.0");
    }

    @Test
    void tarballsWithoutADescriptionAreIndexedFromTheirName() throws Exception {
        handler.handle(request(hosted, "PUT", "src/contrib/mystery_2.0.tar.gz", "junk".getBytes(StandardCharsets.UTF_8), "application/gzip"));
        assertThat(storage.text(hosted, CranFormatHandler.PACKAGES)).contains("Package: mystery\n").contains("Version: 2.0\n").contains("MD5sum: ");
        assertThat(body(handler.handle(request(hosted("empty", "cran"), "GET", CranFormatHandler.PACKAGES)))).isEmpty();
    }

    @Test
    void binaryPackagesAndMetadata() throws Exception {
        handler.handle(request(hosted, "PUT", "bin/windows/contrib/4.5/praise_1.0.0.zip", "zip".getBytes(StandardCharsets.UTF_8), "application/zip"));
        assertThat(storage.coordinates(hosted, "bin/windows/contrib/4.5/praise_1.0.0.zip")).isEqualTo(new StorageService.ComponentCoordinates("", "praise", "1.0.0"));
        assertThat(storage.paths(hosted)).doesNotContain(CranFormatHandler.PACKAGES);
        assertThat(handler.isMetadataPath("src/contrib/PACKAGES")).isTrue();
        assertThat(handler.isMetadataPath("src/contrib/PACKAGES.gz")).isTrue();
        assertThat(handler.isMetadataPath("src/contrib/PACKAGES.rds")).isTrue();
        assertThat(handler.isMetadataPath("bin/windows/contrib/4.5/PACKAGES.gz")).isTrue();
        assertThat(handler.isMetadataPath("src/contrib/Meta/current.rds")).isTrue();
        assertThat(handler.isMetadataPath("web/packages/praise/index.html")).isTrue();
        assertThat(handler.isMetadataPath("src/contrib/praise_1.0.0.tar.gz")).isFalse();
        assertThat(handler.isMetadataPath("bin/windows/contrib/4.5/praise_1.0.0.zip")).isFalse();
    }

    @Test
    void proxyRefreshesPackagesAndCachesTarballsForever() throws Exception {
        Repository cran = proxy("cran-proxy", "cran", "https://cloud.r-project.org/");
        when(proxy.fetch(eq(cran), eq("src/contrib/PACKAGES"), any(FetchOptions.class)))
                .thenReturn(Optional.of(storage.put(cran, "src/contrib/PACKAGES", "Package: praise\n".getBytes(), "text/plain", true)));
        assertThat(body(handler.handle(request(cran, "GET", "src/contrib/PACKAGES")))).isEqualTo("Package: praise\n");
        handler.handle(request(cran, "GET", "src/contrib/praise_1.0.0.tar.gz"));
        ArgumentCaptor<FetchOptions> options = ArgumentCaptor.forClass(FetchOptions.class);
        verify(proxy).fetch(eq(cran), eq("src/contrib/PACKAGES"), options.capture());
        assertThat(options.getValue().metadata()).isTrue();
        verify(proxy).fetch(eq(cran), eq("src/contrib/praise_1.0.0.tar.gz"), options.capture());
        assertThat(options.getValue().metadata()).isFalse();
    }

    @Test
    void templatesAndSnippets() {
        assertThat(handler.templates()).extracting(RepositoryTemplate::name).containsExactly("cran-proxy");
        assertThat(handler.templates().get(0).remoteUrl()).isEqualTo("https://cloud.r-project.org/");
        var snippets = handler.connectSnippets(context(hosted, true));
        assertThat(snippets).extracting(ConnectSnippet::id).contains("rprofile", "install-packages", "renv", "pak-remotes", "build-upload");
        String rprofile = snippets.stream().filter(s -> s.id().equals("rprofile")).findFirst().orElseThrow().code();
        assertThat(rprofile).contains("options(repos = c(CRAN = \"http://granary.test/repository/cran-hosted/\"))");
        String install = snippets.stream().filter(s -> s.id().equals("install-packages")).findFirst().orElseThrow().code();
        assertThat(install).contains("install.packages(").contains("repos = \"http://granary.test/repository/cran-hosted/\"");
        String upload = snippets.stream().filter(s -> s.id().equals("build-upload")).findFirst().orElseThrow().code();
        assertThat(upload).contains("R CMD build").contains("src/contrib/");
    }

    @Test
    void aMalformedTarballNeverBreaksTheIndex() throws Exception {
        byte[] header = TarFixtures.header("evil/DESCRIPTION", 0, '0', "");
        header[124] = (byte) 0x80;
        for (int i = 125; i < 136; i++) {
            header[i] = (byte) 0xff;
        }
        byte[] evil = TarFixtures.gzip(header);
        ResponseEntity<?> put = handler.handle(request(hosted, "PUT", "src/contrib/evil_1.0.tar.gz", evil, "application/gzip"));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<?> garbage = handler.handle(request(hosted, "PUT", "src/contrib/junk_2.0.tar.gz",
                "not gzip at all".getBytes(StandardCharsets.UTF_8), "application/gzip"));
        assertThat(garbage.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<?> good = handler.handle(request(hosted, "PUT", "src/contrib/praise_1.0.0.tar.gz", tarball("1.0.0"), "application/gzip"));
        assertThat(good.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String packages = storage.text(hosted, CranFormatHandler.PACKAGES);
        assertThat(packages).contains("Package: evil\nVersion: 1.0\n").contains("Package: junk\nVersion: 2.0\n")
                .contains("Package: praise\nVersion: 1.0.0\n");
        assertThat(handler.handle(request(hosted, "GET", "src/contrib/PACKAGES")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
