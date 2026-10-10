package dev.granary.format.raw;

import static dev.granary.format.raw.RawTestSupport.body;
import static dev.granary.format.raw.RawTestSupport.bodyBytes;
import static dev.granary.format.raw.RawTestSupport.context;
import static dev.granary.format.raw.RawTestSupport.header;
import static dev.granary.format.raw.RawTestSupport.hosted;
import static dev.granary.format.raw.RawTestSupport.properties;
import static dev.granary.format.raw.RawTestSupport.proxy;
import static dev.granary.format.raw.RawTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class CpanFormatHandlerTest {

    static final String DIST_100 = "authors/id/A/AB/ABC/Acme-Hello-1.00.tar.gz";
    static final String DIST_101 = "authors/id/A/AB/ABC/Acme-Hello-1.01.tar.gz";

    @TempDir
    Path dir;

    final FakeStorage storage = new FakeStorage();
    final Repository hosted = hosted("cpan-hosted", "cpan");
    CpanFormatHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CpanFormatHandler(storage.mock, mock(ProxyService.class), mock(GroupResolver.class), properties(dir));
    }

    static byte[] dist(String version, boolean withMeta) {
        String root = "Acme-Hello-" + version + "/";
        String pm = "package Acme::Hello;\nour $VERSION = '" + version + "';\n1;\n";
        if (withMeta) {
            return TarFixtures.tarGz(
                    root + "META.json", "{\"name\":\"Acme-Hello\",\"version\":\"" + version + "\",\"provides\":{\"Acme::Hello\":{\"file\":\"lib/Acme/Hello.pm\",\"version\":\"" + version + "\"}}}",
                    root + "lib/Acme/Hello.pm", pm);
        }
        return TarFixtures.tarGz(root + "lib/Acme/Hello.pm", pm, root + "lib/Acme/Hello/Extra.pm", "package Acme::Hello::Extra;\nour $VERSION = '0.5';\n1;\n");
    }

    static String gunzip(byte[] bytes) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void uploadingADistributionRegeneratesTheIndex() throws Exception {
        ResponseEntity<?> put = handler.handle(request(hosted, "PUT", DIST_100, dist("1.00", true), "application/gzip"));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.coordinates(hosted, DIST_100)).isEqualTo(new StorageService.ComponentCoordinates("ABC", "Acme-Hello", "1.00"));
        assertThat(storage.asset(hosted, DIST_100).isMetadata()).isFalse();

        String index = storage.text(hosted, CpanFormatHandler.PACKAGES_TXT);
        assertThat(index).startsWith("File:         02packages.details.txt").contains("Line-Count:   1\n");
        assertThat(index).containsPattern("Acme::Hello +1\\.00 +A/AB/ABC/Acme-Hello-1\\.00\\.tar\\.gz\n");
        assertThat(storage.asset(hosted, CpanFormatHandler.PACKAGES_GZ).getOrigin()).isEqualTo(Asset.Origin.GENERATED);
        assertThat(storage.asset(hosted, CpanFormatHandler.PACKAGES_GZ).isMetadata()).isTrue();

        ResponseEntity<?> gz = handler.handle(request(hosted, "GET", CpanFormatHandler.PACKAGES_GZ));
        assertThat(gz.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(gunzip(bodyBytes(gz))).isEqualTo(index);
        assertThat(header(gz, "Cache-Control")).contains("no-cache");
        assertThat(gz.getHeaders().getContentType()).hasToString("application/gzip");
    }

    @Test
    void theHighestVersionWinsAndDeletesRegenerate() throws Exception {
        handler.handle(request(hosted, "PUT", DIST_101, dist("1.01", false), "application/gzip"));
        handler.handle(request(hosted, "PUT", DIST_100, dist("1.00", true), "application/gzip"));
        String index = storage.text(hosted, CpanFormatHandler.PACKAGES_TXT);
        assertThat(index).containsPattern("Acme::Hello +1\\.01 +A/AB/ABC/Acme-Hello-1\\.01\\.tar\\.gz\n")
                .containsPattern("Acme::Hello::Extra +0\\.5 +A/AB/ABC/Acme-Hello-1\\.01\\.tar\\.gz\n")
                .doesNotContain("Acme-Hello-1.00.tar.gz")
                .contains("Line-Count:   2\n");

        assertThat(handler.handle(request(hosted, "DELETE", DIST_101)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String after = storage.text(hosted, CpanFormatHandler.PACKAGES_TXT);
        assertThat(after).containsPattern("Acme::Hello +1\\.00 +A/AB/ABC/Acme-Hello-1\\.00\\.tar\\.gz\n").doesNotContain("Extra").contains("Line-Count:   1\n");
    }

    @Test
    void anEmptyHostedRepositoryStillServesAnIndex() throws Exception {
        ResponseEntity<?> txt = handler.handle(request(hosted, "GET", CpanFormatHandler.PACKAGES_TXT));
        assertThat(txt.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(txt)).contains("Line-Count:   0\n").contains("Written-By:   Granary");
        assertThat(handler.handle(request(hosted, "HEAD", CpanFormatHandler.PACKAGES_GZ)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unreadableArchivesProvideNothingButDoNotBreakTheIndex() throws Exception {
        handler.handle(request(hosted, "PUT", "authors/id/X/XY/XYZ/Broken-0.01.tar.gz", "not a tarball".getBytes(StandardCharsets.UTF_8), "application/gzip"));
        handler.handle(request(hosted, "PUT", DIST_100, dist("1.00", true), "application/gzip"));
        assertThat(storage.text(hosted, CpanFormatHandler.PACKAGES_TXT)).contains("Line-Count:   1\n").contains("Acme::Hello");
        assertThat(handler.handle(request(hosted, "PUT", "authors/id/X/XY/XYZ/README", "readme")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.coordinates(hosted, "authors/id/X/XY/XYZ/README").name()).isEqualTo("authors/id/X/XY/XYZ");
    }

    @Test
    void metadataPatterns() {
        assertThat(handler.isMetadataPath("modules/02packages.details.txt.gz")).isTrue();
        assertThat(handler.isMetadataPath("modules/02packages.details.txt")).isTrue();
        assertThat(handler.isMetadataPath("modules/03modlist.data.gz")).isTrue();
        assertThat(handler.isMetadataPath("modules/06perms.txt.gz")).isTrue();
        assertThat(handler.isMetadataPath("authors/01mailrc.txt.gz")).isTrue();
        assertThat(handler.isMetadataPath("indices/ls-lR.gz")).isTrue();
        assertThat(handler.isMetadataPath("modules/by-module/Acme/Acme-Hello-1.00.tar.gz")).isTrue();
        assertThat(handler.isMetadataPath("authors/id/A/AB/ABC/CHECKSUMS")).isTrue();
        assertThat(handler.isMetadataPath(DIST_100)).isFalse();
    }

    @Test
    void templatesAndSnippets() {
        assertThat(handler.templates()).extracting(RepositoryTemplate::name).containsExactly("cpan-proxy");
        assertThat(handler.templates().get(0).remoteUrl()).isEqualTo("https://www.cpan.org/");
        Repository proxy = proxy("cpan-proxy", "cpan", "https://www.cpan.org/");
        var snippets = handler.connectSnippets(context(proxy, true));
        assertThat(snippets).extracting(ConnectSnippet::id).contains("cpanm", "cpanm-env", "cpan-shell", "carton", "cpm");
        String cpanm = snippets.stream().filter(s -> s.id().equals("cpanm")).findFirst().orElseThrow().code();
        assertThat(cpanm).contains("cpanm --mirror http://granary.test/repository/cpan-proxy/ --mirror-only");
        String shell = snippets.stream().filter(s -> s.id().equals("cpan-shell")).findFirst().orElseThrow().code();
        assertThat(shell).contains("o conf urllist").contains("http://granary.test/repository/cpan-proxy/");
        String env = snippets.stream().filter(s -> s.id().equals("cpanm-env")).findFirst().orElseThrow().code();
        assertThat(env).contains("PERL_CPANM_OPT");
        String carton = snippets.stream().filter(s -> s.id().equals("carton")).findFirst().orElseThrow().code();
        assertThat(carton).contains("PERL_CARTON_MIRROR=http://granary.test/repository/cpan-proxy/");
        String authenticated = handler.connectSnippets(context(proxy, false)).stream().filter(s -> s.id().equals("cpanm")).findFirst().orElseThrow().code();
        assertThat(authenticated).contains("http://" + dev.granary.core.format.ConnectContext.USERNAME_PLACEHOLDER + ":");
    }
}
