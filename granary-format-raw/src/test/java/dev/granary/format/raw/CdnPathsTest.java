package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.storage.StorageService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class CdnPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {"npm/lodash@4.17.21/lodash.min.js", "npm/@scope/pkg@1.0.0/dist/x.js", "gh/user/repo@v1.2.3/file.js",
            "gh/user/repo@1.2.3-beta.1/file.js", "lodash@4.17.21/lodash.js", "@scope/pkg@1.0.0/x.js", "lodash.js/4.17.21/lodash.min.js",
            "jquery/3.7.1/jquery.min.js", "twitter-bootstrap/5.3.3/css/bootstrap.min.css", "npm/lodash@4.17.21/", "npm/vue@3.4.0+build.1/x"})
    void pinnedPathsAreImmutable(String path) {
        assertThat(CdnPaths.isPinned(path)).as(path).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"npm/lodash/lodash.min.js", "npm/lodash@latest/lodash.min.js", "npm/lodash@^4/x.js", "npm/lodash@4/x.js",
            "npm/lodash@4.17/x.js", "lodash/lodash.js", "@scope/pkg/x.js", "@scope/pkg@latest/x.js", "libraries/jquery", "npm/lodash",
            "gh/user/repo@main/file.js", "jquery/latest/jquery.js", "", "npm/lodash@/x.js"})
    void unpinnedPathsAreMetadata(String path) {
        assertThat(CdnPaths.isPinned(path)).as(path).isFalse();
    }

    @Test
    void coordinatesNameThePackageAndVersion() {
        StorageService.ComponentCoordinates jsdelivr = CdnPaths.coordinates("npm/lodash@4.17.21/lodash.min.js");
        assertThat(jsdelivr.namespace()).isEqualTo("npm");
        assertThat(jsdelivr.name()).isEqualTo("lodash");
        assertThat(jsdelivr.version()).isEqualTo("4.17.21");

        StorageService.ComponentCoordinates scoped = CdnPaths.coordinates("npm/@scope/pkg@1.0.0/dist/x.js");
        assertThat(scoped.namespace()).isEqualTo("npm");
        assertThat(scoped.name()).isEqualTo("@scope/pkg");

        StorageService.ComponentCoordinates github = CdnPaths.coordinates("gh/user/repo@v1.2.3/file.js");
        assertThat(github.namespace()).isEqualTo("gh");
        assertThat(github.name()).isEqualTo("user/repo");
        assertThat(github.version()).isEqualTo("v1.2.3");

        StorageService.ComponentCoordinates unpkg = CdnPaths.coordinates("@scope/pkg@1.0.0/x.js");
        assertThat(unpkg.namespace()).isEmpty();
        assertThat(unpkg.name()).isEqualTo("@scope/pkg");

        StorageService.ComponentCoordinates cdnjs = CdnPaths.coordinates("jquery/3.7.1/jquery.min.js");
        assertThat(cdnjs.name()).isEqualTo("jquery");
        assertThat(cdnjs.version()).isEqualTo("3.7.1");

        StorageService.ComponentCoordinates generic = CdnPaths.coordinates("npm/lodash/lodash.min.js");
        assertThat(generic.name()).isEqualTo("npm/lodash");
        assertThat(generic.version()).isEqualTo("lodash.min.js");
    }
}
