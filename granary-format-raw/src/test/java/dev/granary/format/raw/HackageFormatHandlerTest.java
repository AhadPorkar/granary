package dev.granary.format.raw;

import static dev.granary.format.raw.RawTestSupport.body;
import static dev.granary.format.raw.RawTestSupport.context;
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
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class HackageFormatHandlerTest {

    @TempDir
    Path dir;

    final FakeStorage storage = new FakeStorage();
    final ProxyService proxy = mock(ProxyService.class);
    final Repository hackage = proxy("hackage-proxy", "hackage", "https://hackage.haskell.org/");
    HackageFormatHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HackageFormatHandler(storage.mock, proxy, mock(GroupResolver.class), properties(dir));
    }

    @Test
    void indexFilesAreMetadataPackagesAreNot() {
        assertThat(handler.isMetadataPath("01-index.tar.gz")).isTrue();
        assertThat(handler.isMetadataPath("01-index.tar")).isTrue();
        assertThat(handler.isMetadataPath("01-index.tar.idx")).isTrue();
        assertThat(handler.isMetadataPath("00-index.tar.gz")).isTrue();
        assertThat(handler.isMetadataPath("root.json")).isTrue();
        assertThat(handler.isMetadataPath("timestamp.json")).isTrue();
        assertThat(handler.isMetadataPath("snapshot.json")).isTrue();
        assertThat(handler.isMetadataPath("mirrors.json")).isTrue();
        assertThat(handler.isMetadataPath("packages/index.tar.gz")).isTrue();
        assertThat(handler.isMetadataPath("package/aeson/preferred")).isTrue();
        assertThat(handler.isMetadataPath("lts-22.1/cabal.config")).isTrue();
        assertThat(handler.isMetadataPath("snapshots.json")).isTrue();
        assertThat(handler.isMetadataPath("lts-22.1")).isTrue();
        assertThat(handler.isMetadataPath("nightly-2026-10-01")).isTrue();
        assertThat(handler.isMetadataPath("lts")).isTrue();
        assertThat(handler.isMetadataPath("package/aeson-2.1.0.0.tar.gz")).isFalse();
        assertThat(handler.isMetadataPath("package/aeson-2.1.0.0/revision/1.cabal")).isFalse();
        assertThat(handler.isMetadataPath("package/aeson-2.1.0.0/aeson.cabal")).isFalse();
    }

    @Test
    void packageCoordinates() {
        assertThat(handler.coordinates("package/aeson-2.1.0.0.tar.gz")).isEqualTo(new StorageService.ComponentCoordinates("", "aeson", "2.1.0.0"));
        assertThat(handler.coordinates("package/aeson-2.1.0.0/aeson.cabal")).isEqualTo(new StorageService.ComponentCoordinates("", "aeson", "2.1.0.0"));
        assertThat(handler.coordinates("package/aeson-2.1.0.0/revision/1.cabal")).isEqualTo(new StorageService.ComponentCoordinates("", "aeson", "2.1.0.0"));
        assertThat(handler.coordinates("package/optparse-applicative-0.18.1.0.tar.gz"))
                .isEqualTo(new StorageService.ComponentCoordinates("", "optparse-applicative", "0.18.1.0"));
        assertThat(handler.coordinates("package/aeson/preferred")).isEqualTo(new StorageService.ComponentCoordinates("", "package/aeson", "preferred"));
        assertThat(HackageFormatHandler.packageId("base-4.18.0.0")).containsExactly("base", "4.18.0.0");
        assertThat(HackageFormatHandler.packageId("nonsense")).isNull();
    }

    @Test
    void proxyFetchesTheIndexAsMetadataAndTarballsAsArtifacts() throws Exception {
        when(proxy.fetch(eq(hackage), eq("01-index.tar.gz"), any(FetchOptions.class)))
                .thenReturn(Optional.of(storage.put(hackage, "01-index.tar.gz", "index".getBytes(), "application/gzip", true)));
        when(proxy.fetch(eq(hackage), eq("package/aeson-2.1.0.0.tar.gz"), any(FetchOptions.class)))
                .thenReturn(Optional.of(storage.put(hackage, "package/aeson-2.1.0.0.tar.gz", "tarball".getBytes(), "application/gzip", false)));

        ResponseEntity<?> index = handler.handle(request(hackage, "GET", "01-index.tar.gz"));
        assertThat(index.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(index)).isEqualTo("index");
        ResponseEntity<?> tarball = handler.handle(request(hackage, "GET", "package/aeson-2.1.0.0.tar.gz"));
        assertThat(body(tarball)).isEqualTo("tarball");

        ArgumentCaptor<FetchOptions> options = ArgumentCaptor.forClass(FetchOptions.class);
        verify(proxy).fetch(eq(hackage), eq("01-index.tar.gz"), options.capture());
        assertThat(options.getValue().metadata()).isTrue();
        assertThat(options.getValue().component()).isNull(); // metadata belongs to no component
        verify(proxy).fetch(eq(hackage), eq("package/aeson-2.1.0.0.tar.gz"), options.capture());
        assertThat(options.getValue().metadata()).isFalse();
        // the cached tarball is linked to the package version, like an uploaded one
        assertThat(options.getValue().component()).isEqualTo(new StorageService.ComponentCoordinates("", "aeson", "2.1.0.0"));
    }

    @Test
    void templatesAndSnippets() {
        assertThat(handler.templates()).extracting(RepositoryTemplate::name).containsExactly("hackage-proxy", "stackage-proxy", "stackage-snapshots");
        assertThat(handler.templates()).extracting(RepositoryTemplate::remoteUrl)
                .containsExactly("https://hackage.haskell.org/", "https://www.stackage.org/",
                        "https://raw.githubusercontent.com/commercialhaskell/stackage-snapshots/master/");
        var snippets = handler.connectSnippets(context(hackage, true));
        assertThat(snippets).extracting(ConnectSnippet::id)
                .contains("cabal-secure", "cabal-secure-named", "cabal-insecure", "cabal-update", "stack-package-index", "stack-snapshots");
        String insecure = snippet(snippets, "cabal-insecure");
        assertThat(insecure).contains("repository hackage-proxy").contains("url: http://granary.test/repository/hackage-proxy/").contains("secure: False");
        // cabal's repository stanza has no username/password fields
        assertThat(insecure).doesNotContain("username:").doesNotContain("password:");
        String secure = snippet(snippets, "cabal-secure");
        assertThat(secure).contains("repository hackage.haskell.org\n  url: http://granary.test/repository/hackage-proxy/")
                .doesNotContain("root-keys").doesNotContain("anonymous read");
        String named = snippet(snippets, "cabal-secure-named");
        assertThat(named).contains("repository hackage-proxy").contains("secure: True").contains("key-threshold: 3");
        for (String key : HackageFormatHandler.HACKAGE_ROOT_KEYS) {
            assertThat(named).contains("    " + key + "\n");
        }
        String stack = snippet(snippets, "stack-package-index");
        // pantry's package-index takes the repository root; '/package/' belongs to the old package-indices format
        assertThat(stack).contains("package-index:\n  download-prefix: http://granary.test/repository/hackage-proxy/")
                .doesNotContain("/package/").doesNotContain("keyids");

        var authenticated = handler.connectSnippets(context(hackage, false));
        String user = dev.granary.core.format.ConnectContext.USERNAME_PLACEHOLDER;
        String token = dev.granary.core.format.ConnectContext.TOKEN_PLACEHOLDER;
        assertThat(snippet(authenticated, "cabal-insecure"))
                .contains("url: http://" + user + ":" + token + "@granary.test/repository/hackage-proxy/")
                .doesNotContain("username:").doesNotContain("password:");
        // cabal never sends credentials for secure repositories, so the snippets say so instead of embedding them
        assertThat(snippet(authenticated, "cabal-secure")).contains("anonymous read").contains("url: http://granary.test/repository/hackage-proxy/");
        assertThat(snippet(authenticated, "cabal-secure-named")).contains("anonymous read").doesNotContain(token);
        assertThat(snippet(authenticated, "stack-package-index"))
                .contains("download-prefix: http://" + user + ":" + token + "@granary.test/repository/hackage-proxy/");
    }

    @Test
    void rootKeysAreDistinctSha256KeyIds() {
        assertThat(HackageFormatHandler.HACKAGE_ROOT_KEYS).hasSize(6).doesNotHaveDuplicates()
                .allMatch(key -> key.matches("[0-9a-f]{64}"));
    }

    private static String snippet(java.util.List<ConnectSnippet> snippets, String id) {
        return snippets.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow().code();
    }
}
