package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.storage.StorageService;
import org.junit.jupiter.api.Test;

class RawComponentsTest {

    @Test
    void directoryIsTheNameAndFileNameTheVersion() {
        StorageService.ComponentCoordinates c = RawComponents.generic("releases/app/1.2.3/app-1.2.3.zip");
        assertThat(c.namespace()).isEmpty();
        assertThat(c.name()).isEqualTo("releases/app/1.2.3");
        assertThat(c.version()).isEqualTo("app-1.2.3.zip");
    }

    @Test
    void rootLevelFilesBelongToTheRootComponent() {
        StorageService.ComponentCoordinates c = RawComponents.generic("README.md");
        assertThat(c.name()).isEqualTo("/");
        assertThat(c.version()).isEqualTo("README.md");
    }

    @Test
    void coordinatesThatDoNotFitTheComponentsTableAreDropped() {
        StorageService.ComponentCoordinates ok = new StorageService.ComponentCoordinates("", "n".repeat(500), "v".repeat(200));
        assertThat(RawComponents.fit(ok)).isSameAs(ok);
        assertThat(RawComponents.fit(new StorageService.ComponentCoordinates("", "n".repeat(501), "1"))).isNull();
        assertThat(RawComponents.fit(new StorageService.ComponentCoordinates("", "n", "v".repeat(201)))).isNull();
        assertThat(RawComponents.fit(new StorageService.ComponentCoordinates("a".repeat(501), "n", "1"))).isNull();
        assertThat(RawComponents.fit(new StorageService.ComponentCoordinates("", "", "1"))).isNull();
        assertThat(RawComponents.fit(null)).isNull();
    }
}
