package dev.granary.format.raw;

import static dev.granary.format.raw.RawTestSupport.body;
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
import dev.granary.core.model.RepositoryType;
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

class CdnFormatHandlerTest {

    @TempDir
    Path dir;

    final FakeStorage storage = new FakeStorage();
    final ProxyService proxy = mock(ProxyService.class);
    final Repository jsdelivr = proxy("jsdelivr", "cdn", "https://cdn.jsdelivr.net/");
    CdnFormatHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CdnFormatHandler(storage.mock, proxy, mock(GroupResolver.class), properties(dir));
    }

    @Test
    void unpinnedPathsAreMetadataPinnedOnesAreImmutable() {
        assertThat(handler.isMetadataPath("npm/lodash@4.17.21/lodash.min.js")).isFalse();
        assertThat(handler.isMetadataPath("npm/@scope/pkg@1.0.0/dist/index.js")).isFalse();
        assertThat(handler.isMetadataPath("jquery/3.7.1/jquery.min.js")).isFalse();
        assertThat(handler.isMetadataPath("lodash@4.17.21/lodash.js")).isFalse();
        assertThat(handler.isMetadataPath("npm/lodash@latest/lodash.min.js")).isTrue();
        assertThat(handler.isMetadataPath("npm/lodash@^4/lodash.min.js")).isTrue();
        assertThat(handler.isMetadataPath("npm/lodash/lodash.min.js")).isTrue();
        assertThat(handler.isMetadataPath("lodash/lodash.js")).isTrue();
        assertThat(handler.isMetadataPath("libraries/jquery")).isTrue();
        assertThat(handler.isMetadataPath("libraries")).isTrue();
    }

    @Test
    void proxyFetchesPinnedFilesAsArtifactsAndUnpinnedAsIndexes() throws Exception {
        when(proxy.fetch(eq(jsdelivr), eq("npm/lodash@4.17.21/lodash.min.js"), any(FetchOptions.class)))
                .thenReturn(Optional.of(storage.put(jsdelivr, "npm/lodash@4.17.21/lodash.min.js", "js".getBytes(), "text/javascript; charset=utf-8", false)));
        when(proxy.fetch(eq(jsdelivr), eq("npm/lodash@latest/lodash.min.js"), any(FetchOptions.class)))
                .thenReturn(Optional.of(storage.put(jsdelivr, "npm/lodash@latest/lodash.min.js", "js2".getBytes(), "text/javascript; charset=utf-8", true)));

        ResponseEntity<?> pinned = handler.handle(request(jsdelivr, "GET", "npm/lodash@4.17.21/lodash.min.js"));
        assertThat(pinned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(pinned)).isEqualTo("js");
        assertThat(pinned.getHeaders().getContentType()).hasToString("text/javascript;charset=utf-8");
        assertThat(RawTestSupport.header(pinned, "Cache-Control")).contains("max-age");
        ResponseEntity<?> latest = handler.handle(request(jsdelivr, "GET", "npm/lodash@latest/lodash.min.js"));
        assertThat(RawTestSupport.header(latest, "Cache-Control")).contains("no-cache");

        ArgumentCaptor<FetchOptions> options = ArgumentCaptor.forClass(FetchOptions.class);
        verify(proxy).fetch(eq(jsdelivr), eq("npm/lodash@4.17.21/lodash.min.js"), options.capture());
        assertThat(options.getValue().metadata()).isFalse();
        assertThat(options.getValue().component()).isEqualTo(new StorageService.ComponentCoordinates("npm", "lodash", "4.17.21"));
        verify(proxy).fetch(eq(jsdelivr), eq("npm/lodash@latest/lodash.min.js"), options.capture());
        assertThat(options.getValue().metadata()).isTrue();
        assertThat(options.getValue().component()).isNull(); // an unpinned path is no version of anything
    }

    @Test
    void hostedUploadsRecordNpmCoordinates() throws Exception {
        Repository hosted = hosted("cdn-hosted", "cdn");
        handler.handle(request(hosted, "PUT", "npm/lodash@4.17.21/lodash.min.js", "x"));
        assertThat(storage.coordinates(hosted, "npm/lodash@4.17.21/lodash.min.js"))
                .isEqualTo(new StorageService.ComponentCoordinates("npm", "lodash", "4.17.21"));
        assertThat(storage.asset(hosted, "npm/lodash@4.17.21/lodash.min.js").isMetadata()).isFalse();
        handler.handle(request(hosted, "PUT", "npm/lodash@latest/lodash.min.js", "x"));
        assertThat(storage.asset(hosted, "npm/lodash@latest/lodash.min.js").isMetadata()).isTrue();
    }

    @Test
    void templatesAndSnippets() {
        assertThat(handler.supportedTypes()).contains(RepositoryType.PROXY, RepositoryType.HOSTED, RepositoryType.GROUP);
        assertThat(handler.templates()).extracting(RepositoryTemplate::name).containsExactly("cdnjs", "jsdelivr", "unpkg");
        assertThat(handler.templates()).extracting(RepositoryTemplate::remoteUrl)
                .containsExactly("https://cdnjs.cloudflare.com/ajax/libs/", "https://cdn.jsdelivr.net/", "https://unpkg.com/");
        var snippets = handler.connectSnippets(context(jsdelivr, true));
        assertThat(snippets).extracting(ConnectSnippet::id).contains("script-tag", "importmap", "vite", "webpack", "curl");
        String script = snippets.stream().filter(s -> s.id().equals("script-tag")).findFirst().orElseThrow().code();
        assertThat(script).contains("<script src=\"http://granary.test/repository/jsdelivr/npm/");
        Repository cdnjs = proxy("cdnjs", "cdn", "https://cdnjs.cloudflare.com/ajax/libs/");
        String cdnjsScript = handler.connectSnippets(context(cdnjs, true)).stream().filter(s -> s.id().equals("script-tag")).findFirst().orElseThrow().code();
        assertThat(cdnjsScript).contains("http://granary.test/repository/cdnjs/lodash.js/4.17.21/lodash.min.js");
        String curl = handler.connectSnippets(context(jsdelivr, false)).stream().filter(s -> s.id().equals("curl")).findFirst().orElseThrow().code();
        assertThat(curl).contains("-u " + dev.granary.core.format.ConnectContext.USERNAME_PLACEHOLDER + ":");
    }
}
